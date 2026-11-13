package io.github.bitshiftdev.mavendetective.idea;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SimulateUpgradeActionTest {

    private static final String POM = """
            <project>
              <parent>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-parent</artifactId>
                <version>3.4.0</version>
              </parent>
              <artifactId>app</artifactId>
            </project>
            """;

    @Test
    void findsParentUnderCaret() {
        assertArrayEquals(new String[] {"org.springframework.boot", "spring-boot-starter-parent"},
                SimulateUpgradeAction.parentAt(POM, POM.indexOf("3.4.0")));
        assertArrayEquals(new String[] {"org.springframework.boot", "spring-boot-starter-parent"},
                SimulateUpgradeAction.parentAt(POM, POM.indexOf("<parent>")));
        assertNull(SimulateUpgradeAction.parentAt(POM, POM.indexOf("<artifactId>app")));
        assertNull(SimulateUpgradeAction.parentAt("<project/>", 3));
    }
}
