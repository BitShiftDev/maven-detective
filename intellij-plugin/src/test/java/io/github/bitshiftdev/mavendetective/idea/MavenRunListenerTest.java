package io.github.bitshiftdev.mavendetective.idea;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenRunListenerTest {

    @Test
    void normalEndReportedAsDestroyedIsNotAnAbort() {
        // So meldet IntelliJ 2025.3 einen gescheiterten bzw. grünen Lauf über das mvn-Skript
        assertFalse(MavenRunListener.userAborted(true, 1));
        assertFalse(MavenRunListener.userAborted(true, 0));
    }

    @Test
    void stopButtonIsAnAbort() {
        assertTrue(MavenRunListener.userAborted(true, 130));
        assertTrue(MavenRunListener.userAborted(true, 143));
        assertTrue(MavenRunListener.userAborted(true, -1));
        assertFalse(MavenRunListener.userAborted(false, 143));
    }
}
