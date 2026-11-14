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
    @Test
    void removesOneExclusionWithItsLines() {
        String xml = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.web</groupId>
                      <artifactId>web</artifactId>
                      <exclusions>
                        <exclusion>
                          <groupId>org.a</groupId>
                          <artifactId>a</artifactId>
                        </exclusion>
                        <exclusion><groupId>org.b</groupId><artifactId>b</artifactId></exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                  <profiles><profile><dependencies><dependency>
                    <groupId>org.web</groupId><artifactId>web</artifactId>
                    <exclusions><exclusion><groupId>org.b</groupId><artifactId>b</artifactId></exclusion></exclusions>
                  </dependency></dependencies></profile></profiles>
                </project>
                """;
        PomEditor.Edit edit = PomEditor.removeExclusion(xml, "org.web", "web", "org.a:a", false);
        assertEquals(7, edit.line);
        String once = edit.apply(xml);
        assertEquals(xml.replace("""
                        <exclusion>
                          <groupId>org.a</groupId>
                          <artifactId>a</artifactId>
                        </exclusion>
                """, ""), once);

        String twice = PomEditor.removeExclusion(once, "org.web", "web", "org.b:b", false).apply(once);
        assertEquals("""
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.web</groupId>
                      <artifactId>web</artifactId>
                    </dependency>
                  </dependencies>
                """, twice.substring(0, twice.indexOf("  <profiles>")), "letzte Exclusion nimmt <exclusions> mit, Profil bleibt");
        assertTrue(twice.contains("<exclusions><exclusion><groupId>org.b</groupId>"));
    }

    @Test
    void removesInlineExclusionAndKnowsSections() {
        String xml = "<project><dependencyManagement><dependencies><dependency><groupId>g</groupId><artifactId>a</artifactId>"
                + "<exclusions><exclusion><groupId>x</groupId><artifactId>*</artifactId></exclusion></exclusions></dependency>"
                + "</dependencies></dependencyManagement></project>";
        assertThrows(IllegalArgumentException.class, () -> PomEditor.removeExclusion(xml, "g", "a", "x:*", false));
        assertEquals("<project><dependencyManagement><dependencies><dependency><groupId>g</groupId><artifactId>a</artifactId>"
                        + "</dependency></dependencies></dependencyManagement></project>",
                PomEditor.removeExclusion(xml, "g", "a", "x:*", true).apply(xml));
    }

    @Test
    void addsExclusionWithNewExclusionsBlock() {
        String xml = """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>org.web</groupId>
                            <artifactId>web</artifactId>
                            <version>1.0</version>
                        </dependency>
                    </dependencies>
                    <profiles><profile><dependencies>
                        <dependency><groupId>org.web</groupId><artifactId>web</artifactId></dependency>
                    </dependencies></profile></profiles>
                </project>
                """;
        PomEditor.Edit edit = PomEditor.addExclusion(xml, "org.web", "web", "commons-logging:commons-logging");
        assertEquals("""
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>org.web</groupId>
                            <artifactId>web</artifactId>
                            <version>1.0</version>
                            <exclusions>
                                <exclusion>
                                    <groupId>commons-logging</groupId>
                                    <artifactId>commons-logging</artifactId>
                                </exclusion>
                            </exclusions>
                        </dependency>
                    </dependencies>
                    <profiles><profile><dependencies>
                        <dependency><groupId>org.web</groupId><artifactId>web</artifactId></dependency>
                    </dependencies></profile></profiles>
                </project>
                """, edit.apply(xml));
        assertEquals(8, edit.line);
    }

    @Test
    void addsExclusionToExistingBlockAndRefusesDuplicates() {
        String xml = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.web</groupId>
                      <artifactId>web</artifactId>
                      <exclusions>
                        <exclusion>
                          <groupId>org.a</groupId>
                          <artifactId>a</artifactId>
                        </exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                </project>
                """;
        String out = PomEditor.addExclusion(xml, "org.web", "web", "org.b:b").apply(xml);
        assertTrue(out.contains("""
                          <artifactId>a</artifactId>
                        </exclusion>
                        <exclusion>
                          <groupId>org.b</groupId>
                          <artifactId>b</artifactId>
                        </exclusion>
                      </exclusions>
                """), out);
        assertThrows(IllegalArgumentException.class, () -> PomEditor.addExclusion(xml, "org.web", "web", "org.a:a"));
        assertThrows(IllegalArgumentException.class, () -> PomEditor.addExclusion(xml, "org.none", "none", "org.a:a"));
    }

    @Test
    void addsExclusionToOneLineDependency() {
        String xml = "<project>\n  <dependencies>\n    <dependency><groupId>g</groupId><artifactId>a</artifactId></dependency>\n"
                + "  </dependencies>\n</project>\n";
        String out = PomEditor.addExclusion(xml, "g", "a", "x:y").apply(xml);
        assertEquals("<project>\n  <dependencies>\n    <dependency><groupId>g</groupId><artifactId>a</artifactId>\n"
                + "      <exclusions>\n        <exclusion>\n          <groupId>x</groupId>\n          <artifactId>y</artifactId>\n"
                + "        </exclusion>\n      </exclusions>\n    </dependency>\n  </dependencies>\n</project>\n", out);
    }

    // ---------------------------------------------------------------- A5

    @Test
    void addsDependencyAtEndOfList() {
        String xml = """
                <project>
                    <artifactId>app</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>org.web</groupId>
                            <artifactId>web</artifactId>
                            <version>1.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        PomEditor.Edit edit = PomEditor.addDependency(xml, "org.slf4j", "slf4j-api", null, "test", null, null);
        assertEquals("""
                <project>
                    <artifactId>app</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>org.web</groupId>
                            <artifactId>web</artifactId>
                            <version>1.0</version>
                        </dependency>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """, edit.apply(xml));
        assertEquals(9, edit.line);
        assertThrows(IllegalArgumentException.class,
                () -> PomEditor.addDependency(xml, "org.web", "web", "1.0", null, null, null));
    }

    @Test
    void addsDependenciesBlockBeforeBuild() {
        String xml = "<project>\n  <artifactId>app</artifactId>\n  <build/>\n</project>\n";
        String out = PomEditor.addDependency(xml, "g", "a", "1.0", "compile", "test-jar", null).apply(xml);
        assertEquals("<project>\n  <artifactId>app</artifactId>\n  <dependencies>\n    <dependency>\n      <groupId>g</groupId>\n"
                + "      <artifactId>a</artifactId>\n      <version>1.0</version>\n      <type>test-jar</type>\n    </dependency>\n"
                + "  </dependencies>\n\n  <build/>\n</project>\n", out);
    }

    @Test
    void removesDependencyAndEmptyBlock() {
        String xml = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>g</groupId>
                      <artifactId>a</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>g</groupId>
                      <artifactId>b</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """;
        String once = PomEditor.removeDependency(xml, "g", "a", null).apply(xml);
        assertEquals("""
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>g</groupId>
                      <artifactId>b</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """, once);
        assertEquals("<project>\n</project>\n", PomEditor.removeDependency(once, "g", "b", null).apply(once));
        assertThrows(IllegalArgumentException.class, () -> PomEditor.removeDependency(xml, "g", "c", null));
    }

    @Test
    void setsScopeAfterVersionOrReplacesIt() {
        String xml = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>g</groupId>
                      <artifactId>a</artifactId>
                      <version>1.0</version>
                      <exclusions/>
                    </dependency>
                    <dependency><groupId>g</groupId><artifactId>b</artifactId><scope>provided</scope></dependency>
                  </dependencies>
                </project>
                """;
        PomEditor.Edit edit = PomEditor.setScope(xml, "g", "a", "test", null);
        String out = edit.apply(xml);
        assertTrue(out.contains("      <version>1.0</version>\n      <scope>test</scope>\n      <exclusions/>"), out);
        assertEquals(7, edit.line);
        String out2 = PomEditor.setScope(out, "g", "b", "test", null).apply(out);
        assertTrue(out2.contains("<artifactId>b</artifactId><scope>test</scope></dependency>"), out2);
        assertThrows(IllegalArgumentException.class, () -> PomEditor.setScope(out2, "g", "b", "test", null));
    }

    @Test
    void forFixDispatchesByType() {
        String xml = "<project>\n  <dependencies>\n    <dependency><groupId>g</groupId><artifactId>a</artifactId></dependency>\n"
                + "  </dependencies>\n</project>\n";
        String out = PomEditor.forFix(xml, io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction.of(
                io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction.SET_SCOPE, "x", "artifact", "g:a", "scope", "test")).apply(xml);
        assertTrue(out.contains("<artifactId>a</artifactId><scope>test</scope>"), out);
        assertThrows(IllegalArgumentException.class, () -> PomEditor.forFix(xml,
                io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction.of("manual", "x", "artifact", "g:a")));
    }
    @Test
    void setsVersionWhereItStands() {
        String xml = """
                <project>
                  <parent>
                    <groupId>org.p</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                  <dependencyManagement>
                    <dependencies>
                      <dependency><groupId>org.bom</groupId><artifactId>bom</artifactId><version>1.0</version><type>pom</type><scope>import</scope></dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency><groupId>g</groupId><artifactId>a</artifactId><version>1.0</version><classifier>x</classifier></dependency>
                    <dependency><groupId>g</groupId><artifactId>a</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>
                """;
        PomEditor.Edit parent = PomEditor.setVersion(xml, "parent", "org.p", "parent", "2.0");
        assertEquals(5, parent.line);
        assertTrue(parent.apply(xml).contains("<artifactId>parent</artifactId>\n    <version>2.0</version>"));
        assertEquals("Parent parent: Version von 1.0 auf 2.0 geändert", parent.description);
        assertTrue(PomEditor.setVersion(xml, "dependencyManagement", "org.bom", "bom", "2.0").apply(xml)
                .contains("<artifactId>bom</artifactId><version>2.0</version>"));
        String dep = PomEditor.setVersion(xml, "dependencies", "g", "a", "2.0").apply(xml);
        assertTrue(dep.contains("<version>1.0</version><classifier>x</classifier>"), "ohne Classifier bevorzugt: " + dep);
        assertTrue(dep.contains("<artifactId>a</artifactId><version>2.0</version></dependency>"), dep);
        assertThrows(IllegalArgumentException.class, () -> PomEditor.setVersion(xml, "parent", "org.x", "parent", "2.0"));
        assertThrows(IllegalArgumentException.class, () -> PomEditor.setVersion(xml, "dependencies", "g", "b", "2.0"));
    }

    @Test
    void setsOrAddsProperties() {
        String withProps = """
                <project>
                    <properties>
                        <a.version>1.0</a.version>
                    </properties>
                </project>
                """;
        assertTrue(PomEditor.setProperty(withProps, "a.version", "2.0").apply(withProps).contains("<a.version>2.0</a.version>"));
        PomEditor.Edit added = PomEditor.setProperty(withProps, "b.version", "3.0");
        assertEquals("""
                <project>
                    <properties>
                        <a.version>1.0</a.version>
                        <b.version>3.0</b.version>
                    </properties>
                </project>
                """, added.apply(withProps));
        assertEquals(4, added.line);

        String without = """
                <project>
                    <artifactId>x</artifactId>
                    <dependencies>
                    </dependencies>
                </project>
                """;
        PomEditor.Edit created = PomEditor.setProperty(without, "b.version", "3.0");
        assertEquals("""
                <project>
                    <artifactId>x</artifactId>
                    <properties>
                        <b.version>3.0</b.version>
                    </properties>

                    <dependencies>
                    </dependencies>
                </project>
                """, created.apply(without));
        assertEquals(4, created.line);
        String forFix = PomEditor.forFix(without, io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction.of(
                io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction.SET_VERSION, "x", "property", "b.version", "version", "3.0"))
                .apply(without);
        assertEquals(created.apply(without), forFix);
    }

    // --- Leer geschriebene Container (<x/>) werden aufgeklappt, nicht verdoppelt

    @Test
    void addsDependencyIntoSelfClosingDependencies() {
        String xml = "<project>\n  <artifactId>m</artifactId>\n  <dependencies/>\n</project>\n";
        assertEquals("""
                <project>
                  <artifactId>m</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>g</groupId>
                      <artifactId>a</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """, PomEditor.addDependency(xml, "g", "a", "1.0", null, null, null).apply(xml));
    }

    @Test
    void addsExclusionIntoSelfClosingExclusions() {
        String xml = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>g</groupId>
                      <artifactId>a</artifactId>
                      <exclusions />
                    </dependency>
                  </dependencies>
                </project>
                """;
        String out = PomEditor.addExclusion(xml, "g", "a", "x:y").apply(xml);
        assertEquals("""
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>g</groupId>
                      <artifactId>a</artifactId>
                      <exclusions>
                        <exclusion>
                          <groupId>x</groupId>
                          <artifactId>y</artifactId>
                        </exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                </project>
                """, out);
    }

    @Test
    void setsPropertyInSelfClosingPropertiesAndEmptyValue() {
        String xml = "<project>\n  <properties/>\n</project>\n";
        assertEquals("<project>\n  <properties>\n    <a.version>2.0</a.version>\n  </properties>\n</project>\n",
                PomEditor.setProperty(xml, "a.version", "2.0").apply(xml));
        String empty = "<project>\n  <properties>\n    <a.version/>\n  </properties>\n</project>\n";
        assertEquals("<project>\n  <properties>\n    <a.version>2.0</a.version>\n  </properties>\n</project>\n",
                PomEditor.setProperty(empty, "a.version", "2.0").apply(empty));
    }

    @Test
    void pinsIntoSelfClosingManagement() {
        String dm = "<project>\n  <dependencyManagement/>\n</project>\n";
        String expected = """
                <project>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.lib</groupId>
                        <artifactId>lib</artifactId>
                        <version>2.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """;
        assertEquals(expected, pin(dm));
        String deps = "<project>\n  <dependencyManagement>\n    <dependencies/>\n  </dependencyManagement>\n</project>\n";
        assertEquals(expected, pin(deps));
    }

    // --- Kommentare im Container bleiben beim Entfernen des letzten Eintrags

    @Test
    void keepsContainerWithCommentsWhenRemovingLastEntry() {
        String xml = """
                <project>
                  <dependencies>
                    <!-- <dependency><groupId>old</groupId><artifactId>x</artifactId></dependency> -->
                    <dependency>
                      <groupId>g</groupId>
                      <artifactId>a</artifactId>
                      <exclusions>
                        <!-- früher auch x:z -->
                        <exclusion>
                          <groupId>x</groupId>
                          <artifactId>y</artifactId>
                        </exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                </project>
                """;
        String noExclusion = PomEditor.removeExclusion(xml, "g", "a", "x:y", false).apply(xml);
        assertTrue(noExclusion.contains("<exclusions>\n        <!-- früher auch x:z -->\n      </exclusions>"), noExclusion);
        String noDependency = PomEditor.removeDependency(xml, "g", "a", null).apply(xml);
        assertEquals("""
                <project>
                  <dependencies>
                    <!-- <dependency><groupId>old</groupId><artifactId>x</artifactId></dependency> -->
                  </dependencies>
                </project>
                """, noDependency);
    }

    // --- Repository-Adresse: nur ganze <url>-Werte

    @Test
    void replacesOnlyWholeRepositoryUrls() {
        String xml = """
                <project>
                  <url>http://nexus.corp/repo</url>
                  <!-- siehe http://nexus.corp/repo-guide.html -->
                  <repositories>
                    <repository>
                      <id>corp</id>
                      <url> http://nexus.corp/repo/ </url>
                    </repository>
                    <repository>
                      <id>third</id>
                      <url>http://nexus.corp/repository/thirdparty</url>
                    </repository>
                  </repositories>
                  <profiles>
                    <profile>
                      <pluginRepositories>
                        <pluginRepository>
                          <url>http://nexus.corp/repo</url>
                        </pluginRepository>
                      </pluginRepositories>
                    </profile>
                  </profiles>
                </project>
                """;
        PomEditor.Edit edit = PomEditor.replaceUrl(xml, "http://nexus.corp/repo", "https://nexus.corp/repo");
        String out = edit.apply(xml);
        assertEquals(xml.replace("<url> http://nexus.corp/repo/ </url>", "<url> https://nexus.corp/repo/ </url>")
                .replace("<url>http://nexus.corp/repo</url>\n        </pluginRepository>",
                        "<url>https://nexus.corp/repo</url>\n        </pluginRepository>"), out);
        assertEquals(7, edit.line);
        assertThrows(IllegalArgumentException.class, () -> PomEditor.replaceUrl(xml, "http://nexus.corp/rep", "https://nexus.corp/rep"));
    }
}
