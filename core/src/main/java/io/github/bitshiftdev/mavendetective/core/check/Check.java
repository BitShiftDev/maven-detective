package io.github.bitshiftdev.mavendetective.core.check;

/**
 * Eine gezielte Prüfung, die eine Regel des Katalogs per ID aufruft (z. B. {@code last-updated-files}).
 * Prüfungen lesen Dateien und Einstellungen, aber ändern nichts und gehen nie ins Netz.
 */
public interface Check {

    String id();

    CheckResult run(CheckContext ctx);
}
