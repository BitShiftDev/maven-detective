package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.testkit.MavenReference;
import io.github.bitshiftdev.mavendetective.core.testkit.TestPom;
import io.github.bitshiftdev.mavendetective.core.testkit.TestRepository;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Differenztest (Testplan Welle 3): {@link EffectiveModel} muss für jedes Projekt dieselben verwalteten
 * Versionen und Scopes, dieselben (geerbten) Dependencies und dieselben interpolierten Properties liefern
 * wie Mavens Model-Builder. Bewusste Abweichung: Profile wertet {@link EffectiveModel} nicht aus – Projekte
 * mit aktiven Profilen kommen hier deshalb nicht vor (siehe {@code VersionExplainer}, der sie nur meldet).
 */
class EffectiveModelDifferentialTest {

    @TempDir
    Path tmp;

    /** Baut Projekt und Repository und gibt die POM des betrachteten Moduls zurück. */
    interface Scenario {
        Path build(Path project, TestRepository repo) throws IOException;
    }

    static Stream<Arguments> scenarios() {
        return Stream.of(
                Arguments.of("Kind gewinnt über die Parent-Kette", (Scenario) (dir, repo) -> {
                    repo.pom(TestPom.of("org.p", "grand", "1").packaging("pom")
                            .managed("org.a", "a", "1.0").managed("org.b", "b", "1.0", "<scope>runtime</scope>")
                            .property("p.only", "grand").property("p.both", "grand"));
                    repo.pom(TestPom.of("org.p", "parent", "1").packaging("pom").parent("org.p", "grand", "1")
                            .managed("org.a", "a", "2.0").property("p.both", "parent"));
                    return module(dir, TestPom.of("org.app", "app", "1.0").parent("org.p", "parent", "1")
                            .dependency("org.a", "a", null).dependency("org.b", "b", null));
                }),
                Arguments.of("Interpolation im Kontext des Kindes, auch für Einträge des Parents", (Scenario) (dir, repo) -> {
                    repo.pom(TestPom.of("org.p", "parent", "1").packaging("pom")
                            .property("x.version", "1.0").property("y.version", "${x.version}")
                            .managed("org.x", "x", "${x.version}").managed("org.y", "y", "${y.version}"));
                    return module(dir, TestPom.of("org.app", "app", "1.0").parent("org.p", "parent", "1")
                            .property("x.version", "2.0").dependency("org.x", "x", null).dependency("org.y", "y", null));
                }),
                Arguments.of("Eigenes Management schlägt BOM, erster Import gewinnt", (Scenario) (dir, repo) -> {
                    repo.pom(TestPom.of("org.bom", "one", "1").packaging("pom")
                            .managed("org.y", "y", "1.0").managed("org.z", "z", "1.0").managed("org.w", "w", "1.0"));
                    repo.pom(TestPom.of("org.bom", "two", "1").packaging("pom")
                            .managed("org.y", "y", "2.0").managed("org.v", "v", "2.0", "<scope>test</scope>"));
                    return module(dir, TestPom.of("org.app", "app", "1.0").managed("org.w", "w", "9.0")
                            .bom("org.bom", "one", "1").bom("org.bom", "two", "1")
                            .dependency("org.y", "y", null).dependency("org.v", "v", null).dependency("org.w", "w", null));
                }),
                Arguments.of("BOM im eigenen Kontext interpoliert, mit eigenem Parent", (Scenario) (dir, repo) -> {
                    repo.pom(TestPom.of("org.bom", "bom-parent", "1").packaging("pom")
                            .property("y.version", "3.0").managed("org.q", "q", "${y.version}"));
                    repo.pom(TestPom.of("org.bom", "bom", "1").packaging("pom").parent("org.bom", "bom-parent", "1")
                            .managed("org.y", "y", "${y.version}"));
                    return module(dir, TestPom.of("org.app", "app", "1.0").property("y.version", "9.0")
                            .bom("org.bom", "bom", "1").dependency("org.y", "y", null).dependency("org.q", "q", null));
                }),
                Arguments.of("Import im Parent, Version aus einer Property des Kindes", (Scenario) (dir, repo) -> {
                    repo.pom(TestPom.of("org.bom", "bom", "1").packaging("pom").managed("org.y", "y", "1.0"));
                    repo.pom(TestPom.of("org.bom", "bom", "2").packaging("pom").managed("org.y", "y", "2.0"));
                    repo.pom(TestPom.of("org.p", "parent", "1").packaging("pom").property("bom.version", "1")
                            .bom("org.bom", "bom", "${bom.version}"));
                    return module(dir, TestPom.of("org.app", "app", "1.0").parent("org.p", "parent", "1")
                            .property("bom.version", "2").dependency("org.y", "y", null));
                }),
                Arguments.of("Gleiches BOM in Parent und Kind: das des Kindes zählt", (Scenario) (dir, repo) -> {
                    repo.pom(TestPom.of("org.bom", "bom", "1").packaging("pom").managed("org.y", "y", "1.0")
                            .managed("org.only1", "only1", "1.0"));
                    repo.pom(TestPom.of("org.bom", "bom", "2").packaging("pom").managed("org.y", "y", "2.0"));
                    repo.pom(TestPom.of("org.p", "parent", "1").packaging("pom").bom("org.bom", "bom", "1"));
                    return module(dir, TestPom.of("org.app", "app", "1.0").parent("org.p", "parent", "1")
                            .bom("org.bom", "bom", "2").dependency("org.y", "y", null));
                }),
                Arguments.of("Verschachtelte BOM-Importe", (Scenario) (dir, repo) -> {
                    repo.pom(TestPom.of("org.bom", "inner", "1").packaging("pom").managed("org.y", "y", "1.0")
                            .managed("org.z", "z", "1.0"));
                    repo.pom(TestPom.of("org.bom", "outer", "1").packaging("pom").managed("org.z", "z", "2.0")
                            .bom("org.bom", "inner", "1"));
                    return module(dir, TestPom.of("org.app", "app", "1.0").bom("org.bom", "outer", "1")
                            .dependency("org.y", "y", null).dependency("org.z", "z", null));
                }),
                Arguments.of("Type und Classifier sind Teil des Schlüssels", (Scenario) (dir, repo) -> {
                    return module(dir, TestPom.of("org.app", "app", "1.0")
                            .managed("org.z", "z", "1.0")
                            .managed("org.z", "z", "2.0", "<classifier>tests</classifier>")
                            .managed("org.z", "z", "3.0", "<type>test-jar</type>", "<scope>test</scope>")
                            .managed("org.z", "z", "4.0", "<type>pom</type>")
                            .dependency("org.z", "z", null)
                            .dependency("org.z", "z", null, "<classifier>tests</classifier>")
                            .dependency("org.z", "z", null, "<type>test-jar</type>"));
                }),
                Arguments.of("Eingebaute Ausdrücke project.* und parent.*", (Scenario) (dir, repo) -> {
                    repo.pom(TestPom.of("org.p", "parent", "7").packaging("pom")
                            .managed("org.s", "sibling", "${project.version}"));
                    return module(dir, TestPom.of(null, "app", null).parent("org.p", "parent", "7")
                            .property("v1", "${project.version}").property("v2", "${project.parent.version}")
                            .property("v3", "${project.groupId}").property("v4", "${pom.version}")
                            .property("v5", "${project.artifactId}").property("v6", "${version}")
                            .dependency("org.s", "sibling", null)
                            .dependency("org.t", "t", "${project.parent.version}"));
                }),
                Arguments.of("Direkte Version schlägt Management, Scope aus dem Management", (Scenario) (dir, repo) -> {
                    return module(dir, TestPom.of("org.app", "app", "1.0")
                            .managed("org.a", "a", "1.0", "<scope>provided</scope>")
                            .managed("org.b", "b", "1.0", "<scope>test</scope>")
                            .dependency("org.a", "a", "2.0")
                            .dependency("org.b", "b", null, "<scope>runtime</scope>"));
                }),
                Arguments.of("Geerbte Dependencies, im Kind neu deklariert", (Scenario) (dir, repo) -> {
                    repo.pom(TestPom.of("org.p", "parent", "1").packaging("pom")
                            .dependency("org.a", "a", "1.0").dependency("org.b", "b", "1.0", "<scope>test</scope>"));
                    return module(dir, TestPom.of("org.app", "app", "1.0").parent("org.p", "parent", "1")
                            .dependency("org.c", "c", "1.0").dependency("org.a", "a", "2.0"));
                }),
                Arguments.of("Property-Ketten über drei Ebenen", (Scenario) (dir, repo) -> {
                    repo.pom(TestPom.of("org.p", "parent", "1").packaging("pom").property("base", "1")
                            .property("minor", "${base}.2").property("full", "${minor}.3"));
                    return module(dir, TestPom.of("org.app", "app", "1.0").parent("org.p", "parent", "1")
                            .property("base", "5").dependency("org.a", "a", "${full}"));
                }),
                Arguments.of("Ausdrücke in groupId, artifactId, type und classifier", (Scenario) (dir, repo) -> {
                    return module(dir, TestPom.of("org.app", "app", "1.0").property("kind", "tests")
                            .property("art", "core")
                            .managed("${project.groupId}", "core", "${project.version}")
                            .managed("org.app", "${art}", "2.0", "<classifier>${kind}</classifier>")
                            .dependency("${project.groupId}", "core", null)
                            .dependency("org.app", "core", null, "<classifier>tests</classifier>"));
                }),
                Arguments.of("project.parent.artifactId und project.parent.groupId", (Scenario) (dir, repo) -> {
                    repo.pom(TestPom.of("org.p", "parent", "1").packaging("pom"));
                    return module(dir, TestPom.of("org.app", "app", "1.0").parent("org.p", "parent", "1")
                            .property("pa", "${project.parent.artifactId}").property("pg", "${project.parent.groupId}")
                            .property("pv", "${parent.version}")
                            .dependency("org.a", "${project.parent.artifactId}-api", "1.0"));
                }),
                Arguments.of("Property des Parents nutzt die Version des Kindes", (Scenario) (dir, repo) -> {
                    repo.pom(TestPom.of("org.p", "parent", "1").packaging("pom")
                            .property("self", "${project.artifactId}-${project.version}")
                            .managed("org.app", "lib", "${project.version}"));
                    return module(dir, TestPom.of("org.app", "app", "5.0").parent("org.p", "parent", "1")
                            .dependency("org.app", "lib", null));
                }),
                Arguments.of("BOM-Import über einen Parent, der selbst importiert", (Scenario) (dir, repo) -> {
                    repo.pom(TestPom.of("org.bom", "base", "1").packaging("pom").managed("org.y", "y", "1.0")
                            .managed("org.z", "z", "1.0"));
                    repo.pom(TestPom.of("org.bom", "bom-parent", "1").packaging("pom").bom("org.bom", "base", "1"));
                    repo.pom(TestPom.of("org.bom", "bom", "1").packaging("pom").parent("org.bom", "bom-parent", "1")
                            .managed("org.z", "z", "2.0"));
                    return module(dir, TestPom.of("org.app", "app", "1.0").bom("org.bom", "bom", "1")
                            .dependency("org.y", "y", null).dependency("org.z", "z", null));
                }),
                Arguments.of("Doppelte Deklaration in einer POM: die letzte zählt", (Scenario) (dir, repo) -> {
                    repo.pom(TestPom.of("org.p", "parent", "1").packaging("pom").dependency("org.b", "b", "1.0")
                            .managed("org.m", "m", "1.0"));
                    return module(dir, TestPom.of("org.app", "app", "1.0").parent("org.p", "parent", "1")
                            .managed("org.m", "m", "2.0").managed("org.m", "m", "3.0", "<scope>runtime</scope>")
                            .dependency("org.a", "a", "1.0", "<scope>runtime</scope>")
                            .dependency("org.c", "c", "1.0")
                            .dependency("org.a", "a", "2.0")
                            .dependency("org.b", "b", "2.0").dependency("org.b", "b", "3.0")
                            .dependency("org.m", "m", null));
                }),
                Arguments.of("Doppelter Management-Eintrag mit BOM-Import: der letzte zählt", (Scenario) (dir, repo) -> {
                    repo.pom(TestPom.of("org.bom", "bom", "1").packaging("pom").managed("org.x", "x", "1.0"));
                    return module(dir, TestPom.of("org.app", "app", "1.0").bom("org.bom", "bom", "1")
                            .managed("org.m", "m", "2.0", "<scope>runtime</scope>").managed("org.m", "m", "3.0")
                            .dependency("org.m", "m", null));
                }),
                Arguments.of("Doppelter Management-Eintrag im Parent", (Scenario) (dir, repo) -> {
                    repo.pom(TestPom.of("org.p", "parent", "1").packaging("pom")
                            .managed("org.m", "m", "2.0").managed("org.m", "m", "3.0", "<scope>runtime</scope>")
                            .managed("org.n", "n", "1.0", "<scope>test</scope>").managed("org.n", "n", "2.0"));
                    return module(dir, TestPom.of("org.app", "app", "1.0").parent("org.p", "parent", "1")
                            .dependency("org.m", "m", null).dependency("org.n", "n", null));
                }),
                Arguments.of("Parent über relativePath im Projekt", (Scenario) (dir, repo) -> {
                    write(dir.resolve("pom.xml"), TestPom.of("org.app", "root", "3.0").packaging("pom").module("app")
                            .property("lib.version", "1.1").managed("org.lib", "lib", "${lib.version}").xml());
                    return module(dir, TestPom.of(null, "app", null).parent("org.app", "root", "3.0")
                            .dependency("org.lib", "lib", null).dependency("org.app", "other", "${project.version}"));
                })
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void wieMavensModelBuilder(String name, Scenario scenario) throws IOException {
        TestRepository repo = TestRepository.at(tmp.resolve("repo"));
        Path pom = scenario.build(tmp.resolve("project"), repo);

        Model maven = MavenReference.effectiveModel(pom, repo.root());
        EffectiveModel ours = new PomLocator(tmp.resolve("project"), repo.root()).model(read(pom));

        assertEquals(describe(maven), describe(ours), name);
    }

    /** Alle POM-Formen aus {@code resources/poms/} (Parent und BOMs als leere POMs im Repository). */
    @ParameterizedTest(name = "{0}")
    @MethodSource("pomShapes")
    void pomFormenWieMaven(String file) throws Exception {
        TestRepository repo = TestRepository.at(tmp.resolve("repo"));
        Path pom = tmp.resolve("project/pom.xml");
        Files.createDirectories(pom.getParent());
        Files.copy(Path.of("src/test/resources/poms", file), pom);
        PomFile parsed = PomFile.read(pom);
        if (parsed.hasParent()) {
            // Parent als Stellvertreter: verwaltet, was das Modul ohne Version deklariert (wie spring-boot-dependencies)
            TestPom parent = TestPom.of(parsed.getParentGroupId(), parsed.getParentArtifactId(), parsed.getParentVersion())
                    .packaging("pom");
            for (PomFile.Dep d : parsed.getDependencies()) {
                if (d.getVersion() == null) {
                    parent.managed(d.getGroupId(), d.getArtifactId(), "1.0-from-parent");
                }
            }
            repo.pom(parent);
        }
        PomLocator locator = new PomLocator(pom.getParent(), repo.root());
        for (PomFile.Dep d : parsed.getManagedDependencies()) {
            if (d.isImport()) {
                String v = locator.model(parsed).interpolate(d.getVersion()).value;
                repo.pom(TestPom.of(d.getGroupId(), d.getArtifactId(), v).packaging("pom"));
            }
        }

        Model maven = MavenReference.effectiveModel(pom, repo.root());
        EffectiveModel ours = new PomLocator(pom.getParent(), repo.root()).model(PomFile.read(pom));

        assertEquals(describe(maven), describe(ours), file);
    }

    static Stream<String> pomShapes() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/test/resources/poms"))) {
            List<String> names = new ArrayList<>();
            files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".xml"))
                    // profiles.xml: Profil per activeByDefault – EffectiveModel wertet Profile bewusst nicht aus
                    .filter(n -> !PROFILE_SHAPES.contains(n)).sorted().forEach(names::add);
            return names.stream();
        }
    }

    /** Formen mit aktiven Profilen: bewusste Abweichung, siehe Klassenkommentar. */
    static final Set<String> PROFILE_SHAPES = Set.of("profiles.xml");

    // ---- Beschreibung beider Modelle in derselben Form ----

    static String describe(Model m) {
        // doppelte Einträge (bleiben im Modell stehen) so, wie Maven sie anwendet: je Attribut der erste Wert
        Map<String, String[]> first = new TreeMap<>();
        if (m.getDependencyManagement() != null) {
            for (Dependency d : m.getDependencyManagement().getDependencies()) {
                String[] vs = first.computeIfAbsent(key(d.getGroupId(), d.getArtifactId(), d.getType(), d.getClassifier()),
                        k -> new String[2]);
                vs[0] = vs[0] != null ? vs[0] : d.getVersion();
                vs[1] = vs[1] != null ? vs[1] : d.getScope();
            }
        }
        Map<String, String> managed = new TreeMap<>();
        first.forEach((k, vs) -> managed.put(k, vs[0] + " | " + scope(vs[1])));
        List<String> deps = new ArrayList<>();
        for (Dependency d : m.getDependencies()) {
            deps.add(key(d.getGroupId(), d.getArtifactId(), d.getType(), d.getClassifier()) + " = " + d.getVersion()
                    + " | " + scope(d.getScope()));
        }
        Map<String, String> props = new TreeMap<>();
        m.getProperties().forEach((k, v) -> props.put((String) k, (String) v));
        return render(managed, deps, props);
    }

    static String describe(EffectiveModel m) {
        Map<String, String> managed = new TreeMap<>();
        for (EffectiveModel.Managed e : m.getManaged().values()) {
            PomFile.Dep d = e.dep;
            managed.put(key(d.getGroupId(), d.getArtifactId(), d.getType(), d.getClassifier()),
                    e.version().value + " | " + scope(d.getScope()));
        }
        List<String> deps = new ArrayList<>();
        for (EffectiveModel.Declared e : m.getDependencies().values()) {
            PomFile.Dep d = e.dep;
            EffectiveModel.Managed mg = m.managed(d);
            String scope = d.getScope() != null ? d.getScope() : mg != null ? mg.dep.getScope() : null;
            deps.add(key(d.getGroupId(), d.getArtifactId(), d.getType(), d.getClassifier()) + " = "
                    + ExclusionAudit.dependencyVersion(m, d) + " | " + scope(scope));
        }
        Set<String> names = new TreeSet<>();
        for (PomFile p : m.getChain()) {
            names.addAll(p.getProperties().keySet());
        }
        Map<String, String> props = new TreeMap<>();
        for (String n : names) {
            props.put(n, m.interpolate("${" + n + "}").value);
        }
        return render(managed, deps, props);
    }

    private static String render(Map<String, String> managed, List<String> deps, Map<String, String> props) {
        StringBuilder sb = new StringBuilder();
        managed.forEach((k, v) -> sb.append("managed ").append(k).append(" = ").append(v).append('\n'));
        deps.forEach(d -> sb.append("dep ").append(d).append('\n'));
        props.forEach((k, v) -> sb.append("prop ").append(k).append(" = ").append(v).append('\n'));
        return sb.toString();
    }

    private static String key(String g, String a, String type, String classifier) {
        String t = type == null || type.isEmpty() ? "jar" : type;
        return g + ":" + a + ":" + t + (classifier == null || classifier.isEmpty() ? "" : ":" + classifier);
    }

    private static String scope(String s) {
        return s == null || s.isEmpty() ? "compile" : s;
    }

    static Path module(Path dir, TestPom pom) {
        Path file = dir.resolve(pom.artifactId()).resolve("pom.xml");
        write(file, pom.xml());
        return file;
    }

    static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static PomFile read(Path pom) {
        try {
            return PomFile.read(pom);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
