package io.github.bitshiftdev.mavendetective.core.deps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ausgabe von {@code mvn dependency:tree} in allen Formen, die im Log vorkommen (Testplan Welle 3). */
class DependencyTreeParserTest {

    /** {@code dependency:tree -Dverbose} (maven-dependency-plugin 3.8) unter Maven 3.9. */
    static final String MAVEN_39_VERBOSE = """
            [INFO] Scanning for projects...
            [INFO]
            [INFO] ---------------------------< com.example:app >---------------------------
            [INFO] Building app 1.0-SNAPSHOT
            [INFO]   from pom.xml
            [INFO] --------------------------------[ jar ]---------------------------------
            [INFO]
            [INFO] --- dependency:3.8.1:tree (default-cli) @ app ---
            [INFO] com.example:app:jar:1.0-SNAPSHOT
            [INFO] +- org.springframework:spring-core:jar:6.2.1:compile
            [INFO] |  +- org.springframework:spring-jcl:jar:6.2.1:compile
            [INFO] |  \\- (org.slf4j:slf4j-api:jar:1.7.36:compile - omitted for conflict with 2.0.16)
            [INFO] +- org.slf4j:slf4j-api:jar:2.0.16:compile
            [INFO] +- com.fasterxml.jackson.core:jackson-databind:jar:2.18.2:compile (version managed from 2.17.0)
            [INFO] |  +- com.fasterxml.jackson.core:jackson-annotations:jar:2.18.2:compile (version managed from 2.18.1; scope managed from runtime)
            [INFO] |  \\- (com.fasterxml.jackson.core:jackson-core:jar:2.18.2:compile - omitted for duplicate)
            [INFO] +- com.fasterxml.jackson.core:jackson-core:jar:2.18.2:compile
            [INFO] +- io.netty:netty-transport-native-epoll:jar:linux-x86_64:4.1.115.Final:runtime
            [INFO] +- org.optional:opt:jar:1.0:compile (optional)
            [INFO] \\- org.junit.jupiter:junit-jupiter:jar:5.11.3:test
            [INFO]    \\- (com.example:app:jar:1.0-SNAPSHOT:test - omitted for cycle)
            [INFO] ------------------------------------------------------------------------
            [INFO] BUILD SUCCESS
            """;

    /** Was jede Form des Baums ergeben muss. */
    static void assertMaven39(List<DepNode> roots) {
        assertEquals(1, roots.size());
        DepNode root = roots.get(0);
        assertEquals("com.example:app:1.0-SNAPSHOT", root.gav());
        assertEquals(List.of("spring-core", "slf4j-api", "jackson-databind", "jackson-core", "netty-transport-native-epoll",
                "opt", "junit-jupiter"), names(root.getChildren()));

        DepNode loser = root.getChildren().get(0).getChildren().get(1);
        assertEquals(DepNode.State.CONFLICT, loser.getState());
        assertEquals("1.7.36", loser.getVersion());
        assertEquals("2.0.16", loser.getRelatedVersion());
        assertEquals("org.springframework:spring-core:6.2.1 → org.slf4j:slf4j-api:1.7.36", loser.pathText());

        DepNode databind = root.getChildren().get(2);
        assertEquals("2.17.0", databind.getPremanagedVersion());
        assertNull(databind.getPremanagedScope());
        DepNode annotations = databind.getChildren().get(0);
        assertEquals("2.18.1", annotations.getPremanagedVersion());
        assertEquals("runtime", annotations.getPremanagedScope());
        assertEquals(DepNode.State.DUPLICATE, databind.getChildren().get(1).getState());

        DepNode epoll = root.getChildren().get(4);
        assertEquals("linux-x86_64", epoll.getClassifier());
        assertEquals("4.1.115.Final", epoll.getVersion());
        assertEquals("runtime", epoll.getScope());

        assertEquals(DepNode.State.INCLUDED, root.getChildren().get(5).getState(), "(optional) ist kein Auslassen");
        assertEquals(DepNode.State.CYCLE, root.getChildren().get(6).getChildren().get(0).getState());
        assertEquals("org.slf4j:slf4j-api:2.0.16", root.findIncluded("org.slf4j", "slf4j-api").gav());
    }

    static List<String> names(List<DepNode> nodes) {
        List<String> out = new ArrayList<>();
        for (DepNode n : nodes) {
            out.add(n.getArtifactId());
        }
        return out;
    }

    static List<DepNode> parse(String text) throws IOException {
        return DependencyTreeParser.parse(new StringReader(text));
    }

    static Stream<Arguments> formen() {
        return Stream.of(
                Arguments.of("Maven 3.9", (UnaryOperator<String>) s -> s),
                Arguments.of("Windows-Zeilenenden", (UnaryOperator<String>) s -> s.replace("\n", "\r\n")),
                Arguments.of("GitHub-Actions-Zeitstempel", (UnaryOperator<String>) s -> s.replaceAll("(?m)^\\[",
                        "2024-11-19T10:15:30.1234567Z [")),
                Arguments.of("Jenkins-Zeitstempel", (UnaryOperator<String>) s -> s.replaceAll("(?m)^\\[", "[2024-11-19T10:15:30.123Z] [")),
                Arguments.of("ANSI-Farben", (UnaryOperator<String>) s -> s.replace("[INFO]", "[\u001B[1;34mINFO\u001B[m]")
                        .replace("com.example:app:jar:1.0-SNAPSHOT\n", "\u001B[1mcom.example:app:jar:1.0-SNAPSHOT\u001B[m\n")),
                Arguments.of("-DoutputFile ohne Präfix", (UnaryOperator<String>) s -> s.lines()
                        .filter(l -> l.startsWith("[INFO] com.") || l.startsWith("[INFO] +") || l.startsWith("[INFO] |")
                                || l.startsWith("[INFO] \\") || l.startsWith("[INFO]    "))
                        .map(l -> l.substring("[INFO] ".length())).reduce("", (a, b) -> a + b + "\n")),
                Arguments.of("-Dtokens=extended", (UnaryOperator<String>) s -> s.replace("+- ", "├─ ")
                        .replace("\\- ", "└─ ").replace("|  ", "│  ")),
                Arguments.of("Leerzeichen am Zeilenende", (UnaryOperator<String>) s -> s.replace("\n", "   \n")),
                Arguments.of("Warnungen dazwischen", (UnaryOperator<String>) s -> s.replace("[INFO] +- org.slf4j",
                        "[WARNING] Some problems were encountered\n[INFO] +- org.slf4j"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("formen")
    void jedeFormErgibtDenselbenBaum(String name, UnaryOperator<String> form) throws IOException {
        assertMaven39(parse(form.apply(MAVEN_39_VERBOSE)));
    }

    @Test
    void maven4MitMehrerenModulen() throws IOException {
        List<DepNode> roots = parse("""
                [INFO] --- dependency:3.8.1:tree (default-cli) @ core ---
                [INFO] com.example:core:jar:2.0.0
                [INFO] \\- org.slf4j:slf4j-api:jar:2.0.16:compile
                [INFO]
                [INFO] --- dependency:3.8.1:tree (default-cli) @ web ---
                [INFO] com.example:web:war:2.0.0
                [INFO] +- com.example:core:jar:2.0.0:compile
                [INFO] |  \\- org.slf4j:slf4j-api:jar:2.0.16:compile
                [INFO] \\- jakarta.servlet:jakarta.servlet-api:jar:6.1.0:provided
                [INFO] ------------------------------------------------------------------------
                [INFO] Reactor Summary for parent 2.0.0:
                [INFO] core ............................................... SUCCESS [  0.512 s]
                """);
        assertEquals(List.of("core", "web"), names(roots));
        assertEquals("war", roots.get(1).getType());
        assertEquals("com.example:core:2.0.0 → org.slf4j:slf4j-api:2.0.16",
                roots.get(1).getChildren().get(0).getChildren().get(0).pathText());
        assertEquals("provided", roots.get(1).getChildren().get(1).getScope());
    }

    @Test
    void wurzelMitCiFriendlyOderBuildMetadatenVersion() throws IOException {
        assertEquals("${revision}", parse("[INFO] com.example:app:jar:${revision}\n[INFO] \\- a:b:jar:1:compile\n")
                .get(0).getVersion());
        List<DepNode> roots = parse("[INFO] com.example:app:jar:1.0.0+build.5\n[INFO] \\- a:b:jar:1:compile\n");
        assertEquals(1, roots.size());
        assertEquals("1.0.0+build.5", roots.get(0).getVersion());
        assertEquals(1, roots.get(0).getChildren().size());
    }

    @Test
    void falscheEinrueckungWirdUebersprungenStattFalschZugeordnet() throws IOException {
        List<DepNode> roots = parse("""
                [INFO] com.example:app:jar:1.0
                [INFO] +- a:a:jar:1:compile
                [INFO] |     \\- b:b:jar:1:compile
                [INFO] |  |  \\- c:c:jar:1:compile
                [INFO] \\- d:d:jar:1:compile
                """);
        DepNode root = roots.get(0);
        assertEquals(List.of("a", "d"), names(root.getChildren()));
        assertTrue(root.getChildren().get(0).getChildren().isEmpty());
    }

    @Test
    void unbrauchbareZeilenErgebenKeinenKnoten() {
        assertNull(DependencyTreeParser.node("nur-ein-wort"));
        assertNull(DependencyTreeParser.node("a:b"));
        assertNull(DependencyTreeParser.node("a:b:c:d:e:f:g"));
        assertEquals("a:b:1", DependencyTreeParser.node("a:b:jar:1").gav());
    }
}
