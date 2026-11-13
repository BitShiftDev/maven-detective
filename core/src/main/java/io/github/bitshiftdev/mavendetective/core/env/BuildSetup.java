package io.github.bitshiftdev.mavendetective.core.env;

import io.github.bitshiftdev.mavendetective.core.Texts;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Womit ein Maven-Build läuft: Maven-Version und -Quelle, JDK, settings.xml, lokales Repository,
 * Profile und Zusatzargumente. Eine Seite für die IDE (füllt das Plugin), eine für die Kommandozeile
 * ({@link #detectCommandLine}). {@link SetupComparison} stellt beide gegenüber.
 */
public final class BuildSetup {

    private String label;
    private String mavenVersion;
    private String mavenSource;
    private String mavenHome;
    private String javaVersion;
    private String javaHome;
    private String userSettings;
    private String localRepository;
    private final List<String> profiles = new ArrayList<String>();
    /** Inhalt von .mvn/maven.config, wie er in den Lauf eingeht; {@code null} = wird nicht gelesen. */
    private String mavenConfig;
    private String jvmConfig;
    private String mavenOpts;
    private final List<String> notes = new ArrayList<String>();

    public BuildSetup(String label) {
        this.label = label;
    }

    public String getLabel() { return label; }
    public BuildSetup setLabel(String label) { this.label = label; return this; }

    public String getMavenVersion() { return mavenVersion; }
    public BuildSetup setMavenVersion(String v) { this.mavenVersion = v; return this; }

    /** z. B. „Wrapper (mvnw)“, „gebündelt“, „/opt/maven“. */
    public String getMavenSource() { return mavenSource; }
    public BuildSetup setMavenSource(String s) { this.mavenSource = s; return this; }

    /** Installationsverzeichnis von Maven (für {@code conf/settings.xml}); {@code null} = unbekannt. */
    public String getMavenHome() { return mavenHome; }
    public BuildSetup setMavenHome(String h) { this.mavenHome = h; return this; }

    public String getJavaVersion() { return javaVersion; }
    public BuildSetup setJavaVersion(String v) { this.javaVersion = v; return this; }

    public String getJavaHome() { return javaHome; }
    public BuildSetup setJavaHome(String h) { this.javaHome = h; return this; }

    public String getUserSettings() { return userSettings; }
    public BuildSetup setUserSettings(String s) { this.userSettings = s; return this; }

    public String getLocalRepository() { return localRepository; }
    public BuildSetup setLocalRepository(String r) { this.localRepository = r; return this; }

    public List<String> getProfiles() { return profiles; }

    public String getMavenConfig() { return mavenConfig; }
    public BuildSetup setMavenConfig(String c) { this.mavenConfig = c; return this; }

    public String getJvmConfig() { return jvmConfig; }
    public BuildSetup setJvmConfig(String c) { this.jvmConfig = c; return this; }

    public String getMavenOpts() { return mavenOpts; }
    public BuildSetup setMavenOpts(String o) { this.mavenOpts = o; return this; }

    /** Hinweise zur Erkennung (z. B. „JAVA_HOME nicht gesetzt, java aus PATH“). */
    public List<String> getNotes() { return notes; }

    // ---------------------------------------------------------------- Kommandozeile erkennen

    private static final Pattern WRAPPER_VERSION = Pattern.compile("apache-maven-([0-9][^/]*?)-bin\\.(?:zip|tar\\.gz)");
    private static final Pattern MAVEN_CORE_JAR = Pattern.compile("maven-core-([0-9][^/]*)\\.jar");
    private static final Pattern JAVA_VERSION = Pattern.compile("^JAVA_VERSION=\"?([^\"]+)\"?\\s*$", Pattern.MULTILINE);

    /**
     * Was ein {@code ./mvnw} bzw. {@code mvn} im Terminal nehmen würde – nur aus Dateien und
     * Umgebungsvariablen, ohne einen Prozess zu starten.
     *
     * @param env Umgebungsvariablen der Shell (in der IDE: die der IDE, das ist eine Näherung)
     */
    public static BuildSetup detectCommandLine(Path projectDir, Path userHome, Map<String, String> env) {
        BuildSetup s = new BuildSetup(Texts.t("setup.command.line"));
        Path mvnDir = projectDir == null ? null : projectDir.resolve(".mvn");

        // Maven: Wrapper vor installiertem Maven
        Path wrapperProps = mvnDir == null ? null : mvnDir.resolve("wrapper").resolve("maven-wrapper.properties");
        boolean hasWrapperScript = projectDir != null
                && (Files.isRegularFile(projectDir.resolve("mvnw")) || Files.isRegularFile(projectDir.resolve("mvnw.cmd")));
        if (wrapperProps != null && Files.isRegularFile(wrapperProps)) {
            Properties p = load(wrapperProps);
            Matcher m = WRAPPER_VERSION.matcher(p.getProperty("distributionUrl", ""));
            s.mavenVersion = m.find() ? m.group(1) : null;
            s.mavenSource = hasWrapperScript ? "Wrapper (./mvnw)" : Texts.t("setup.wrapper.no.script");
            Path dist = wrapperDistribution(s.mavenVersion, userHome, env);
            s.mavenHome = dist == null ? null : dist.toString();
        } else {
            Path home = mavenHomeFromEnv(env);
            if (home == null) {
                home = mavenHomeFromPath(env);
            }
            if (home != null) {
                s.mavenVersion = mavenVersionOf(home);
                s.mavenSource = home.toString();
                s.mavenHome = home.toString();
            } else {
                s.notes.add(Texts.t("setup.note.no.maven"));
            }
        }

        // JDK: JAVA_HOME, sonst java im PATH
        String javaHome = env.get("JAVA_HOME");
        Path jh = javaHome != null && !javaHome.trim().isEmpty() ? Paths.get(javaHome.trim()) : null;
        if (jh == null) {
            Path java = which("java", env);
            if (java != null) {
                jh = realParent(java, 2);
                s.notes.add(Texts.t("setup.note.no.java.home"));
            }
        }
        if (jh != null) {
            s.javaHome = jh.toString();
            s.javaVersion = javaVersionOf(jh);
        }

        // .mvn/maven.config, .mvn/jvm.config, MAVEN_OPTS
        List<String> configArgs = Collections.emptyList();
        if (mvnDir != null && Files.isRegularFile(mvnDir.resolve("maven.config"))) {
            s.mavenConfig = read(mvnDir.resolve("maven.config"));
            configArgs = splitArgs(s.mavenConfig);
        }
        if (mvnDir != null && Files.isRegularFile(mvnDir.resolve("jvm.config"))) {
            s.jvmConfig = read(mvnDir.resolve("jvm.config"));
        }
        String opts = env.get("MAVEN_OPTS");
        s.mavenOpts = opts == null || opts.trim().isEmpty() ? null : opts.trim();

        // settings.xml: -s aus maven.config, sonst ~/.m2/settings.xml
        String settings = argValue(configArgs, "-s", "--settings");
        if (settings != null) {
            s.userSettings = projectDir.resolve(settings).normalize().toString();
        } else if (userHome != null && Files.isRegularFile(userHome.resolve(".m2").resolve("settings.xml"))) {
            s.userSettings = userHome.resolve(".m2").resolve("settings.xml").toString();
        }
        String repo = argValue(configArgs, "-Dmaven.repo.local", null);
        if (repo != null) {
            s.localRepository = repo;
        }
        for (String profile : argValues(configArgs, "-P", "--activate-profiles")) {
            for (String p : profile.split(",")) {
                if (!p.trim().isEmpty()) {
                    s.profiles.add(p.trim());
                }
            }
        }
        return s;
    }

    /**
     * Vom Wrapper schon entpacktes Maven ({@code ~/.m2/wrapper/dists/apache-maven-X-bin/<hash>/apache-maven-X});
     * {@code null}, solange der Wrapper es noch nicht geladen hat.
     */
    static Path wrapperDistribution(String version, Path userHome, Map<String, String> env) {
        if (version == null) {
            return null;
        }
        String mavenUserHome = env.get("MAVEN_USER_HOME");
        Path base = mavenUserHome != null && !mavenUserHome.trim().isEmpty() ? Paths.get(mavenUserHome.trim())
                : userHome == null ? null : userHome.resolve(".m2");
        if (base == null) {
            return null;
        }
        Path dists = base.resolve("wrapper").resolve("dists").resolve("apache-maven-" + version + "-bin");
        if (!Files.isDirectory(dists)) {
            return null;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dists)) {
            for (Path hash : ds) {
                Path home = hash.resolve("apache-maven-" + version);
                if (Files.isDirectory(home.resolve("conf"))) {
                    return home;
                }
                if (Files.isDirectory(hash.resolve("conf"))) {
                    return hash;
                }
            }
        } catch (IOException ignored) {
            // nicht lesbar
        }
        return null;
    }

    /** Versionsnummer aus {@code <javaHome>/release}, z. B. "21.0.4". */
    public static String javaVersionOf(Path javaHome) {
        Path release = javaHome.resolve("release");
        if (!Files.isRegularFile(release)) {
            return null;
        }
        Matcher m = JAVA_VERSION.matcher(read(release));
        return m.find() ? m.group(1).trim() : null;
    }

    /** Version aus {@code <mavenHome>/lib/maven-core-X.jar}. */
    public static String mavenVersionOf(Path mavenHome) {
        Path lib = mavenHome.resolve("lib");
        if (!Files.isDirectory(lib)) {
            return null;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(lib, "maven-core-*.jar")) {
            for (Path p : ds) {
                Matcher m = MAVEN_CORE_JAR.matcher(p.getFileName().toString());
                if (m.matches()) {
                    return m.group(1);
                }
            }
        } catch (IOException ignored) {
            // nicht lesbar
        }
        return null;
    }

    /** Hauptversion: "1.8.0_402" → 8, "21.0.4" → 21, "17" → 17. */
    public static int javaMajor(String version) {
        if (version == null) {
            return -1;
        }
        Matcher m = Pattern.compile("^(\\d+)(?:\\.(\\d+))?").matcher(version.trim());
        if (!m.find()) {
            return -1;
        }
        int first = Integer.parseInt(m.group(1));
        return first == 1 && m.group(2) != null ? Integer.parseInt(m.group(2)) : first;
    }

    private static Path mavenHomeFromEnv(Map<String, String> env) {
        for (String name : new String[] {"MAVEN_HOME", "M2_HOME"}) {
            String v = env.get(name);
            if (v != null && !v.trim().isEmpty() && Files.isDirectory(Paths.get(v.trim()))) {
                return Paths.get(v.trim());
            }
        }
        return null;
    }

    private static Path mavenHomeFromPath(Map<String, String> env) {
        Path mvn = which("mvn", env);
        return mvn == null ? null : realParent(mvn, 2);
    }

    /** Sucht ein Programm im PATH. */
    static Path which(String program, Map<String, String> env) {
        String path = env.get("PATH");
        if (path == null) {
            path = env.get("Path");
        }
        if (path == null) {
            return null;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String[] suffixes = windows ? new String[] {".exe", ".cmd", ".bat", ""} : new String[] {""};
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            for (String suffix : suffixes) {
                Path p = Paths.get(dir, program + suffix);
                if (Files.isRegularFile(p) && (windows || Files.isExecutable(p))) {
                    return p;
                }
            }
        }
        return null;
    }

    /** Symlinks auflösen (z. B. /usr/bin/java → /usr/lib/jvm/…/bin/java) und {@code levels} Ebenen hoch. */
    private static Path realParent(Path file, int levels) {
        Path p;
        try {
            p = file.toRealPath();
        } catch (IOException e) {
            p = file.toAbsolutePath();
        }
        for (int i = 0; i < levels && p != null; i++) {
            p = p.getParent();
        }
        return p;
    }

    static List<String> splitArgs(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) {
            return out;
        }
        Matcher m = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)").matcher(text);
        while (m.find()) {
            out.add(m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3));
        }
        return out;
    }

    /** Wert zu {@code -s x}, {@code -sx}, {@code --settings=x} oder {@code -Dname=x}. */
    static String argValue(List<String> args, String shortName, String longName) {
        List<String> all = argValues(args, shortName, longName);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    static List<String> argValues(List<String> args, String shortName, String longName) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (shortName.startsWith("-D")) {
                if (a.startsWith(shortName + "=")) {
                    out.add(a.substring(shortName.length() + 1));
                }
                continue;
            }
            if (a.equals(shortName) || (longName != null && a.equals(longName))) {
                if (i + 1 < args.size()) {
                    out.add(args.get(++i));
                }
            } else if (longName != null && a.startsWith(longName + "=")) {
                out.add(a.substring(longName.length() + 1));
            } else if (a.startsWith(shortName) && a.length() > shortName.length() && !a.startsWith("--")) {
                out.add(a.substring(shortName.length()));
            }
        }
        return out;
    }

    private static Properties load(Path file) {
        Properties p = new Properties();
        try (java.io.Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException ignored) {
            // leer lassen
        }
        return p;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }
}
