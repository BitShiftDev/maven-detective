package io.github.bitshiftdev.mavendetective.core.snapshot;

import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DeclaredVersions}: Profil-Aktivierung wie Mavens {@code DefaultProfileSelector} (je POM), {@code -D} vor
 * POM-Properties. Was von der Umgebung abhängt, fehlt lieber, als dass es rät.
 */
class DeclaredVersionsTest {

    @TempDir
    Path tmp;

    PomFile pom(String dir, String body) throws Exception {
        Path file = tmp.resolve(dir).resolve("pom.xml");
        Files.createDirectories(file.getParent());
        Files.write(file, ("<project>\n" + body + "\n</project>\n").getBytes(StandardCharsets.UTF_8));
        return PomFile.read(file);
    }

    static String profile(String id, String activation, String content) {
        return "<profile><id>" + id + "</id>" + (activation.isEmpty() ? "" : "<activation>" + activation + "</activation>")
                + content + "</profile>\n";
    }

    static final String LIB_2 = "<dependencies><dependency><groupId>g</groupId><artifactId>lib</artifactId>"
            + "<version>2.0</version></dependency></dependencies>";

    /**
     * Modul mit {@code g:lib:1.0}; Profil {@code p} hebt auf 2.0, Profil {@code q} (nur mit {@code -P}, oder mit
     * eigener Aktivierung) liegt daneben. Erwartet: Version im Lauf, {@code -} = unsicher (fehlt).
     */
    @ParameterizedTest(name = "p: {0} | q: {1} | {2} → {3}")
    @CsvSource(delimiter = '|', value = {
        "||''|1.0",
        "||-Pp|2.0",
        "||-P!p|1.0",
        "<activeByDefault>true</activeByDefault>||''|2.0",
        "<activeByDefault>true</activeByDefault>||-P!p|1.0",
        "<activeByDefault>true</activeByDefault>||-Pq|1.0",
        "<activeByDefault>true</activeByDefault>||-Pnirgends|2.0",
        "<activeByDefault>true</activeByDefault>||-Pp,q|2.0",
        "<activeByDefault>false</activeByDefault>||''|1.0",
        "<activeByDefault>true</activeByDefault>|<property><name>ci</name></property>|''|-",
        "<activeByDefault>true</activeByDefault>|<property><name>ci</name></property>|-P!q|2.0",
        "<activeByDefault>true</activeByDefault>|<property><name>ci</name></property>|-Pq|1.0",
        "<activeByDefault>true</activeByDefault>|<activeByDefault>true</activeByDefault>|''|2.0",
        "<property><name>ci</name></property>||''|-",
        "<property><name>ci</name></property>||-Pp|2.0",
        "<property><name>ci</name></property>||-P!p|1.0",
        // Maven entschiede hier sicher (Nutzer-Property vor System-Property); noch nicht ausgewertet → unsicher
        "<property><name>ci</name></property>||-Dci|-",
        "<property><name>!ci</name></property>||''|-",
        "<jdk>[21,)</jdk>||''|-",
        "<os><family>windows</family></os>||''|-",
        "<file><exists>${basedir}/flag</exists></file>||''|-",
        "<activeByDefault>true</activeByDefault><jdk>17</jdk>||''|-",
    })
    void profilAktivierung(String activationP, String activationQ, String args, String expected) throws Exception {
        PomFile module = pom("m", "<groupId>g</groupId><artifactId>m</artifactId><version>1</version>\n"
                + "<dependencies><dependency><groupId>g</groupId><artifactId>lib</artifactId><version>1.0</version></dependency></dependencies>\n"
                + "<profiles>\n" + profile("p", activationP == null ? "" : activationP, LIB_2)
                + profile("q", activationQ == null ? "" : activationQ, "") + "</profiles>");
        Map<String, String> versions = DeclaredVersions.of(List.of(module), new RunArguments().parse(args)).get("g:m");
        assertEquals("-".equals(expected) ? null : expected, versions == null ? null : versions.get("g:lib"));
    }

    @Test
    void activeByDefaultImParentFaelltNurDurchProfileDesParentsWeg() throws Exception {
        PomFile parent = pom("parent", "<groupId>g</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging>\n"
                + "<properties><lib.version>1.0</lib.version></properties>\n<profiles>\n"
                + profile("defaults", "<activeByDefault>true</activeByDefault>", "<properties><lib.version>1.5</lib.version></properties>")
                + profile("parent-only", "", "<properties><lib.version>1.7</lib.version></properties>")
                + "</profiles>");
        PomFile child = pom("child", "<parent><groupId>g</groupId><artifactId>parent</artifactId><version>1</version></parent>\n"
                + "<artifactId>child</artifactId>\n"
                + "<dependencies><dependency><groupId>g</groupId><artifactId>lib</artifactId><version>${lib.version}</version></dependency></dependencies>\n"
                + "<profiles>\n" + profile("child-only", "", "<properties><other>x</other></properties>") + "</profiles>");
        List<PomFile> poms = List.of(parent, child);

        assertEquals("1.5", lib(poms, ""));
        assertEquals("1.5", lib(poms, "-Pchild-only"), "Profil des Kindes schaltet activeByDefault im Parent nicht ab");
        assertEquals("1.7", lib(poms, "-Pparent-only"));
        assertEquals("1.0", lib(poms, "-P!defaults"));
        assertNull(DeclaredVersions.of(poms, new RunArguments()).get("g:parent"), "packaging pom fehlt");
    }

    @Test
    void kommandozeileVorProfilVorKindVorParent() throws Exception {
        PomFile parent = pom("parent", "<groupId>g</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging>\n"
                + "<properties><lib.version>1.0</lib.version></properties>\n<profiles>\n"
                + profile("bump", "", "<properties><lib.version>3.0</lib.version></properties>") + "</profiles>");
        PomFile child = pom("child", "<parent><groupId>g</groupId><artifactId>parent</artifactId><version>1</version></parent>\n"
                + "<artifactId>child</artifactId>\n<properties><lib.version>2.0</lib.version></properties>\n"
                + "<dependencies><dependency><groupId>g</groupId><artifactId>lib</artifactId><version>${lib.version}</version></dependency>\n"
                + "<dependency><groupId>g</groupId><artifactId>self</artifactId><version>${pom.version}</version></dependency>\n"
                + "<dependency><groupId>g</groupId><artifactId>sibling</artifactId><version>${project.parent.version}</version></dependency>\n"
                + "<dependency><groupId>g</groupId><artifactId>open</artifactId><version>${nirgends}</version></dependency>\n"
                + "<dependency><groupId>g</groupId><artifactId>empty</artifactId><version>${empty}</version></dependency>\n"
                + "<dependency><groupId>g</groupId><artifactId>native</artifactId><classifier>linux</classifier><version>1</version></dependency>\n"
                + "</dependencies>\n<profiles>\n"
                + profile("mine", "", "<properties><lib.version>4.0</lib.version><empty></empty></properties>") + "</profiles>");
        List<PomFile> poms = List.of(parent, child);

        assertEquals("2.0", lib(poms, ""), "Kind schlägt Parent");
        assertEquals("2.0", lib(poms, "-Pbump"), "Kind schlägt auch das Profil des Parents");
        assertEquals("4.0", lib(poms, "-Pmine"), "Profil des Kindes schlägt das Kind");
        assertEquals("9.9", lib(poms, "-Pmine -Dlib.version=9.9"), "-D schlägt alles");

        Map<String, String> all = DeclaredVersions.of(poms, new RunArguments().parse("-Pmine")).get("g:child");
        assertEquals("1", all.get("g:self"), "${pom.*} wie ${project.*}; Version vom Parent geerbt");
        assertEquals("1", all.get("g:sibling"));
        assertEquals("1", all.get("g:native:linux"), "Classifier gehört zum Schlüssel");
        assertTrue(!all.containsKey("g:open") && !all.containsKey("g:empty"), "offener oder leerer Ausdruck fehlt: " + all);
    }

    @Test
    void zyklischeParentsUndZyklischePropertiesEnden() throws Exception {
        PomFile a = pom("a", "<parent><groupId>g</groupId><artifactId>b</artifactId><version>1</version></parent>\n"
                + "<groupId>g</groupId><artifactId>a</artifactId><version>1</version>\n"
                + "<properties><x>${y}</x><y>${x}</y></properties>\n"
                + "<dependencies><dependency><groupId>g</groupId><artifactId>lib</artifactId><version>${x}</version></dependency>\n"
                + "<dependency><groupId>g</groupId><artifactId>fix</artifactId><version>1.0</version></dependency></dependencies>");
        PomFile b = pom("b", "<parent><groupId>g</groupId><artifactId>a</artifactId><version>1</version></parent>\n"
                + "<groupId>g</groupId><artifactId>b</artifactId><version>1</version>");
        Map<String, String> versions = DeclaredVersions.of(List.of(a, b), new RunArguments()).get("g:a");
        assertEquals(Map.of("g:fix", "1.0"), versions);
    }

    private static String lib(List<PomFile> poms, String args) {
        Map<String, String> versions = DeclaredVersions.of(poms, new RunArguments().parse(args)).get("g:child");
        return versions == null ? null : versions.get("g:lib");
    }
}
