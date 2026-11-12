package io.github.bitshiftdev.mavendetective.idea;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixRunnerTest {

    private static final String TAG = "<artifactId>maven-compiler-plugin</artifactId>";

    @Test
    void pluginOhneVersionWirdGefunden() {
        String pom = """
                <project>
                  <build><plugins>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-compiler-plugin</artifactId>
                    </plugin>
                  </plugins></build>
                </project>
                """;
        assertEquals(pom.indexOf(TAG), FixRunner.pluginArtifactIdWithoutVersion(pom, TAG, 0));
    }

    @Test
    void pluginMitVersionBleibtUnberuehrt() {
        String pom = """
                <plugin>
                  <artifactId>maven-compiler-plugin</artifactId>
                  <version>3.14.0</version>
                </plugin>
                """;
        assertEquals(-1, FixRunner.pluginArtifactIdWithoutVersion(pom, TAG, 0));
    }

    @Test
    void trefferAusserhalbEinesPluginsZaehltNicht() {
        // Erst ein abgeschlossenes Plugin, dann dieselbe artifactId in einer Exclusion
        String pom = """
                <plugin><artifactId>other</artifactId></plugin>
                <dependency><exclusions><exclusion>
                  <artifactId>maven-compiler-plugin</artifactId>
                </exclusion></exclusions></dependency>
                """;
        assertEquals(-1, FixRunner.pluginArtifactIdWithoutVersion(pom, TAG, 0));
    }

    @Test
    void sucheBeginntAbDerGemeldetenZeile() {
        String pom = """
                <plugin><artifactId>maven-compiler-plugin</artifactId><version>1</version></plugin>
                <plugin><artifactId>maven-compiler-plugin</artifactId></plugin>
                """;
        int second = pom.indexOf(TAG, pom.indexOf(TAG) + 1);
        assertEquals(second, FixRunner.pluginArtifactIdWithoutVersion(pom, TAG, pom.indexOf('\n')));
        assertEquals(-1, FixRunner.pluginArtifactIdWithoutVersion(pom, TAG, 0));
    }
}
