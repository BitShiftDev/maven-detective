package io.github.bitshiftdev.mavendetective.idea.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.CopyProvider;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBLoadingPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.StatusText;
import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.idea.MavenDetectiveBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Grundlage aller Seiten des Tool-Windows: Toolbar aus Aktionen (oben), Inhalt mit Ladeanzeige,
 * Statuszeile (unten), Rechnen im Hintergrund mit Abbruch und Generationszähler, Zeilen mit
 * Kontextmenü, F4 und Strg+C. Die gemeinsamen Aktionen ({@link DetectiveActions}) finden die Seite
 * über {@link #KEY} im Data-Context.
 */
public abstract class DetectivePanel extends SimpleToolWindowPanel implements Disposable {

    public static final DataKey<DetectivePanel> KEY = DataKey.create("MavenDetective.Panel");
    public static final String TOOLBAR_PLACE = "MavenDetective.Toolbar";
    public static final String ROW_POPUP = "MavenDetective.RowPopup";

    private static final Logger LOG = Logger.getInstance(DetectivePanel.class);

    protected final Project project;
    private final JBLoadingPanel loading;
    private final JBLabel status = new JBLabel();
    private final List<Background> backgrounds = new ArrayList<>();
    /** Laufende Aufgaben mit Ladeanzeige (nur EDT). */
    private int overlays;
    private volatile boolean disposed;

    protected DetectivePanel(@NotNull Project project) {
        super(true, true);
        this.project = project;
        loading = new JBLoadingPanel(new BorderLayout(), this);
        status.setForeground(NamedColorUtil.getInactiveTextColor());
        status.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(3, 8)));
        status.setVisible(false);
        JPanel content = new JPanel(new BorderLayout());
        content.add(loading, BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);
        setContent(content);
    }

    // ---- Aufbau ----------------------------------------------------------------------------

    /** Hauptinhalt unter der Toolbar (liegt in der Ladeanzeige). */
    protected final void setBody(@NotNull JComponent body) {
        loading.add(body, BorderLayout.CENTER);
    }

    /**
     * Toolbar-Zeile: links optional Auswahlfelder (Modul, Artifact), dann die Aktionen (klappen bei
     * Platzmangel in »), rechts optional das Suchfeld.
     */
    protected final void installToolbar(@Nullable JComponent leading, @NotNull ActionGroup actions, @Nullable JComponent trailing) {
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, actions, true);
        toolbar.setTargetComponent(this);
        toolbar.setLayoutStrategy(ToolbarLayoutStrategy.AUTOLAYOUT_STRATEGY);
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        if (leading != null) {
            if (leading instanceof JPanel) {
                leading.setOpaque(false);   // Formular-Panel: Hintergrund der Toolbar durchscheinen lassen
            }
            row.add(leading, BorderLayout.WEST);
        }
        row.add(toolbar.getComponent(), BorderLayout.CENTER);
        if (trailing != null) {
            row.add(trailing, BorderLayout.EAST);
        }
        row.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(1, 4)));
        setToolbar(row);
    }

    /** Eine registrierte Aktion (plugin.xml) für die Toolbar. */
    protected static @NotNull AnAction registered(@NotNull String id) {
        return ActionManager.getInstance().getAction(id);
    }

    /** Ein-/Ausschalter für das Filter-Menü; liest und schreibt den Zustand im Panel (EDT). */
    protected static @NotNull ToggleAction toggle(@Nls @NotNull String text, @Nls @Nullable String description,
                                                  @NotNull BooleanSupplier get, @NotNull Consumer<Boolean> set) {
        return new DumbAwareToggleAction(text, description, null) {
            @Override
            public boolean isSelected(@NotNull AnActionEvent e) {
                return get.getAsBoolean();
            }

            @Override
            public void setSelected(@NotNull AnActionEvent e, boolean state) {
                set.accept(state);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
    }

    /** Filter als ein Knopf mit Menü – spart Platz im schmalen Tool-Window. */
    protected static @NotNull DefaultActionGroup filterGroup(@NotNull AnAction... toggles) {
        DefaultActionGroup group = DefaultActionGroup.createPopupGroup(() -> MavenDetectiveBundle.message("toolbar.filter"));
        group.getTemplatePresentation().setIcon(AllIcons.General.Filter);
        group.addAll(toggles);
        return group;
    }

    /** Suchfeld für die Toolbar; {@code onChange} läuft bei jeder Eingabe. */
    protected static @NotNull SearchTextField searchField(@Nls @NotNull String tooltip, @NotNull Runnable onChange) {
        SearchTextField field = new SearchTextField(false);
        field.getTextEditor().setColumns(16);
        field.getTextEditor().getEmptyText().setText(MavenDetectiveBundle.message("search.placeholder"));
        field.setToolTipText(tooltip);
        field.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                onChange.run();
            }
        });
        return field;
    }

    /**
     * Tabelle oder Liste mit Scrollbalken, Kontextmenü (Jump to Source, Kopieren), F4 auf den Ort der
     * Auswahl ({@code pfad:zeile}) und Strg+C auf ihren Text. Beides wird beim Aufruf erfragt.
     */
    protected final @NotNull JComponent rows(@NotNull JComponent view, @NotNull Supplier<@Nullable String> location,
                                             @NotNull Supplier<@Nullable String> text) {
        PopupHandler.installPopupMenu(view, ROW_POPUP, ROW_POPUP);
        JBScrollPane scroll = new JBScrollPane(view);
        scroll.setBorder(JBUI.Borders.empty());
        return UiDataProvider.wrapComponent(scroll, sink -> {
            String loc = location.get();
            if (loc != null) {
                sink.lazy(CommonDataKeys.NAVIGATABLE, () -> Locations.navigatable(project, loc));
            }
            String t = text.get();
            if (t != null) {
                sink.set(PlatformDataKeys.COPY_PROVIDER, new TextCopy(t));
            }
        });
    }

    /** Leerer Zustand einer Tabelle/Liste: Satz und optional ein Link, der etwas tut. */
    protected static void emptyText(@NotNull StatusText target, @Nls @NotNull String text, @Nls @Nullable String link,
                                    @Nullable Runnable action) {
        target.setText(text);
        if (link != null && action != null) {
            target.appendSecondaryText(link, SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES, e -> action.run());
        }
    }

    protected final void setStatus(@Nls @Nullable String text) {
        boolean has = text != null && !text.isEmpty();
        status.setText(has ? text : "");
        status.setToolTipText(has ? text : null);
        status.setVisible(has);
    }

    // ---- Haken für die gemeinsamen Aktionen -----------------------------------------------

    /** Zeigt die Aktion „Aktualisieren/Prüfen“ in dieser Seite. */
    public boolean supportsRefresh() {
        return false;
    }

    public boolean canRefresh() {
        return true;
    }

    public void refresh() {
    }

    public @Nls @NotNull String refreshText() {
        return MavenDetectiveBundle.message("action.MavenDetective.Refresh.text");
    }

    public @NotNull Icon refreshIcon() {
        return AllIcons.Actions.Refresh;
    }

    public boolean canCopyMarkdown() {
        return false;
    }

    public @Nullable String markdown() {
        return null;
    }

    /** Text der Kopier-Aktion in dieser Seite; {@code null} = „Als Markdown kopieren“. */
    public @Nls @Nullable String copyMarkdownText() {
        return null;
    }

    @Override
    public void uiDataSnapshot(@NotNull DataSink sink) {
        super.uiDataSnapshot(sink);
        sink.set(KEY, this);
    }

    // ---- Hintergrund -----------------------------------------------------------------------

    /** Eine Aufgabe dieser Seite; mit Text zeigt sie beim Rechnen die Ladeanzeige über dem Inhalt. */
    protected final @NotNull Background background(@Nls @Nullable String loadingText) {
        Background b = new Background(loadingText);
        backgrounds.add(b);
        return b;
    }

    public final boolean isRunning() {
        for (Background b : backgrounds) {
            if (b.isRunning()) {
                return true;
            }
        }
        return false;
    }

    /** Laufende Aufgaben verwerfen; die Rechnung bricht am nächsten Abbruchpunkt ab. */
    public final void cancelRunning() {
        boolean any = false;
        for (Background b : backgrounds) {
            if (b.isRunning()) {
                b.cancel();
                any = true;
            }
        }
        if (any) {
            cancelled();
        }
    }

    /** Nach „Abbrechen“; Standard: Hinweis in der Statuszeile. */
    protected void cancelled() {
        setStatus(MavenDetectiveBundle.message("status.cancelled"));
    }

    /** Rechnung mit Ausnahme beendet; Standard: protokollieren und in der Statuszeile nennen. */
    protected void failed(@NotNull RuntimeException e) {
        LOG.warn("Maven Detective: " + getClass().getSimpleName() + " fehlgeschlagen", e);
        setStatus(MavenDetectiveBundle.message("status.failed", String.valueOf(e.getMessage())));
    }

    protected final boolean isGone() {
        return disposed || project.isDisposed();
    }

    @Override
    public final void dispose() {
        disposed = true;   // laufende Aufgaben verwerfen ihr Ergebnis
    }

    private void startOverlay(@Nls String text) {
        overlays++;
        loading.setLoadingText(text);
        loading.startLoading();
    }

    private void stopOverlay() {
        overlays = Math.max(0, overlays - 1);
        if (overlays == 0) {
            loading.stopLoading();
        }
    }

    /**
     * Rechnet im Hintergrund und liefert das Ergebnis auf dem EDT. Ein neuerer Lauf derselben
     * Aufgabe, „Abbrechen“ oder das Schließen der Seite verwerfen ältere Ergebnisse.
     */
    public final class Background {
        private final @Nls @Nullable String loadingText;
        private volatile int generation;
        /** Gestartete, noch nicht beendete Läufe (nur EDT). */
        private int running;

        private Background(@Nls @Nullable String loadingText) {
            this.loadingText = loadingText;
        }

        public <T> void run(@NotNull Function<Cancellation, T> work, @NotNull Consumer<T> done) {
            int gen = ++generation;
            running++;
            if (loadingText != null) {
                startOverlay(loadingText);
            }
            Cancellation check = () -> {
                if (disposed || project.isDisposed() || gen != generation) {
                    throw new ProcessCanceledException();
                }
            };
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                T result = null;
                RuntimeException error = null;
                boolean cancelled = false;
                try {
                    result = work.apply(check);
                } catch (ProcessCanceledException e) {
                    cancelled = true;
                } catch (RuntimeException e) {
                    error = e;
                }
                T r = result;
                RuntimeException err = error;
                boolean stop = cancelled;
                ApplicationManager.getApplication().invokeLater(() -> {
                    running--;
                    if (loadingText != null) {
                        stopOverlay();
                    }
                    if (stop || gen != generation) {
                        return;
                    }
                    if (err != null) {
                        failed(err);
                    } else {
                        done.accept(r);
                    }
                }, ModalityState.defaultModalityState(), o -> disposed || project.isDisposed());
            });
        }

        public boolean isRunning() {
            return running > 0;
        }

        public void cancel() {
            generation++;
        }

        /** Neue Generation für Arbeit außerhalb von {@link #run} (z. B. {@code Task.Backgroundable}). */
        public int next() {
            return ++generation;
        }

        public boolean isCurrent(int gen) {
            return gen == generation;
        }
    }

    private record TextCopy(String text) implements CopyProvider {
        @Override
        public void performCopy(@NotNull DataContext dataContext) {
            CopyPasteManager.getInstance().setContents(new StringSelection(text));
        }

        @Override
        public boolean isCopyEnabled(@NotNull DataContext dataContext) {
            return true;
        }

        @Override
        public boolean isCopyVisible(@NotNull DataContext dataContext) {
            return true;
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }
}
