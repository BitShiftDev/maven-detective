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

    @Test
    void ueberlangerLogBehaeltKopfUndEnde() {
        StringBuilder log = new StringBuilder();
        MavenRunListener.append(log, "[INFO] Apache Maven 3.9.11\n");
        String zeile = "[INFO] Running test …………………………………………………………………………\n";
        for (long geschrieben = 0; geschrieben < MavenRunListener.MAX_CHARS * 2L; geschrieben += zeile.length()) {
            MavenRunListener.append(log, zeile);
            assertTrue(log.length() <= MavenRunListener.MAX_CHARS);
        }
        MavenRunListener.append(log, "[INFO] BUILD FAILURE\n[ERROR] Failed to execute goal x\n");
        String text = log.toString();
        assertTrue(text.startsWith("[INFO] Apache Maven 3.9.11\n"));
        assertTrue(text.endsWith("[ERROR] Failed to execute goal x\n"));
        assertTrue(text.contains(MavenRunListener.CUT_MARKER));
    }
}
