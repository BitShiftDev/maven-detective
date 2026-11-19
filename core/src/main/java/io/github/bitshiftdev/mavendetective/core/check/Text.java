package io.github.bitshiftdev.mavendetective.core.check;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Kleine Textwerkzeuge für Prüfungen. */
public final class Text {

    private static final Pattern FEATURE = Pattern.compile("^(?:1\\.)?(\\d{1,9})(?!\\d)");
    private static final Pattern HOST = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*(?::[a-zA-Z]+)?://(?:[^@/]*@)?([^/:]+)");

    private Text() {
    }

    /**
     * Regex für einen absoluten Pfad mit einer der Endungen ({@code "jar|zip"}): Verzeichnisse dürfen
     * Leerzeichen enthalten ({@code C:\\Users\\Max Mustermann\\…}), der Dateiname nicht. Ein Leerzeichen vor
     * einem neuen Pfadanfang beendet den Pfad. Länge begrenzt, damit {@code find()} über lange Zeilen
     * linear bleibt. Ohne eigene Gruppe.
     */
    public static String absolutePath(String extensions) {
        return "(?:[A-Za-z]:)?[\\\\/](?:(?:[^\\s:;'\"\\[]|[ \\t](?![\\\\/]|[A-Za-z]:)){0,400}?[\\\\/])?"
                + "[^\\s\\\\/:;'\"\\[]+\\.(?:" + extensions + ")";
    }

    /** Java-Feature-Version: "1.8.0_392" → 8, "17.0.9" → 17, "25-ea" → 25; -1 wenn unbekannt. */
    public static int javaFeatureVersion(String version) {
        if (version == null) {
            return -1;
        }
        Matcher m = FEATURE.matcher(version.trim());
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Class-File-Version → Java-Version (65 → 21). */
    public static int javaForClassVersion(int classVersion) {
        return classVersion > 44 ? classVersion - 44 : -1;
    }

    /** Wie oben, Version als Text aus dem Log; -1, wenn es keine Zahl ist oder sie nicht in {@code int} passt. */
    public static int javaForClassVersion(String classVersion) {
        int n = number(classVersion);
        return n < 0 ? -1 : javaForClassVersion(n);
    }

    /** Nicht negative Zahl aus dem Log; -1, wenn {@code s} fehlt, keine Zahl ist oder nicht in {@code int} passt. */
    public static int number(String s) {
        if (s == null || s.isEmpty() || s.length() > 9) {
            return -1;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return -1;
            }
        }
        return Integer.parseInt(s);
    }

    private static final Pattern USER_INFO = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.\\-]*://)[^/@\\s]+@");

    /** URL ohne Zugangsdaten: {@code https://user:token@nexus/…} → {@code https://***@nexus/…}. */
    public static String maskUserInfo(String url) {
        return url == null || url.indexOf('@') < 0 ? url : USER_INFO.matcher(url).replaceFirst("$1***@");
    }

    public static String host(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = HOST.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    public static boolean isMavenCentral(String url) {
        String h = host(url);
        return h != null && (h.equals("repo.maven.apache.org") || h.equals("repo1.maven.org")
                || h.equals("central.sonatype.com"));
    }

    public static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = Character.toLowerCase(a.charAt(i - 1)) == Character.toLowerCase(b.charAt(j - 1)) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** Ähnlich genug für einen „Meinten Sie …?“-Hinweis. */
    public static boolean similar(String a, String b) {
        if (a == null || b == null || a.equals(b)) {
            return false;
        }
        int max = Math.max(1, Math.min(a.length(), b.length()) / 4);
        return a.equalsIgnoreCase(b) || levenshtein(a, b) <= Math.min(2, max + 1);
    }

    /**
     * Pfad aus Logtext oder Session-Angaben, wenn er lokal ist; {@code null} für Netzwerkpfade
     * ({@code \\\\host\\share}, {@code //host/share}, {@code \\\\?\\UNC\\…}) und ungültige Pfade. Unter Windows
     * baut schon {@code Files.exists} auf einen UNC-Pfad eine SMB-Verbindung auf und sendet dabei den
     * NTLM-Hash des Nutzers – ein fremdes Log darf das nicht auslösen.
     * <p>
     * Auch {@code null} für laufwerksrelative Pfade ({@code a:}, {@code C:pom.xml}): Unter Windows hängen sie
     * vom Arbeitsverzeichnis des Laufwerks ab, und {@code toAbsolutePath()} wirft für ein fehlendes Laufwerk
     * {@code java.io.IOError} – ein Error, den keine Prüfung erwartet. Geprüft wird der Text, damit jedes
     * Betriebssystem gleich entscheidet.
     */
    public static Path localPath(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty() || isNetworkPath(t) || isDriveRelative(t)) {
            return null;
        }
        Path p;
        try {
            p = Paths.get(t);
        } catch (InvalidPathException e) {
            return null;
        }
        Path root = p.getRoot();
        return root != null && isNetworkPath(root.toString()) ? null : p;
    }

    /** Laufwerksbuchstabe und Doppelpunkt ohne folgenden Trenner: {@code a:}, {@code C:pom.xml}. */
    private static boolean isDriveRelative(String t) {
        if (t.length() < 2 || t.charAt(1) != ':' || !isAsciiLetter(t.charAt(0))) {
            return false;
        }
        return t.length() == 2 || (t.charAt(2) != '\\' && t.charAt(2) != '/');
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isNetworkPath(String t) {
        return t.length() >= 2 && (t.charAt(0) == '\\' || t.charAt(0) == '/') && (t.charAt(1) == '\\' || t.charAt(1) == '/');
    }

    public static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
