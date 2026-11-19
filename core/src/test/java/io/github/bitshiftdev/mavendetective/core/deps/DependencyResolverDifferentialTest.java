package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.testkit.MavenReference;
import io.github.bitshiftdev.mavendetective.core.testkit.TestPom;
import io.github.bitshiftdev.mavendetective.core.testkit.TestRepository;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static io.github.bitshiftdev.mavendetective.core.deps.EffectiveModelDifferentialTest.module;
import static io.github.bitshiftdev.mavendetective.core.deps.EffectiveModelDifferentialTest.read;
import static io.github.bitshiftdev.mavendetective.core.testkit.TestPom.exclusions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differenztest (Testplan Welle 3): Der Baum von {@link DependencyResolver} (nur die Gewinner) muss Knoten für
 * Knoten dem Baum entsprechen, den Maven Resolver mit den Einstellungen von Maven 3.9 sammelt – Versionen,
 * Scopes, Reihenfolge und Pfad.
 *
 * <p>Bewusste Abweichung bei Zyklen zwischen Artifacts ({@code a → b → a} in anderer Version): Mavens
 * {@code ConflictResolver} arbeitet die Artifacts dann in beliebiger Reihenfolge ab und zählt Scopes aus Teilbäumen
 * mit, die später verlieren. {@link DependencyResolver} verfolgt Verlierer nie; ohne Zyklus (topologische
 * Reihenfolge, Verlierer sind dann schon entschieden) ist das dasselbe. Die Zufallsgraphen sind deshalb zyklenfrei,
 * Zyklen prüft ein fester Fall.
 */
class DependencyResolverDifferentialTest {

    @TempDir
    Path tmp;

    interface Scenario {
        Path build(Path project, TestRepository repo) throws IOException;
    }

    static final String[] SCOPES = {"compile", "provided", "runtime", "test"};

    static Stream<Arguments> scenarios() {
        List<Arguments> out = new ArrayList<>();
        out.add(Arguments.of("nearest wins, bei gleicher Tiefe die erste Deklaration", (Scenario) (dir, repo) -> {
            repo.pom(TestPom.of("org.a", "a", "1").dependency("org.c", "c", "1.0"));
            repo.pom(TestPom.of("org.b", "b", "1").dependency("org.c", "c", "2.0").dependency("org.x", "x", "1"));
            repo.pom(TestPom.of("org.x", "x", "1").dependency("org.c", "c", "3.0").dependency("org.d", "d", "1"));
            repo.pom(TestPom.of("org.c", "c", "1.0").dependency("org.d", "d", "2"));
            repo.pom(TestPom.of("org.c", "c", "2.0").dependency("org.e", "e", "1"));
            repo.pom("org.c", "c", "3.0");
            repo.pom("org.d", "d", "1");
            repo.pom("org.d", "d", "2");
            repo.pom("org.e", "e", "1");
            return module(dir, TestPom.of("org.app", "app", "1").dependency("org.b", "b", "1").dependency("org.a", "a", "1"));
        }));
        // Scope-Matrix: direkter Scope × Scope in der POM der Dependency
        for (String direct : SCOPES) {
            for (String transitive : SCOPES) {
                out.add(Arguments.of("Scope " + direct + " × " + transitive, (Scenario) (dir, repo) -> {
                    repo.pom(TestPom.of("org.a", "a", "1").dependency("org.t", "t", "1", "<scope>" + transitive + "</scope>"));
                    repo.pom(TestPom.of("org.t", "t", "1").dependency("org.u", "u", "1"));
                    repo.pom("org.u", "u", "1");
                    return module(dir, TestPom.of("org.app", "app", "1").dependency("org.a", "a", "1", "<scope>" + direct + "</scope>"));
                }));
            }
        }
        out.add(Arguments.of("Weitester Scope gewinnt, direkter Scope bleibt", (Scenario) (dir, repo) -> {
            repo.pom(TestPom.of("org.a", "a", "1").dependency("org.s", "s", "1"));
            repo.pom(TestPom.of("org.b", "b", "1").dependency("org.s", "s", "1", "<scope>runtime</scope>")
                    .dependency("org.d", "d", "1"));
            repo.pom(TestPom.of("org.c", "c", "1").dependency("org.s", "s", "1").dependency("org.d", "d", "1"));
            repo.pom("org.s", "s", "1");
            repo.pom("org.d", "d", "1");
            return module(dir, TestPom.of("org.app", "app", "1")
                    .dependency("org.a", "a", "1", "<scope>test</scope>")
                    .dependency("org.b", "b", "1", "<scope>runtime</scope>")
                    .dependency("org.c", "c", "1", "<scope>provided</scope>")
                    .dependency("org.d", "d", "1", "<scope>test</scope>"));
        }));
        out.add(Arguments.of("optional: direkt ja, transitiv nein", (Scenario) (dir, repo) -> {
            repo.pom(TestPom.of("org.a", "a", "1").dependency("org.o", "o", "1", "<optional>true</optional>")
                    .dependency("org.p", "p", "1"));
            repo.pom(TestPom.of("org.o", "o", "1").dependency("org.q", "q", "1"));
            repo.pom("org.p", "p", "1");
            repo.pom("org.q", "q", "1");
            return module(dir, TestPom.of("org.app", "app", "1").dependency("org.a", "a", "1")
                    .dependency("org.o", "o", "1", "<optional>true</optional>"));
        }));
        out.add(Arguments.of("Exclusions mit Platzhaltern gelten für den ganzen Teilbaum", (Scenario) (dir, repo) -> {
            repo.pom(TestPom.of("org.a", "a", "1").dependency("org.b", "b", "1").dependency("org.x", "x1", "1"));
            repo.pom(TestPom.of("org.b", "b", "1").dependency("org.x", "x2", "1").dependency("org.y", "y", "1"));
            repo.pom(TestPom.of("org.c", "c", "1").dependency("org.y", "y", "1").dependency("org.z", "z", "1"));
            repo.pom(TestPom.of("org.d", "d", "1").dependency("org.z", "z", "1"));
            repo.pom("org.x", "x1", "1");
            repo.pom("org.x", "x2", "1");
            repo.pom("org.y", "y", "1");
            repo.pom("org.z", "z", "1");
            return module(dir, TestPom.of("org.app", "app", "1")
                    .dependency("org.a", "a", "1", exclusions("org.x:*"))
                    .dependency("org.c", "c", "1", exclusions("*:y"))
                    .dependency("org.d", "d", "1", exclusions("*:*")));
        }));
        out.add(Arguments.of("Exclusion in der POM der Dependency", (Scenario) (dir, repo) -> {
            repo.pom(TestPom.of("org.a", "a", "1").dependency("org.b", "b", "1", exclusions("org.c:c")));
            repo.pom(TestPom.of("org.b", "b", "1").dependency("org.c", "c", "1").dependency("org.d", "d", "1"));
            repo.pom("org.c", "c", "1");
            repo.pom("org.d", "d", "1");
            return module(dir, TestPom.of("org.app", "app", "1").dependency("org.a", "a", "1"));
        }));
        out.add(Arguments.of("Zyklus zwischen Dependencies und zurück zum Modul", (Scenario) (dir, repo) -> {
            repo.pom(TestPom.of("org.a", "a", "1").dependency("org.b", "b", "1"));
            repo.pom(TestPom.of("org.b", "b", "1").dependency("org.a", "a", "1").dependency("org.app", "app", "1")
                    .dependency("org.c", "c", "1"));
            repo.pom("org.c", "c", "1");
            return module(dir, TestPom.of("org.app", "app", "1").dependency("org.a", "a", "1"));
        }));
        out.add(Arguments.of("Management des Moduls für transitive Version, Scope und Exclusions", (Scenario) (dir, repo) -> {
            repo.pom(TestPom.of("org.a", "a", "1").dependency("org.c", "c", "1.0").dependency("org.r", "r", "1")
                    .dependency("org.s", "s", "1"));
            repo.pom(TestPom.of("org.c", "c", "1.0").dependency("org.d", "d", "1"));
            repo.pom(TestPom.of("org.c", "c", "2.0").dependency("org.d", "d", "1").dependency("org.e", "e", "1"));
            repo.pom(TestPom.of("org.s", "s", "1").dependency("org.f", "f", "1"));
            repo.pom("org.d", "d", "1");
            repo.pom("org.e", "e", "1");
            repo.pom("org.f", "f", "1");
            repo.pom("org.r", "r", "1");
            return module(dir, TestPom.of("org.app", "app", "1")
                    .managed("org.c", "c", "2.0", exclusions("org.d:d"))
                    .managed("org.r", "r", "1", "<scope>test</scope>")
                    .managed("org.s", "s", "1", "<scope>runtime</scope>")
                    .dependency("org.a", "a", "1"));
        }));
        out.add(Arguments.of("Exclusions direkter Dependencies und aus dem Management", (Scenario) (dir, repo) -> {
            repo.pom(TestPom.of("org.a", "a", "1").dependency("org.x", "x", "1").dependency("org.y", "y", "1"));
            repo.pom(TestPom.of("org.b", "b", "1").dependency("org.x", "x", "1").dependency("org.y", "y", "1"));
            repo.pom("org.x", "x", "1");
            repo.pom("org.y", "y", "1");
            return module(dir, TestPom.of("org.app", "app", "1")
                    .managed("org.a", "a", "1", exclusions("org.x:x"))
                    .managed("org.b", "b", "1", exclusions("org.x:x"))
                    .dependency("org.a", "a", null, exclusions("org.y:y"))
                    .dependency("org.b", "b", null));
        }));
        out.add(Arguments.of("Management in der POM der Dependency (Version, Scope test, Exclusions)", (Scenario) (dir, repo) -> {
            repo.pom(TestPom.of("org.a", "a", "1")
                    .managed("org.c", "c", "2.0", exclusions("org.d:d"))
                    .managed("org.t", "t", "1", "<scope>test</scope>")
                    .dependency("org.c", "c", null).dependency("org.t", "t", null));
            repo.pom(TestPom.of("org.c", "c", "2.0").dependency("org.d", "d", "1").dependency("org.e", "e", "1"));
            repo.pom("org.d", "d", "1");
            repo.pom("org.e", "e", "1");
            repo.pom("org.t", "t", "1");
            return module(dir, TestPom.of("org.app", "app", "1").dependency("org.a", "a", "1"));
        }));
        out.add(Arguments.of("Dependency-POM mit Parent, Property und BOM", (Scenario) (dir, repo) -> {
            repo.pom(TestPom.of("org.bom", "bom", "1").packaging("pom").managed("org.m", "m", "4.0"));
            repo.pom(TestPom.of("org.lib", "lib-parent", "1").packaging("pom").property("d.version", "2")
                    .dependency("org.inh", "inherited", "1").bom("org.bom", "bom", "1"));
            repo.pom(TestPom.of("org.lib", "lib", "1").parent("org.lib", "lib-parent", "1")
                    .dependency("org.d", "d", "${d.version}").dependency("org.m", "m", null)
                    .dependency("${project.groupId}", "lib-core", "${project.version}"));
            repo.pom("org.inh", "inherited", "1");
            repo.pom("org.d", "d", "2");
            repo.pom("org.m", "m", "4.0");
            repo.pom("org.lib", "lib-core", "1");
            return module(dir, TestPom.of("org.app", "app", "1").dependency("org.lib", "lib", "1"));
        }));
        out.add(Arguments.of("Konflikt tiefer im Baum, Teilbaum des Verlierers fällt weg", (Scenario) (dir, repo) -> {
            repo.pom(TestPom.of("org.a", "a", "1").dependency("org.b", "b", "1"));
            repo.pom(TestPom.of("org.b", "b", "1").dependency("org.c", "c", "1"));
            repo.pom(TestPom.of("org.c", "c", "1").dependency("org.only1", "only1", "1"));
            repo.pom(TestPom.of("org.x", "x", "1").dependency("org.c", "c", "2"));
            repo.pom(TestPom.of("org.c", "c", "2").dependency("org.only2", "only2", "1"));
            repo.pom("org.only1", "only1", "1");
            repo.pom("org.only2", "only2", "1");
            return module(dir, TestPom.of("org.app", "app", "1").dependency("org.a", "a", "1").dependency("org.x", "x", "1"));
        }));
        out.add(Arguments.of("Classifier und test-jar sind eigene Artefakte", (Scenario) (dir, repo) -> {
            repo.pom(TestPom.of("org.a", "a", "1").dependency("org.c", "c", "1", "<classifier>linux</classifier>")
                    .dependency("org.c", "c", "2"));
            repo.pom("org.c", "c", "1");
            repo.pom("org.c", "c", "2");
            return module(dir, TestPom.of("org.app", "app", "1").dependency("org.a", "a", "1")
                    .dependency("org.c", "c", "1", "<type>test-jar</type>", "<scope>test</scope>"));
        }));
        out.add(Arguments.of("system-Scope bleibt transitiv", (Scenario) (dir, repo) -> {
            repo.pom(TestPom.of("org.a", "a", "1").dependency("org.sys", "sys", "1", "<scope>system</scope>",
                    "<systemPath>" + dir.resolve("sys.jar").toAbsolutePath() + "</systemPath>").dependency("org.b", "b", "1"));
            repo.pom(TestPom.of("org.b", "b", "1").dependency("org.sys", "sys", "1"));
            repo.pom("org.sys", "sys", "1");
            return module(dir, TestPom.of("org.app", "app", "1").dependency("org.a", "a", "1", "<scope>runtime</scope>"));
        }));
        out.add(Arguments.of("Abhängigkeit vom Typ pom", (Scenario) (dir, repo) -> {
            repo.pom(TestPom.of("org.g", "group", "1").packaging("pom").dependency("org.x", "x", "1"));
            repo.pom("org.x", "x", "1");
            return module(dir, TestPom.of("org.app", "app", "1").dependency("org.g", "group", "1", "<type>pom</type>"));
        }));
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void wieMavenResolver(String name, Scenario scenario) throws IOException {
        TestRepository repo = TestRepository.at(tmp.resolve("repo"));
        Path pom = scenario.build(tmp.resolve("project"), repo);

        List<String> maven = MavenReference.tree(pom, repo.root());
        DependencyResolver.Tree ours = DependencyResolver.resolve(new PomLocator(tmp.resolve("project"), repo.root()), read(pom));

        assertTrue(ours.getMissing().isEmpty(), ours.getMissing().toString());
        assertEquals(String.join("\n", withoutModuleItself(maven, ours.getRoot())), String.join("\n", included(ours.getRoot())), name);
    }

    /**
     * Zufällige Graphen mit festem Startwert: 12 Bibliotheken in je drei Versionen, Dependencies mit Scopes,
     * optional, Exclusions, eigenem Management und Zyklen; das Modul mit Management für Version und Scope.
     */
    @ParameterizedTest(name = "Startwert {0}")
    @MethodSource("seeds")
    void zufaelligeGraphenWieMavenResolver(int seed) throws IOException {
        Path base = tmp.resolve("s" + seed);
        RandomProject p = RandomProject.generate(seed, base);
        Path pom = p.pom;
        TestRepository repo = p.repo;

        List<String> maven = MavenReference.tree(pom, repo.root());
        DependencyResolver.Tree ours = DependencyResolver.resolve(new PomLocator(base.resolve("project"), repo.root()), read(pom));

        assertEquals(String.join("\n", maven), String.join("\n", included(ours.getRoot())), () -> p.module.xml() + p.described
                + "\n\nMaven -Dverbose:\n" + String.join("\n", MavenReference.tree(pom, repo.root(), true))
                + "\n\nDependencyResolver:\n" + verbose(ours.getRoot()));
    }

    /** Zufälliges Projekt mit Repository; {@code described} beschreibt die Bibliotheken kompakt. */
    static final class RandomProject {
        Path pom;
        TestRepository repo;
        TestPom module;
        String described;

        static RandomProject generate(int seed, Path base) {
            java.util.Random random = new java.util.Random(seed);
            TestRepository repo = TestRepository.at(base.resolve("repo"));
            String[] scopes = {null, null, null, "compile", "runtime", "test", "provided"};
            StringBuilder described = new StringBuilder();
            for (int lib = 0; lib < 12; lib++) {
                for (int v = 1; v <= 3; v++) {
                    TestPom pom = TestPom.of("org.l" + lib, "l" + lib, String.valueOf(v));
                    described.append("\nl").append(lib).append(':').append(v).append(" →");
                    int deps = random.nextInt(4);
                    for (int i = 0; i < deps; i++) {
                        // nur auf Bibliotheken mit höherer Nummer: keine Zyklen zwischen Artifacts (siehe Klassenkommentar)
                        if (lib == 11) {
                            break;
                        }
                        int target = lib + 1 + random.nextInt(11 - lib);
                        List<String> extra = new ArrayList<>();
                        String scope = scopes[random.nextInt(scopes.length)];
                        if (scope != null) {
                            extra.add("<scope>" + scope + "</scope>");
                        }
                        if (random.nextInt(10) == 0) {
                            extra.add("<optional>true</optional>");
                        }
                        if (random.nextInt(6) == 0) {
                            int ex = random.nextInt(12);
                            extra.add(exclusions(random.nextBoolean() ? "org.l" + ex + ":l" + ex : "org.l" + ex + ":*"));
                        }
                        described.append(" l").append(target).append(String.join("", extra).replaceAll("</?(scope|optional|exclusions|exclusion|groupId|artifactId)>", " "));
                        if (random.nextInt(8) == 0) {
                            described.append("(managed)");
                            pom.managed("org.l" + target, "l" + target, String.valueOf(1 + random.nextInt(3)));
                            pom.dependency("org.l" + target, "l" + target, null, extra.toArray(new String[0]));
                        } else {
                            pom.dependency("org.l" + target, "l" + target, String.valueOf(1 + random.nextInt(3)),
                                    extra.toArray(new String[0]));
                        }
                    }
                    repo.pom(pom);
                }
            }
            TestPom module = TestPom.of("org.app", "app", "1");
            int direct = 1 + random.nextInt(4);
            for (int i = 0; i < direct; i++) {
                int target = random.nextInt(12);
                String scope = scopes[random.nextInt(scopes.length)];
                module.dependency("org.l" + target, "l" + target, String.valueOf(1 + random.nextInt(3)),
                        scope == null ? new String[0] : new String[] {"<scope>" + scope + "</scope>"});
            }
            int managed = random.nextInt(3);
            for (int i = 0; i < managed; i++) {
                int target = random.nextInt(12);
                String scope = scopes[random.nextInt(scopes.length)];
                module.managed("org.l" + target, "l" + target, String.valueOf(1 + random.nextInt(3)),
                        scope == null || "test".equals(scope) && random.nextBoolean() ? new String[0] : new String[] {"<scope>" + scope + "</scope>"});
            }
            RandomProject out = new RandomProject();
            out.pom = module(base.resolve("project"), module);
            out.repo = repo;
            out.module = module;
            out.described = described.toString();
            return out;
        }
    }

    /**
     * 300 Graphen; unter Windows 50 – dort kosten die rund 36 POMs je Graph im {@code @TempDir} Minuten, und der
     * Windows-Job prüft Pfade und Zeilenenden, nicht die Algorithmen (die laufen unter Linux und macOS voll).
     */
    static Stream<Integer> seeds() {
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        return java.util.stream.IntStream.range(0, windows ? 50 : 300).boxed();
    }

    /**
     * Bewusste Abweichung: Führt ein Pfad zurück zum Modul selbst, behält Maven Resolver ihn als eigenen Knoten
     * (die Wurzel nimmt an der Konfliktauflösung nicht teil). {@link DependencyResolver} zeigt ihn als
     * {@link DepNode.State#CYCLE}, damit die Analysen das Modul nicht als eigene Dependency (doppelte Klassen,
     * Klassenpfad) zählen – seine Klassen liegen ohnehin in {@code target/classes}.
     */
    static List<String> withoutModuleItself(List<String> maven, DepNode root) {
        List<String> out = new ArrayList<>();
        int skipDeeperThan = -1;
        for (String line : maven) {
            int depth = (line.length() - line.stripLeading().length()) / 2;
            if (skipDeeperThan >= 0 && depth > skipDeeperThan) {
                continue;
            }
            skipDeeperThan = -1;
            if (line.stripLeading().startsWith(root.getGroupId() + ":" + root.getArtifactId() + ":")) {
                skipDeeperThan = depth;
                continue;
            }
            out.add(line);
        }
        return out;
    }

    /** Der ganze Baum mit Verlierern, zum Untersuchen einer Abweichung. */
    static String verbose(DepNode root) {
        StringBuilder sb = new StringBuilder();
        java.util.function.BiConsumer<DepNode, Integer>[] walk = new java.util.function.BiConsumer[1];
        walk[0] = (n, depth) -> {
            sb.append("  ".repeat(depth)).append(n.gav()).append(':').append(n.getScope()).append(' ').append(n.getState())
                    .append(n.getPremanagedVersion() != null || n.getPremanagedScope() != null
                            ? " [managed from " + n.getPremanagedVersion() + "/" + n.getPremanagedScope() + "]" : "")
                    .append('\n');
            n.getChildren().forEach(c -> walk[0].accept(c, depth + 1));
        };
        root.getChildren().forEach(c -> walk[0].accept(c, 0));
        return sb.toString();
    }

    /** Gewinner in Vorordnung, in der Form von {@link MavenReference#tree}. */
    static List<String> included(DepNode root) {
        List<String> out = new ArrayList<>();
        for (DepNode c : root.getChildren()) {
            print(c, 0, out);
        }
        return out;
    }

    private static void print(DepNode n, int depth, List<String> out) {
        if (n.getState() != DepNode.State.INCLUDED) {
            return;
        }
        String[] ext = extension(n.getType());
        String classifier = n.getClassifier() != null ? n.getClassifier() : ext[1];
        out.add("  ".repeat(depth) + n.getGroupId() + ":" + n.getArtifactId() + ":" + ext[0]
                + (classifier == null || classifier.isEmpty() ? "" : ":" + classifier) + ":" + n.getVersion() + ":" + n.getScope());
        for (DepNode c : n.getChildren()) {
            print(c, depth + 1, out);
        }
    }

    /** Endung und Classifier eines Typs wie Mavens {@code ArtifactTypeRegistry}. */
    private static String[] extension(String type) {
        switch (type) {
            case "test-jar": return new String[] {"jar", "tests"};
            case "ejb-client": return new String[] {"jar", "client"};
            case "java-source": return new String[] {"jar", "sources"};
            case "javadoc": return new String[] {"jar", "javadoc"};
            case "maven-plugin": case "ejb": case "bundle": return new String[] {"jar", null};
            default: return new String[] {type, null};
        }
    }
}
