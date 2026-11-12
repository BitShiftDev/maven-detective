package io.github.bitshiftdev.mavendetective.core.deps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomEditorTest {

    private static String pin(String xml) {
        return PomEditor.pinManagedVersion(xml, "org.lib", "lib", "2.0").apply(xml);
    }

    @Test
    void insertsIntoExistingManagement() {
        String xml = """
                <project>
                    <artifactId>parent</artifactId>
                    <dependencyManagement>
                        <dependencies>
                            <!-- <dependency><groupId>org.lib</groupId><artifactId>lib</artifactId></dependency> -->
                            <dependency>
                                <groupId>org.other</groupId>
                                <artifactId>other</artifactId>
                                <version>1.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """;
        PomEditor.Edit edit = PomEditor.pinManagedVersion(xml, "org.lib", "lib", "2.0");
        assertEquals("""
                <project>
                    <artifactId>parent</artifactId>
                    <dependencyManagement>
                        <dependencies>
                            <!-- <dependency><groupId>org.lib</groupId><artifactId>lib</artifactId></dependency> -->
                            <dependency>
                                <groupId>org.other</groupId>
                                <artifactId>other</artifactId>
                                <version>1.0</version>
                            </dependency>
                            <dependency>
                                <groupId>org.lib</groupId>
                                <artifactId>lib</artifactId>
                                <version>2.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """, edit.apply(xml));
        assertEquals(11, edit.line);
    }

    @Test
    void changesExistingVersionButNotInProfiles() {
        String xml = """
                <project>
                  <profiles><profile><dependencyManagement><dependencies>
                    <dependency><groupId>org.lib</groupId><artifactId>lib</artifactId><version>0.1</version></dependency>
                  </dependencies></dependencyManagement></profile></profiles>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.lib</groupId>
                        <artifactId>lib</artifactId>
                        <version> 1.5 </version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """;
        String out = pin(xml);
        assertTrue(out.contains("<version>2.0</version>"), out);
        assertTrue(out.contains("<version>0.1</version>"), out);
        assertEquals(xml.length() - " 1.5 ".length() + 3, out.length());
    }

    @Test
    void changesPropertyInSameFile() {
        String xml = """
                <project>
                  <properties>
                    <lib.version>1.5</lib.version>
                  </properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.lib</groupId>
                        <artifactId>lib</artifactId>
                        <version>${lib.version}</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """;
        PomEditor.Edit edit = PomEditor.pinManagedVersion(xml, "org.lib", "lib", "2.0");
        assertTrue(edit.description.contains("${lib.version}"), edit.description);
        assertEquals(3, edit.line);
        String out = edit.apply(xml);
        assertTrue(out.contains("<lib.version>2.0</lib.version>"), out);
        assertTrue(out.contains("<version>${lib.version}</version>"), out);
    }

    @Test
    void createsManagementBeforeDependencies() {
        String xml = "<project>\r\n\t<artifactId>parent</artifactId>\r\n\t<dependencies>\r\n\t</dependencies>\r\n</project>\r\n";
        String out = pin(xml);
        assertEquals("<project>\r\n\t<artifactId>parent</artifactId>\r\n"
                + "\t<dependencyManagement>\r\n\t\t<dependencies>\r\n\t\t\t<dependency>\r\n"
                + "\t\t\t\t<groupId>org.lib</groupId>\r\n\t\t\t\t<artifactId>lib</artifactId>\r\n\t\t\t\t<version>2.0</version>\r\n"
                + "\t\t\t</dependency>\r\n\t\t</dependencies>\r\n\t</dependencyManagement>\r\n\r\n"
                + "\t<dependencies>\r\n\t</dependencies>\r\n</project>\r\n", out);
    }

    @Test
    void createsManagementAtEndOfProject() {
        String xml = """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <artifactId>parent</artifactId>
                </project>""";
        String out = pin(xml);
        assertTrue(out.endsWith("""
                  <artifactId>parent</artifactId>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.lib</groupId>
                        <artifactId>lib</artifactId>
                        <version>2.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>"""), out);
    }

    @Test
    void addsDependenciesToEmptyManagement() {
        String xml = """
                <project>
                  <dependencyManagement>
                  </dependencyManagement>
                </project>
                """;
        String out = pin(xml);
        assertTrue(out.contains("  <dependencyManagement>\n    <dependencies>\n      <dependency>\n        <groupId>org.lib</groupId>"), out);
    }

    @Test
    void rejectsBrokenXml() {
        assertThrows(IllegalArgumentException.class, () -> pin("<project><dependencies></project>"));
        assertThrows(IllegalArgumentException.class, () -> pin("<foo/>"));
    }
}
