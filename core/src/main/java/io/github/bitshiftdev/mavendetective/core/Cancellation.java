package io.github.bitshiftdev.mavendetective.core;

/**
 * Abbruchpunkt für lange Dateizugriffe. Der Kern kennt keine IDE; die IDE übergibt hier
 * {@code ProgressManager::checkCanceled}, damit z. B. eine Inspection beim nächsten Tastendruck
 * abbricht, statt die Read-Action festzuhalten.
 *
 * <p>{@link #check()} wirft eine unchecked Exception des Aufrufers. Code im Kern ruft sie deshalb
 * immer <b>außerhalb</b> von {@code catch (Exception)}-Blöcken auf, damit der Abbruch nicht
 * geschluckt wird.
 */
public interface Cancellation {

    /** Nie abbrechen (CLI, Extension, Tests). */
    Cancellation NONE = new Cancellation() {
        @Override
        public void check() {
        }
    };

    /** Wirft, wenn der Aufrufer abbrechen will; kehrt sonst sofort zurück. */
    void check();
}
