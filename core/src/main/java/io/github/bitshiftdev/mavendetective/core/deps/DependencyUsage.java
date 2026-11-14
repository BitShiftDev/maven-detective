package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.deps.ClassUsages.Use;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Declared;
import io.github.bitshiftdev.mavendetective.core.deps.ModuleClasspath.Entry;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Modul A5 – ungenutzte und undeklarierte Dependencies. Grundlage ist, welche Klassen der
 * Quelltext eines Moduls <i>beim Namen nennt</i> ({@link ClassUsages}: in der IDE über den
 * Code-Index aufgelöst, auf der Kommandozeile über den {@link SourceScanner}). Jede genannte Klasse
 * wird dem Classpath-Eintrag zugeordnet, aus dem der Compiler sie nimmt ({@link ModuleClasspath},
 * erster Eintrag gewinnt).
 *
 * <ul>
 *   <li>{@link Kind#UNDECLARED}: genannte Klasse liegt in einem JAR, das nur transitiv kommt –
 *       fällt die vermittelnde Dependency weg oder ändert sie ihre Abhängigkeiten, bricht der Build.
 *       Quick-Fix {@code add-dependency}.</li>
 *   <li>{@link Kind#UNUSED}: deklarierte Dependency, von der keine Klasse genannt wird. Starter ohne
 *       eigene Klassen zählen als genutzt, wenn etwas genannt wird, das sie mitbringen. Bringt das
 *       JAR Dienst-Registrierungen mit ({@code META-INF/services}, Spring-Autokonfiguration), wirkt
 *       es womöglich ohne Nennung – dann nur „wahrscheinlich“. Quick-Fix {@code remove-dependency}.</li>
 *   <li>{@link Kind#TEST_ONLY}: Scope {@code compile}/{@code provided}, aber nur in Tests genannt.
 *       Quick-Fix {@code set-scope} auf {@code test}.</li>
 * </ul>
 *
 * Dependencies mit Scope {@code runtime} und bekannte Laufzeit-Bibliotheken (JDBC-Treiber,
 * Logging-Backends, Test-Engines, Annotation-Prozessoren) werden nie als ungenutzt gemeldet.
 */
public final class DependencyUsage {

    private static final int MAX_EXAMPLES = 5;

    public enum Kind {
        UNDECLARED,
        TEST_ONLY,
        UNUSED;

        public String label() {
            return Texts.t("usage.kind." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** Woher die Klassennamen eines Moduls kommen (IDE-Index oder Quelltext-Scanner). */
    public interface UsageSource {
        ClassUsages usages(PomFile module);
    }

    /** Bekannte Bibliotheken, die man nie beim Namen nennt, aber zur Laufzeit braucht. */
    static final class Known {
        final String[] patterns;
        final String what;

        Known(String what, String... patterns) {
            this.what = what;
            this.patterns = patterns;
        }

        boolean matches(String groupId, String artifactId) {
            for (String p : patterns) {
                int colon = p.indexOf(':');
                String g = p.substring(0, colon);
                String a = p.substring(colon + 1);
                if (!g.equals(groupId)) {
                    continue;
                }
                if (a.equals("*") || a.equals(artifactId) || a.endsWith("*") && artifactId.startsWith(a.substring(0, a.length() - 1))) {
                    return true;
                }
            }
            return false;
        }
    }

    static final List<Known> RUNTIME_ONLY = Collections.unmodifiableList(Arrays.asList(
            new Known("usage.known.jdbc", "com.h2database:h2", "org.hsqldb:hsqldb", "org.apache.derby:derby*", "org.postgresql:postgresql",
                    "com.mysql:mysql-connector-j", "mysql:mysql-connector-java", "org.mariadb.jdbc:mariadb-java-client",
                    "org.xerial:sqlite-jdbc", "com.oracle.database.jdbc:ojdbc*", "com.microsoft.sqlserver:mssql-jdbc",
                    "com.ibm.db2:jcc", "net.sourceforge.jtds:jtds"),
            new Known("usage.known.logging", "ch.qos.logback:logback-classic", "org.slf4j:slf4j-simple", "org.slf4j:slf4j-nop",
                    "org.slf4j:slf4j-reload4j", "org.slf4j:slf4j-log4j12", "org.slf4j:slf4j-jdk14", "org.slf4j:jcl-over-slf4j",
                    "org.slf4j:log4j-over-slf4j", "org.slf4j:jul-to-slf4j", "org.apache.logging.log4j:log4j-slf4j-impl",
                    "org.apache.logging.log4j:log4j-slf4j2-impl", "org.apache.logging.log4j:log4j-to-slf4j",
                    "org.apache.logging.log4j:log4j-jul", "org.apache.logging.log4j:log4j-core", "org.springframework:spring-jcl"),
            new Known("usage.known.test.engine", "org.junit.jupiter:junit-jupiter-engine", "org.junit.vintage:junit-vintage-engine",
                    "org.junit.platform:junit-platform-launcher", "org.junit.platform:junit-platform-suite-engine",
                    "net.bytebuddy:byte-buddy-agent", "org.mockito:mockito-inline"),
            new Known("usage.known.processor", "org.mapstruct:mapstruct-processor", "org.hibernate:hibernate-jpamodelgen",
                    "org.hibernate.orm:hibernate-jpamodelgen", "org.springframework.boot:spring-boot-configuration-processor",
                    "org.springframework.boot:spring-boot-autoconfigure-processor", "com.querydsl:querydsl-apt",
                    "org.projectlombok:lombok-mapstruct-binding", "com.google.dagger:dagger-compiler",
                    "com.google.auto.value:auto-value", "io.micronaut:micronaut-inject-java"),
            new Known("usage.known.runtime.tool", "org.springframework.boot:spring-boot-devtools", "org.springframework.boot:spring-boot-docker-compose",
                    "org.springframework.boot:spring-boot-starter-actuator", "org.springframework.boot:spring-boot-starter-tomcat",
                    "org.springframework.boot:spring-boot-starter-jetty", "org.springframework.boot:spring-boot-starter-undertow",
                    "org.springframework.boot:spring-boot-starter-logging", "org.springframework.boot:spring-boot-starter-log4j2")));

    static Known known(String groupId, String artifactId) {
        for (Known k : RUNTIME_ONLY) {
            if (k.matches(groupId, artifactId)) {
                return k;
            }
        }
        return null;
    }

    /** Ein Befund. */
    public static final class Finding {
        private final ModuleClasspath module;
        private final Kind kind;
        private final Entry entry;
        private final Declared declared;
        private final List<Use> mainUses;
        private final List<Use> testUses;
        private final int mainCount;
        private final int testCount;
        private final boolean certain;
        private final String note;
        private final Entry via;
        private final boolean viaBundle;

        Finding(ModuleClasspath module, Kind kind, Entry entry, Declared declared, List<Use> mainUses, List<Use> testUses,
                int mainCount, int testCount, boolean certain, String note, Entry via, boolean viaBundle) {
            this.module = module;
            this.kind = kind;
            this.entry = entry;
            this.declared = declared;
            this.mainUses = mainUses;
            this.testUses = testUses;
            this.mainCount = mainCount;
            this.testCount = testCount;
            this.certain = certain;
            this.note = note;
            this.via = via;
            this.viaBundle = viaBundle;
        }

        public ModuleClasspath getModule() { return module; }
        public Kind getKind() { return kind; }
        /** Der Classpath-Eintrag: bei UNDECLARED das transitive JAR, sonst die deklarierte Dependency. */
        public Entry getEntry() { return entry; }
        /** Die Deklaration (UNUSED, TEST_ONLY); {@code null} bei UNDECLARED. */
        public Declared getDeclared() { return declared; }
        /** Beispiele aus den Hauptquellen (höchstens fünf). */
        public List<Use> getMainUses() { return Collections.unmodifiableList(mainUses); }
        /** Beispiele aus den Testquellen (höchstens fünf). */
        public List<Use> getTestUses() { return Collections.unmodifiableList(testUses); }
        /** Wie viele verschiedene Klassen aus dem Eintrag in Haupt- bzw. Testquellen genannt werden. */
        public int getMainCount() { return mainCount; }
        public int getTestCount() { return testCount; }
        /** {@code false}, wenn das JAR auch ohne Nennung wirken kann (Dienst-Registrierung). */
        public boolean isCertain() { return certain; }
        /** Zusatz, z. B. welche Registrierungen gefunden wurden; sonst {@code null}. */
        public String getNote() { return note; }
        /** UNDECLARED: die direkte Dependency, über die das JAR heute kommt (kann {@code null} sein). */
        public Entry getVia() { return via; }
        /** UNDECLARED: kommt über einen Starter ohne eigene Klassen – dort üblich und oft gewollt. */
        public boolean isViaBundle() { return viaBundle; }

        public String ga() { return entry.ga(); }

        /** Ist die Deklaration geerbt (steht in einem Parent)? */
        public boolean isInherited() {
            return declared != null && !declared.declaredIn.getFile().equals(module.getPom().getFile());
        }

        /** Scope, mit dem die Dependency deklariert werden sollte (UNDECLARED). */
        public String suggestedScope() {
            if (kind == Kind.TEST_ONLY) {
                return "test";
            }
            if (mainCount == 0) {
                return "test";
            }
            return "provided".equals(entry.getScope()) ? "provided" : "compile";
        }

        /** Befund in einem Satz. */
        public String message() {
            StringBuilder sb = new StringBuilder();
            switch (kind) {
                case UNDECLARED: {
                    Use first = !mainUses.isEmpty() ? mainUses.get(0) : testUses.get(0);
                    int n = mainCount + testCount;
                    sb.append(Texts.t("usage.msg.undeclared", entry.label(), first.getClassName()));
                    if (n > 1) {
                        sb.append(' ').append(Texts.t("usage.msg.more.classes", n - 1));
                    }
                    sb.append(mainCount == 0 ? " (" + Texts.t("usage.kind.test_only") + ")" : "");
                    sb.append(" – ").append(Texts.t("usage.msg.transitive"));
                    if (via != null) {
                        sb.append(' ').append(Texts.t("upgrade.via", via.label()));
                    }
                    if ("runtime".equals(entry.getScope()) && mainCount > 0) {
                        sb.append(", ").append(Texts.t("usage.msg.runtime"));
                    }
                    break;
                }
                case TEST_ONLY:
                    sb.append(Texts.t("usage.msg.test.only", entry.label(), entry.getScope()));
                    if (!testUses.isEmpty()) {
                        sb.append(", ").append(Texts.t("usage.msg.example", testUses.get(0).getClassName()));
                    }
                    sb.append(" – ").append(Texts.t("usage.msg.scope.test"));
                    break;
                default:
                    sb.append(Texts.t(note != null && note.startsWith(Texts.t("usage.note.starter")) ? "usage.msg.unused.starter"
                            : "usage.msg.unused", entry.label()));
                    break;
            }
            if (isInherited()) {
                sb.append(" (").append(Texts.t("usage.msg.declared.in", declared.declaredIn.getArtifactId())).append(')');
            }
            if (!certain) {
                sb.append(" – ").append(Texts.t("usage.msg.maybe.needed"));
            }
            return sb.append('.').toString();
        }

        /** Quick-Fixes; leer, wenn keiner passt. */
        public List<FixAction> fixes() {
            List<FixAction> out = new ArrayList<FixAction>();
            String pomFile = module.getPom().getFile().toString();
            if (kind == Kind.UNDECLARED) {
                EffectiveModel model = module.getModel();
                boolean managed = model.managed(entry.getGroupId(), entry.getArtifactId(), entry.getType(), entry.getClassifier()) != null;
                String scope = suggestedScope();
                out.add(FixAction.of(FixAction.ADD_DEPENDENCY,
                        Texts.t("usage.fix.declare", entry.ga() + (managed ? "" : ":" + entry.getVersion()) + ("compile".equals(scope) ? "" : " (" + scope + ")"),
                                module.getName()),
                        "file", pomFile, "artifact", entry.ga(), "version", managed ? null : entry.getVersion(),
                        "scope", "compile".equals(scope) ? null : scope,
                        "type", "jar".equals(entry.getType()) ? null : entry.getType(), "classifier", entry.getClassifier()));
                return out;
            }
            if (declared == null) {
                return out;
            }
            String file = declared.declaredIn.getFile().toString();
            if (isInherited()) {
                out.add(FixAction.of(FixAction.OPEN_FILE, Texts.t("usage.fix.inherited", entry.ga(), declared.declaredIn.getArtifactId()),
                        "file", file, "line", String.valueOf(declared.dep.getLine())));
                return out;
            }
            if (kind == Kind.TEST_ONLY) {
                out.add(FixAction.of(FixAction.SET_SCOPE, Texts.t("usage.fix.scope.test", entry.ga()),
                        "file", file, "artifact", entry.ga(), "scope", "test", "classifier", declared.dep.getClassifier(),
                        "type", declaredType()));
            } else {
                out.add(FixAction.of(FixAction.REMOVE_DEPENDENCY, Texts.t("usage.fix.remove", entry.ga(), module.getName()),
                        "file", file, "artifact", entry.ga(), "classifier", declared.dep.getClassifier(), "type", declaredType()));
            }
            return out;
        }

        /** Typ der Deklaration für den Quick-Fix; {@code null} bei {@code jar}. */
        private String declaredType() {
            String t = declared.dep.getType();
            return "jar".equals(t) ? null : t;
        }

        /** Zeile der Deklaration (UNUSED/TEST_ONLY) als {@code datei:zeile}, sonst {@code null}. */
        public String declarationLocation() {
            return declared == null ? null : declared.declaredIn.getFile() + ":" + declared.dep.getLine();
        }
    }

    /** Ergebnis für ein Modul. */
    public static final class ModuleResult {
        private final ModuleClasspath classpath;
        private final ClassUsages usages;
        private final List<Finding> findings = new ArrayList<Finding>();
        private final List<Entry> unchecked = new ArrayList<Entry>();
        private final List<String> notes = new ArrayList<String>();

        ModuleResult(ModuleClasspath classpath, ClassUsages usages) {
            this.classpath = classpath;
            this.usages = usages;
        }

        public ModuleClasspath getClasspath() { return classpath; }
        public ClassUsages getUsages() { return usages; }
        public List<Finding> getFindings() { return Collections.unmodifiableList(findings); }
        /** Deklarierte Dependencies, deren JAR lokal fehlt oder kaputt ist – nicht beurteilt. */
        public List<Entry> getUnchecked() { return Collections.unmodifiableList(unchecked); }
        /** Hinweise, z. B. bewusst nicht gemeldete Laufzeit-Bibliotheken. */
        public List<String> getNotes() { return Collections.unmodifiableList(notes); }

        public boolean hasSources() {
            return usages.getMainFiles() + usages.getTestFiles() > 0;
        }
    }

    private final List<ModuleResult> modules;

    private DependencyUsage(List<ModuleResult> modules) {
        this.modules = modules;
    }

    public List<ModuleResult> getModules() { return Collections.unmodifiableList(modules); }

    /** Alle Befunde über alle Module. */
    public List<Finding> findings() {
        List<Finding> out = new ArrayList<Finding>();
        for (ModuleResult m : modules) {
            out.addAll(m.findings);
        }
        return out;
    }

    public List<Finding> findings(Kind kind) {
        List<Finding> out = new ArrayList<Finding>();
        for (Finding f : findings()) {
            if (f.kind == kind) {
                out.add(f);
            }
        }
        return out;
    }

    /** Wurde irgendein Classpath ohne Baum nachgebaut? */
    public boolean isEstimated() {
        for (ModuleResult m : modules) {
            if (!m.classpath.isFromTree()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Alle Module prüfen. POMs mit {@code packaging pom} werden übersprungen.
     *
     * @param trees  aufgelöste Bäume je {@code groupId:artifactId} des Moduls (darf leer sein)
     * @param source liefert je Modul die genannten Klassen
     */
    public static DependencyUsage build(PomLocator locator, List<PomFile> poms, Map<String, DepNode> trees, JarIndex index,
                                        UsageSource source, Cancellation cancellation) {
        Cancellation cancel = cancellation == null ? Cancellation.NONE : cancellation;
        Map<String, PomFile> reactor = new HashMap<String, PomFile>();
        for (PomFile p : poms) {
            reactor.put(p.getGroupId() + ":" + p.getArtifactId(), p);
        }
        List<ModuleResult> out = new ArrayList<ModuleResult>();
        for (PomFile pom : poms) {
            if ("pom".equals(pom.getPackaging())) {
                continue;
            }
            cancel.check();
            DepNode tree = trees == null ? null : trees.get(pom.getGroupId() + ":" + pom.getArtifactId());
            ModuleClasspath cp = ModuleClasspath.of(locator, pom, tree, reactor);
            out.add(analyze(cp, source.usages(pom), index, reactor, cancel));
        }
        return new DependencyUsage(out);
    }

    /** Quelltext-Scanner als Quelle (Kommandozeile). */
    public static UsageSource sourceScanner(final Cancellation cancellation) {
        return new UsageSource() {
            @Override
            public ClassUsages usages(PomFile module) {
                return SourceScanner.scan(module.getFile().getParent(), cancellation);
            }
        };
    }

    /** Ein Modul mit fertigem Classpath und gesammelten Namen prüfen. */
    public static ModuleResult analyze(ModuleClasspath cp, ClassUsages usages, JarIndex index, Map<String, PomFile> reactor,
                                       Cancellation cancellation) {
        Cancellation cancel = cancellation == null ? Cancellation.NONE : cancellation;
        ModuleResult result = new ModuleResult(cp, usages);
        List<Entry> entries = cp.getEntries();
        int n = entries.size();
        JarIndex.Contents[] contents = new JarIndex.Contents[n];
        Map<String, int[]> owners = new HashMap<String, int[]>();
        for (int i = 0; i < n; i++) {
            cancel.check();
            Entry e = entries.get(i);
            contents[i] = read(e, index, reactor);
            if (contents[i] == null) {
                continue;
            }
            for (int k = 0; k < contents[i].size(); k++) {
                String name = contents[i].name(k);
                int[] o = owners.get(name);
                if (o == null) {
                    owners.put(name, new int[] {i});
                } else {
                    int[] grown = Arrays.copyOf(o, o.length + 1);
                    grown[o.length] = i;
                    owners.put(name, grown);
                }
            }
        }

        // genannte Klassen → Eintrag, aus dem der Compiler sie nimmt
        int[] mainCount = new int[n];
        int[] testCount = new int[n];
        List<List<Use>> mainUses = new ArrayList<List<Use>>();
        List<List<Use>> testUses = new ArrayList<List<Use>>();
        List<Set<String>> mainSeen = new ArrayList<Set<String>>();
        List<Set<String>> testSeen = new ArrayList<Set<String>>();
        for (int i = 0; i < n; i++) {
            mainUses.add(new ArrayList<Use>());
            testUses.add(new ArrayList<Use>());
            mainSeen.add(new LinkedHashSet<String>());
            testSeen.add(new LinkedHashSet<String>());
        }
        for (int t = 0; t < 2; t++) {
            boolean test = t == 1;
            List<Use> all = new ArrayList<Use>(usages.uses(test));
            for (ClassUsages.Wildcard w : usages.wildcards(test)) {
                for (String simple : w.simpleNames) {
                    String name = w.pkg + "." + simple;
                    if (owners.containsKey(name)) {
                        all.add(new Use(name, w.file, w.line));
                    }
                }
            }
            for (Use u : all) {
                cancel.check();
                String cls = resolve(u.getClassName(), owners);
                if (cls == null) {
                    continue;
                }
                int owner = owner(owners.get(cls), entries, test);
                if (owner >= 0 && !entries.get(owner).isDirect()) {
                    // Liegt die Klasse auch in einer deklarierten Dependency, gehört die Nennung ihr –
                    // sonst hieße die Deklaration „ungenutzt“, und ihr Entfernen hinge an einem Zufall der Reihenfolge.
                    int declared = declaredOwner(owners.get(cls), entries, test);
                    if (declared >= 0) {
                        owner = declared;
                    }
                }
                if (owner < 0 || entries.get(owner).isSelf()) {
                    continue;
                }
                Set<String> seen = (test ? testSeen : mainSeen).get(owner);
                if (!seen.add(cls)) {
                    continue;
                }
                if (test) {
                    testCount[owner]++;
                } else {
                    mainCount[owner]++;
                }
                List<Use> examples = (test ? testUses : mainUses).get(owner);
                if (examples.size() < MAX_EXAMPLES) {
                    examples.add(new Use(cls, u.getFile(), u.getLine()));
                }
            }
        }

        EffectiveModel model = cp.getModel();
        List<Finding> undeclared = new ArrayList<Finding>();
        List<Finding> testOnly = new ArrayList<Finding>();
        List<Finding> unused = new ArrayList<Finding>();

        // undeklariert: genannt, aber nur transitiv da
        for (int i = 0; i < n; i++) {
            Entry e = entries.get(i);
            if (e.isSelf() || e.isDirect() || mainCount[i] + testCount[i] == 0) {
                continue;
            }
            if (model.declared(e.getGroupId(), e.getArtifactId()) != null || e.ga().equals(cp.ga())) {
                continue;
            }
            int via = directIndex(entries, e);
            Entry viaEntry = via < 0 ? null : entries.get(via);
            boolean bundle = via >= 0 && contents[via] != null && contents[via].size() == 0;
            undeclared.add(new Finding(cp, Kind.UNDECLARED, e, null, mainUses.get(i), testUses.get(i), mainCount[i], testCount[i],
                    true, null, viaEntry, bundle));
        }

        // deklariert: genutzt, nur in Tests oder gar nicht
        boolean hasSources = usages.getMainFiles() + usages.getTestFiles() > 0;
        if (!hasSources) {
            result.notes.add(Texts.t("usage.note.no.sources"));
        }
        for (Declared d : model.getDependencies().values()) {
            PomFile.Dep dep = d.dep;
            if ("pom".equals(dep.getType()) || dep.isImport()) {
                continue;
            }
            int i = directEntry(entries, dep);
            if (i < 0) {
                continue;
            }
            Entry e = entries.get(i);
            if (contents[i] == null) {
                result.unchecked.add(e);
                continue;
            }
            String scope = e.getScope();
            if ("runtime".equals(scope) || "import".equals(scope)) {
                continue;
            }
            boolean bundle = contents[i].size() == 0;
            int usedMain = mainCount[i];
            int usedTest = testCount[i];
            List<Use> mu = new ArrayList<Use>(mainUses.get(i));
            List<Use> tu = new ArrayList<Use>(testUses.get(i));
            Set<String> services = new LinkedHashSet<String>(contents[i].getServices());
            if (bundle) {
                for (int j = 0; j < n; j++) {
                    if (j != i && !entries.get(j).isDirect() && i == directIndex(entries, entries.get(j))) {
                        usedMain += mainCount[j];
                        usedTest += testCount[j];
                        addExamples(mu, mainUses.get(j));
                        addExamples(tu, testUses.get(j));
                        if (contents[j] != null) {
                            services.addAll(contents[j].getServices());
                        }
                    }
                }
            }
            if (usedMain > 0 || !hasSources) {
                continue;
            }
            Known k = known(dep.getGroupId(), dep.getArtifactId());
            if (k != null) {
                if (usedTest == 0) {
                    result.notes.add(Texts.t("usage.note.runtime.only", e.ga(), Texts.t(k.what)));
                }
                continue;
            }
            boolean certain = services.isEmpty();
            String note = bundle ? Texts.t("usage.note.starter") : null;
            if (!certain) {
                String s = services.iterator().next();
                note = (note == null ? "" : note + "; ") + Texts.t("usage.note.services", s
                        + (services.size() > 1 ? " " + Texts.t("usage.note.services.more", services.size() - 1) : ""));
            }
            if (usedTest > 0) {
                if ("compile".equals(scope) || "provided".equals(scope) || "system".equals(scope)) {
                    if (usages.getMainFiles() > 0) {
                        testOnly.add(new Finding(cp, Kind.TEST_ONLY, e, d, mu, tu, usedMain, usedTest, certain, note, null, false));
                    }
                }
                continue;
            }
            unused.add(new Finding(cp, Kind.UNUSED, e, d, mu, tu, 0, 0, certain, note, null, false));
        }

        Collections.sort(undeclared, new java.util.Comparator<Finding>() {
            @Override
            public int compare(Finding a, Finding b) {
                if (a.viaBundle != b.viaBundle) {
                    return a.viaBundle ? 1 : -1;
                }
                if ((a.mainCount > 0) != (b.mainCount > 0)) {
                    return a.mainCount > 0 ? -1 : 1;
                }
                return (b.mainCount + b.testCount) - (a.mainCount + a.testCount);
            }
        });
        Collections.sort(unused, new java.util.Comparator<Finding>() {
            @Override
            public int compare(Finding a, Finding b) {
                return a.certain == b.certain ? 0 : a.certain ? -1 : 1;
            }
        });
        result.findings.addAll(undeclared);
        result.findings.addAll(testOnly);
        result.findings.addAll(unused);
        return result;
    }

    /**
     * Live-Prüfung einer einzelnen Nennung (IDE-Inspection): Befund {@link Kind#UNDECLARED}, wenn die
     * Klasse aus {@code e} kommt und {@code e} nur transitiv da ist; sonst {@code null}.
     */
    public static Finding undeclared(ModuleClasspath cp, Entry e, String className, boolean test, Path file, int line, JarIndex index) {
        if (e == null || e.isSelf() || e.isDirect() || e.ga().equals(cp.ga())
                || cp.getModel().declared(e.getGroupId(), e.getArtifactId()) != null) {
            return null;
        }
        if (!test && "test".equals(e.getScope())) {
            return null;
        }
        List<Entry> entries = cp.getEntries();
        int via = directIndex(entries, e);
        Entry viaEntry = via < 0 ? null : entries.get(via);
        boolean bundle = false;
        if (viaEntry != null && viaEntry.getFile() != null && index != null) {
            try {
                bundle = index.read(viaEntry.getFile()).size() == 0;
            } catch (IOException ex) {
                bundle = false;
            } catch (RuntimeException ex) {
                bundle = false;
            }
        }
        List<Use> uses = Collections.singletonList(new Use(className, file, line));
        List<Use> none = Collections.emptyList();
        return new Finding(cp, Kind.UNDECLARED, e, null, test ? none : uses, test ? uses : none, test ? 0 : 1, test ? 1 : 0, true,
                null, viaEntry, bundle);
    }

    private static void addExamples(List<Use> into, List<Use> from) {
        for (Use u : from) {
            if (into.size() >= MAX_EXAMPLES) {
                return;
            }
            into.add(u);
        }
    }

    /** Name wie gefunden → Klassenname im Classpath: innere Klassen und Member abschneiden. */
    static String resolve(String name, Map<String, int[]> owners) {
        String n = name;
        int dollar = n.indexOf('$');
        if (dollar > 0) {
            n = n.substring(0, dollar);
        }
        while (true) {
            if (owners.containsKey(n)) {
                return n;
            }
            int dot = n.lastIndexOf('.');
            if (dot <= 0) {
                return null;
            }
            n = n.substring(0, dot);
        }
    }

    /**
     * Der Eintrag, aus dem der Compiler die Klasse nimmt: für Hauptquellen der erste mit Scope
     * compile/provided/system (sonst runtime – dann kompiliert es heute gar nicht), für Tests der erste.
     */
    private static int owner(int[] candidates, List<Entry> entries, boolean test) {
        if (test) {
            return candidates[0];
        }
        int runtime = -1;
        for (int i : candidates) {
            String scope = entries.get(i).getScope();
            if (!"test".equals(scope) && !"runtime".equals(scope)) {
                return i;
            }
            if ("runtime".equals(scope) && runtime < 0) {
                runtime = i;
            }
        }
        return runtime;
    }

    /** Erste direkte Dependency unter den Kandidaten, die der Compiler hier sieht (Hauptquellen: nicht test/runtime); sonst -1. */
    private static int declaredOwner(int[] candidates, List<Entry> entries, boolean test) {
        for (int i : candidates) {
            Entry e = entries.get(i);
            if (!e.isDirect() || e.isSelf()) {
                continue;
            }
            String scope = e.getScope();
            if (test || !"test".equals(scope) && !"runtime".equals(scope)) {
                return i;
            }
        }
        return -1;
    }

    /** Index der direkten Dependency, über die {@code e} kommt; -1, wenn unbekannt. */
    private static int directIndex(List<Entry> entries, Entry e) {
        if (e.getPath().isEmpty()) {
            return -1;
        }
        String first = e.getPath().get(0);
        for (int i = 0; i < entries.size(); i++) {
            Entry d = entries.get(i);
            if (d.isDirect() && first.startsWith(d.ga() + ":")) {
                return i;
            }
        }
        return -1;
    }

    /** Classpath-Eintrag einer deklarierten Dependency (gleicher Typ und Classifier). */
    private static int directEntry(List<Entry> entries, PomFile.Dep dep) {
        String type = dep.getType();
        String classifier = classifierOf(type, dep.getClassifier());
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            String et = e.getType() == null ? "jar" : e.getType();
            if (e.isDirect() && e.ga().equals(dep.ga()) && type.equals(et) && classifier.equals(classifierOf(et, e.getClassifier()))) {
                return i;
            }
        }
        return -1;
    }

    /** Classifier, den der Typ mitbringt, wenn keiner angegeben ist ({@code test-jar} → {@code tests}). */
    private static String classifierOf(String type, String classifier) {
        if (classifier != null && !classifier.isEmpty()) {
            return classifier;
        }
        if ("test-jar".equals(type)) {
            return "tests";
        }
        return "ejb-client".equals(type) ? "client" : "";
    }

    /**
     * Klassen eines Eintrags. Reactor-Module ohne {@code target/classes} (noch nicht gebaut) werden
     * aus den Quelldateien erschlossen. {@code null}, wenn nichts lesbar ist.
     */
    private static JarIndex.Contents read(Entry e, JarIndex index, Map<String, PomFile> reactor) {
        JarIndex.Contents c = null;
        if (e.getFile() != null) {
            try {
                c = index.read(e.getFile());
            } catch (IOException ex) {
                c = null;
            } catch (RuntimeException ex) {
                c = null;
            }
        }
        if (e.isReactor() && (c == null || c.size() == 0) && reactor != null) {
            PomFile module = reactor.get(e.ga());
            if (module != null) {
                List<String> names = sourceClassNames(module.getFile().getParent(), "test-jar".equals(e.getType()));
                if (!names.isEmpty() || c == null) {
                    List<Long> crcs = new ArrayList<Long>();
                    for (int i = 0; i < names.size(); i++) {
                        crcs.add(0L);
                    }
                    c = JarIndex.Contents.of(names, crcs);
                }
            }
        }
        return c;
    }

    /** Klassennamen aus den Dateinamen unter {@code src/main/*} (bzw. {@code src/test/*}). */
    static List<String> sourceClassNames(Path moduleDir, boolean test) {
        final List<String> out = new ArrayList<String>();
        for (SourceScanner.Root r : SourceScanner.roots(moduleDir)) {
            if (r.test != test || !Files.isDirectory(r.dir) || r.dir.startsWith(moduleDir.resolve("target"))) {
                continue;
            }
            final Path root = r.dir;
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String name = file.getFileName().toString();
                        if (SourceScanner.isSource(name)) {
                            String rel = root.relativize(file).toString().replace('\\', '/');
                            out.add(rel.substring(0, rel.lastIndexOf('.')).replace('/', '.'));
                        }
                        return out.size() > 100000 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException ex) {
                // dann eben ohne
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Ausgabe

    /** Konsolenausgabe; mit {@code all} auch unsichere Befunde und Starter-Fälle ausführlich. */
    public List<String> lines(boolean all) {
        List<String> out = new ArrayList<String>();
        out.add(summary());
        out.add(Texts.t("usage.basis"));
        if (isEstimated()) {
            out.add(Texts.t("usage.estimated"));
        }
        for (ModuleResult m : modules) {
            if (m.findings.isEmpty() && m.unchecked.isEmpty() && (!all || m.notes.isEmpty())) {
                continue;
            }
            out.add("");
            out.add(Texts.t("usage.module", m.classpath.getName(), m.usages.getMainFiles(), m.usages.getTestFiles()));
            for (Finding f : m.findings) {
                if (!all && f.viaBundle) {
                    continue;
                }
                out.add("  " + pad(f.kind.label() + (f.certain ? "" : "?"), 13) + f.message());
                if (f.kind == Kind.UNDECLARED && f.entry.getPath().size() > 1) {
                    out.add("      " + Texts.t("usage.path") + " " + join(f.entry.getPath(), " → "));
                }
                List<Use> examples = new ArrayList<Use>(f.mainUses);
                examples.addAll(f.testUses);
                for (int i = 0; i < examples.size() && (all || i < 2); i++) {
                    out.add("      " + Texts.t("usage.named") + " " + examples.get(i));
                }
                if (f.note != null) {
                    out.add("      " + Texts.t("usage.note") + " " + f.note);
                }
                for (FixAction fix : f.fixes()) {
                    out.add("        → " + fix.getLabel());
                }
            }
            int bundled = 0;
            for (Finding f : m.findings) {
                if (f.viaBundle) {
                    bundled++;
                }
            }
            if (!all && bundled > 0) {
                out.add("  (" + Texts.t("usage.bundled.hidden", bundled) + ")");
            }
            if (!m.unchecked.isEmpty()) {
                List<String> l = new ArrayList<String>();
                for (Entry e : m.unchecked) {
                    l.add(e.gav());
                }
                out.add("  " + Texts.t("dup.unreadable") + " " + join(l, ", "));
            }
            if (all) {
                for (String note : m.notes) {
                    out.add("  " + Texts.t("usage.note") + " " + note);
                }
            }
        }
        if (findings().isEmpty()) {
            int unchecked = 0;
            for (ModuleResult m : modules) {
                unchecked += m.unchecked.size();
            }
            out.add("");
            out.add(unchecked == 0 ? Texts.t("usage.clean") : Texts.t("usage.clean.unchecked", unchecked));
        }
        return out;
    }

    /** Markdown-Tabelle für Ticket oder Review. */
    public String markdown(boolean all) {
        StringBuilder sb = new StringBuilder("### ").append(Texts.t("usage.title")).append("\n\n").append(summary()).append("\n\n");
        List<Finding> list = new ArrayList<Finding>();
        for (Finding f : findings()) {
            if (all || !f.viaBundle) {
                list.add(f);
            }
        }
        if (list.isEmpty()) {
            return sb.append(Texts.t("usage.no.findings")).append("\n").toString();
        }
        sb.append(Texts.t("usage.md.table")).append("\n|---|---|---|---|---|\n");
        for (Finding f : list) {
            List<Use> examples = new ArrayList<Use>(f.mainUses);
            examples.addAll(f.testUses);
            String evidence = examples.isEmpty() ? (f.note == null ? "–" : f.note) : "`" + examples.get(0).getClassName() + "`"
                    + (f.mainCount + f.testCount > 1 ? " (+" + (f.mainCount + f.testCount - 1) + ")" : "");
            List<FixAction> fixes = f.fixes();
            sb.append("| `").append(f.module.getName()).append("` | ").append(f.kind.label()).append(f.certain ? "" : " (" + Texts.t("usage.uncertain") + ")")
                    .append(" | `").append(f.entry.label()).append("` | ").append(evidence.replace("|", "\\|")).append(" | ")
                    .append(fixes.isEmpty() ? "–" : fixes.get(0).getLabel().replace("|", "\\|")).append(" |\n");
        }
        return sb.toString();
    }

    private String summary() {
        return Texts.t("usage.summary", findings(Kind.UNDECLARED).size(), findings(Kind.UNUSED).size(),
                findings(Kind.TEST_ONLY).size(), modules.size());
    }

    private static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.append(' ').toString();
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append(sb.length() == 0 ? "" : sep).append(p);
        }
        return sb.toString();
    }
}
