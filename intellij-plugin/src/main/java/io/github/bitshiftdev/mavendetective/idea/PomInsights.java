package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.openapi.util.SimpleModificationTracker;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.xml.XmlFile;
import io.github.bitshiftdev.mavendetective.core.deps.ConflictMatrix;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.DuplicateClasses;
import io.github.bitshiftdev.mavendetective.core.deps.ModuleClasspath;
import io.github.bitshiftdev.mavendetective.core.deps.PomCache;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomHints;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Was die Editor-Hinweise in der {@code pom.xml} brauchen (UI-Plan U4): verwaltete Versionen
 * (Inlay), Konflikte über Module (Code Vision), doppelte Klassen und verdrängte höhere Versionen
 * (Gutter-Icon). Alles aus dem <b>gespeicherten</b> Stand und dem letzten Maven-Import, je Datei
 * gecacht – Tippen rechnet nichts neu. Die Konflikt-Matrix rechnet ein Hintergrund-Thread, nie der
 * Highlighting-Lauf; doppelte Klassen stammen nur aus der letzten Prüfung der Seite „Doppelte Klassen“.
 */
@Service(Service.Level.PROJECT)
public final class PomInsights implements Disposable {

    private static final Logger LOG = Logger.getInstance(PomInsights.class);

    /** Repository-POMs ändern sich praktisch nie; über alle Läufe hinweg gecacht (prüft Änderungszeit). */
    private static final PomCache CACHE = IdeCaches.POMS;

    /**
     * Befunde einer POM, nach Schlüssel des {@code <dependency>}-Tags.
     *
     * @param managed     nach {@code managementKey} (roh, wie notiert): Version aus dependencyManagement
     * @param conflicts   nach {@code g:a}: Zeile der Konflikt-Matrix mit abweichenden Versionen
     * @param risks       nach {@code g:a} der direkten Dependency: verdrängte höhere Versionen darunter
     * @param clashes     nach {@code g:a} der direkten Dependency: doppelte Klassen, die über sie kommen
     * @param groupId     groupId des Moduls (für {@code ${project.groupId}})
     */
    record Facts(@NotNull Path pom, @NotNull Map<String, PomHints.ManagedVersion> managed,
                 @NotNull Map<String, ConflictMatrix.Row> conflicts, @NotNull Map<String, List<UpgradeSimulator.Risk>> risks,
                 @NotNull Map<String, List<DuplicateClasses.Clash>> clashes, @Nullable String groupId) {

        static Facts empty(Path pom) {
            return new Facts(pom, Map.of(), Map.of(), Map.of(), Map.of(), null);
        }
    }

    private final Project project;
    /** Ändert sich, wenn sich eine Grundlage ändert (Import, gespeicherte POM, neue Matrix, neue Prüfung). */
    private final SimpleModificationTracker tracker = new SimpleModificationTracker();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean computing = new AtomicBoolean();
    private volatile @Nullable ConflictMatrix conflicts;
    private volatile boolean conflictsValid;
    private volatile @Nullable DuplicateClasses duplicates;

    public PomInsights(@NotNull Project project) {
        this.project = project;
        IdeMaven.onImport(project, this, () -> invalidate(true));
        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent e : events) {
                    VirtualFile f = e.getFile();
                    if (f != null && "pom.xml".equals(f.getName())) {
                        invalidate(false);
                        return;
                    }
                }
            }
        });
    }

    static PomInsights getInstance(@NotNull Project project) {
        return project.getService(PomInsights.class);
    }

    /** Nach einem Import sind auch die doppelten Klassen alt (die Seite zeigt dann „veraltet“). */
    private void invalidate(boolean imported) {
        generation.incrementAndGet();
        conflictsValid = false;
        if (imported) {
            duplicates = null;
        }
        changed();   // Editoren neu hervorheben – sie rechnen die Matrix dann neu an
    }

    /** Neue Matrix aus der Seite „Konflikte“ – spart die eigene Rechnung. */
    void setConflicts(@Nullable ConflictMatrix matrix) {
        conflicts = matrix;
        conflictsValid = true;
        changed();
    }

    /** Ergebnis der Seite „Doppelte Klassen“; {@code null} = keins (abgebrochen, veraltet). */
    void setDuplicates(@Nullable DuplicateClasses result) {
        duplicates = result;
        changed();
    }

    /**
     * Aktuelle Konflikt-Matrix oder {@code null}, solange sie (neu) gerechnet wird – dann startet die
     * Rechnung im Hintergrund, und die Editoren werden danach neu hervorgehoben.
     */
    @Nullable ConflictMatrix matrix() {
        if (conflictsValid) {
            return conflicts;
        }
        if (computing.compareAndSet(false, true)) {
            long gen = generation.get();
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                ConflictMatrix m = null;
                try {
                    m = project.isDisposed() ? null : ConflictMatrixPanel.compute(project);
                } catch (ProcessCanceledException e) {
                    // nächster Highlighting-Lauf fragt erneut
                } catch (RuntimeException e) {
                    LOG.warn("Maven Detective: Konflikt-Matrix für den Editor nicht berechenbar", e);
                } finally {
                    computing.set(false);
                }
                if (project.isDisposed()) {
                    return;
                }
                if (gen == generation.get()) {
                    setConflicts(m);
                } else {
                    // Während der Rechnung änderte sich eine POM: Ergebnis verwerfen, aber die Editoren neu
                    // hervorheben lassen – der nächste Lauf startet die Rechnung mit dem neuen Stand
                    changed();
                }
            });
        }
        return null;
    }

    private void changed() {
        tracker.incModificationCount();
        ApplicationManager.getApplication().invokeLater(() -> DaemonCodeAnalyzer.getInstance(project)
                .restart("Maven Detective: neue Grundlage für POM-Hinweise"), project.getDisposed());
    }

    /**
     * Befunde der POM; gecacht bis zur nächsten neuen Grundlage (gespeicherte {@code pom.xml}, Maven-Import, neue
     * Matrix oder Prüfung) – nicht bei jeder beliebigen Dateiänderung im Projekt (Read-Action).
     */
    @NotNull Facts facts(@NotNull XmlFile file) {
        return CachedValuesManager.getCachedValue(file, () -> CachedValueProvider.Result.create(compute(file), tracker));
    }

    /** Ändert sich bei jeder gespeicherten {@code pom.xml} und jedem Maven-Import (für Caches der Inspections). */
    @NotNull ModificationTracker pomTracker() {
        return tracker;
    }

    private Facts compute(XmlFile file) {
        Path pom = file.getVirtualFile().toNioPath();
        try {
            String groupId = null;
            for (IdeMaven.ModuleRef m : IdeMaven.modules(project)) {
                if (m.pom().equals(pom)) {
                    groupId = m.groupId();
                }
            }
            return new Facts(pom, managed(pom), conflicts(), risks(pom), clashes(pom), groupId);
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Maven Detective: Hinweise für " + pom + " nicht berechenbar", e);
            return Facts.empty(pom);
        }
    }

    private Map<String, PomHints.ManagedVersion> managed(Path pom) {
        PomLocator locator = DependenciesPanel.locator(project, IdeMaven.reactorRoot(project), CACHE, ProgressManager::checkCanceled);
        PomFile file = locator.load(pom);
        Map<String, PomHints.ManagedVersion> out = new LinkedHashMap<>();
        if (file != null) {
            for (PomHints.ManagedVersion m : PomHints.managedVersions(locator, file)) {
                out.putIfAbsent(m.getDependency().managementKey(), m);
            }
        }
        return out;
    }

    /**
     * Abweichende Zeilen der Matrix nach {@code g:a} – sie gelten für den ganzen Reactor, also für jede
     * POM, die das Artifact deklariert oder verwaltet (Modul oder gemeinsamer Parent).
     */
    private Map<String, ConflictMatrix.Row> conflicts() {
        ConflictMatrix m = matrix();
        Map<String, ConflictMatrix.Row> out = new LinkedHashMap<>();
        if (m != null) {
            for (ConflictMatrix.Row r : m.divergent()) {
                out.put(r.ga(), r);
            }
        }
        return out;
    }

    private Map<String, List<UpgradeSimulator.Risk>> risks(Path pom) {
        DepNode tree = IdeMaven.dependencyTree(project, pom);
        Map<String, List<UpgradeSimulator.Risk>> out = new LinkedHashMap<>();
        if (tree != null) {
            for (UpgradeSimulator.Risk r : UpgradeSimulator.risks(tree)) {
                out.computeIfAbsent(r.getDirect(), k -> new ArrayList<>()).add(r);
            }
        }
        return out;
    }

    /**
     * Doppelte Klassen, deren JARs über eine {@code <dependency>} dieser POM kommen – auch aus
     * Modulen, die sie erben. Der Schlüssel ist die Dependency, an der die Exclusion ansetzt, oder
     * das JAR selbst, wenn es direkt deklariert ist.
     */
    private Map<String, List<DuplicateClasses.Clash>> clashes(Path pom) {
        DuplicateClasses d = duplicates;
        Map<String, List<DuplicateClasses.Clash>> out = new LinkedHashMap<>();
        if (d == null) {
            return out;
        }
        String file = pom.toString();
        for (DuplicateClasses.Clash c : d.clashes()) {
            if (!c.isMain()) {
                continue;
            }
            for (ModuleClasspath.Entry e : c.getJars()) {
                for (FixAction f : c.fixesFor(e)) {
                    if (!file.equals(f.param("file"))) {
                        continue;
                    }
                    String key = FixAction.ADD_EXCLUSION.equals(f.getType()) ? f.param("artifact") : e.ga();
                    List<DuplicateClasses.Clash> list = out.computeIfAbsent(key, k -> new ArrayList<>());
                    if (!list.contains(c)) {
                        list.add(c);
                    }
                }
            }
        }
        return out;
    }

    @Override
    public void dispose() {
        generation.incrementAndGet();
    }
}
