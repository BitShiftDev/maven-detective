package io.github.bitshiftdev.mavendetective.idea;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WhyThisVersionActionTest {

    private static final String POM = """
            <project>
              <dependencies>
                <dependency>
                  <groupId>org.springframework</groupId>
                  <artifactId>spring-core</artifactId>
                  <exclusions>
                    <exclusion><groupId>commons-logging</groupId><artifactId>commons-logging</artifactId></exclusion>
                  </exclusions>
                </dependency>

                <dependency><groupId>com.acme</groupId><artifactId>util</artifactId></dependency>
              </dependencies>
            </project>
            """;

    @Test
    void findsDependencyAroundCaret() {
        assertArrayEquals(new String[] {"org.springframework", "spring-core"},
                WhyThisVersionAction.dependencyAt(POM, POM.indexOf("spring-core")));
        // Cursor in der Exclusion gehört trotzdem zur äußeren Dependency
        assertArrayEquals(new String[] {"org.springframework", "spring-core"},
                WhyThisVersionAction.dependencyAt(POM, POM.indexOf("commons-logging")));
        // Cursor auf dem Tag selbst
        assertArrayEquals(new String[] {"com.acme", "util"},
                WhyThisVersionAction.dependencyAt(POM, POM.indexOf("<dependency><groupId>com.acme") + 2));
    }

    @Test
    void nothingBetweenDependencies() {
        assertNull(WhyThisVersionAction.dependencyAt(POM, POM.indexOf("\n\n") + 1));
        assertNull(WhyThisVersionAction.dependencyAt(POM, 0));
    }
}
