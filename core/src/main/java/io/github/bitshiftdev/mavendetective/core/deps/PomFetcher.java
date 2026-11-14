package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.io.TextFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lädt POMs, die für eine Upgrade-Simulation lokal fehlen – <b>nur auf ausdrücklichen Wunsch</b>
 * (CLI {@code --fetch}, Knopf in der IDE); alles andere in Modul A geht nie ins Netz.
 *
 * <p>Dazu entsteht in einem temporären Verzeichnis eine POM, die die fehlenden Koordinaten als
 * {@code type=pom} deklariert, und Maven sammelt deren Baum mit {@code dependency:collect}. Das lädt
 * nur POMs (keine JARs), auch die der transitiven Dependencies und Parents. Die
 * {@code <repositories>} der Projekt-POMs werden übernommen, das lokale Repository explizit gesetzt.
 */
public final class PomFetcher {

    /** Feste Plugin-Version: ohne sie hinge das Ergebnis vom Metadaten-Stand im Repository ab. */
    public static final String GOAL = "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:collect";

    private static final Pattern GAV = Pattern.compile("^([\\w.\\-]+):([\\w.\\-]+):([^\\s:()]+)(\\s.*)?$");
    private static final Pattern REPOSITORIES = Pattern.compile("<repositories>(.*?)</repositories>", Pattern.DOTALL);
    private static final Pattern REPOSITORY = Pattern.compile("<repository>.*?</repository>", Pattern.DOTALL);
    private static final Pattern ID = Pattern.compile("<id>\\s*([^<]+?)\\s*</id>");
    private static final int TAIL = 40;

    /** Ergebnis eines Maven-Laufs. */
    public static final class Outcome {
        private final List<String> command;
        private final int exitCode;
        private final List<String> output;

        Outcome(List<String> command, int exitCode, List<String> output) {
            this.command = command;
            this.exitCode = exitCode;
            this.output = output;
        }

        public List<String> getCommand() { return command; }
        public int getExitCode() { return exitCode; }
        public boolean isOk() { return exitCode == 0; }
        /** Die letzten Zeilen der Ausgabe. */
        public List<String> getOutput() { return output; }

        /** Die aussagekräftigsten Zeilen bei einem Fehler ({@code [ERROR]}), sonst das Ende. */
        public List<String> errors() {
            List<String> out = new ArrayList<String>();
            for (String l : output) {
                if (l.startsWith("[ERROR]") && !l.trim().equals("[ERROR]") && !l.contains("-> [Help") && !l.contains("re-run Maven")
                        && !l.contains("full stack trace") && !l.contains("For more information") && !l.contains("http://cwiki")
                        && !l.contains("https://cwiki")) {
                    out.add(l);
                }
            }
            return out.isEmpty() ? output : out;
        }
    }

    private PomFetcher() {
    }

    /** Nur die Einträge aus {@code missing}, die mit {@code g:a:v} beginnen (ohne Duplikate). */
    public static List<String> coordinates(Collection<String> missing) {
        Set<String> out = new LinkedHashSet<String>();
        for (String m : missing) {
            Matcher x = GAV.matcher(m.trim());
            if (x.matches() && !x.group(3).contains("${") && !"?".equals(x.group(3))) {
                out.add(x.group(1) + ":" + x.group(2) + ":" + x.group(3));
            }
        }
        return new ArrayList<String>(out);
    }

    /** {@code ./mvnw} im Projekt, sonst {@code <mavenHome>/bin/mvn}, sonst {@code mvn} aus dem PATH. */
    public static String executable(Path projectRoot, Path mavenHome) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (projectRoot != null) {
            Path wrapper = projectRoot.resolve(windows ? "mvnw.cmd" : "mvnw");
            if (Files.isRegularFile(wrapper) && (windows || Files.isExecutable(wrapper))) {
                return wrapper.toString();
            }
        }
        if (mavenHome != null) {
            Path mvn = mavenHome.resolve("bin").resolve(windows ? "mvn.cmd" : "mvn");
            if (Files.isRegularFile(mvn)) {
                return mvn.toString();
            }
        }
        return windows ? "mvn.cmd" : "mvn";
    }

    /** Die temporäre POM: fehlende Koordinaten als {@code type=pom}, Repositories des Projekts. */
    static String fetchPom(Collection<String> gavs, Collection<String> repositories) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n");
        sb.append("  <modelVersion>4.0.0</modelVersion>\n");
        sb.append("  <groupId>maven-detective.fetch</groupId>\n");
        sb.append("  <artifactId>fetch</artifactId>\n");
        sb.append("  <version>1</version>\n");
        sb.append("  <packaging>pom</packaging>\n");
        if (!repositories.isEmpty()) {
            sb.append("  <repositories>\n");
            for (String r : repositories) {
                sb.append("    ").append(r.trim()).append('\n');
            }
            sb.append("  </repositories>\n");
        }
        sb.append("  <dependencies>\n");
        for (String gav : gavs) {
            String[] p = gav.split(":");
            sb.append("    <dependency><groupId>").append(p[0]).append("</groupId><artifactId>").append(p[1])
                    .append("</artifactId><version>").append(p[2]).append("</version><type>pom</type></dependency>\n");
        }
        sb.append("  </dependencies>\n");
        sb.append("</project>\n");
        return sb.toString();
    }

    /** {@code <repository>}-Blöcke aus den POMs (auch aus Profilen), je ID der erste. */
    static List<String> repositories(Collection<Path> poms) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Path p : poms) {
            String xml;
            try {
                xml = TextFile.read(p).getText();
            } catch (IOException e) {
                continue;
            }
            Matcher rs = REPOSITORIES.matcher(xml);
            while (rs.find()) {
                Matcher r = REPOSITORY.matcher(rs.group(1));
                while (r.find()) {
                    Matcher id = ID.matcher(r.group());
                    String key = id.find() ? id.group(1) : r.group();
                    if (!out.containsKey(key) && !r.group().contains("${")) {
                        out.put(key, r.group());
                    }
                }
            }
        }
        return new ArrayList<String>(out.values());
    }

    /**
     * Lädt die POMs zu {@code gavs} (und ihren Baum) ins lokale Repository.
     *
     * @param executable  Maven-Aufruf, siehe {@link #executable}
     * @param projectRoot Arbeitsverzeichnis (für {@code ./mvnw} und {@code .mvn/})
     * @param localRepo   lokales Repository oder {@code null} (dann Mavens Standard)
     * @param projectPoms POMs, deren {@code <repositories>} übernommen werden
     * @param environment zusätzliche Umgebungsvariablen (z. B. {@code JAVA_HOME} der IDE) oder {@code null}
     * @param cancellation wird alle 200 ms geprüft; bei Abbruch wird Maven beendet
     */
    public static Outcome fetch(String executable, Path projectRoot, Path localRepo, Collection<String> gavs, Collection<Path> projectPoms,
                                Map<String, String> environment, Cancellation cancellation, long timeoutSeconds) throws IOException {
        Cancellation cancel = cancellation == null ? Cancellation.NONE : cancellation;
        Path dir = Files.createTempDirectory("maven-detective-fetch");
        Path pom = dir.resolve("pom.xml");
        try {
            Files.write(pom, fetchPom(gavs, repositories(projectPoms)).getBytes(StandardCharsets.UTF_8));
            List<String> cmd = new ArrayList<String>();
            cmd.add(executable);
            cmd.add("-B");
            cmd.add("-f");
            cmd.add(pom.toString());
            if (localRepo != null) {
                cmd.add("-Dmaven.repo.local=" + localRepo);
            }
            cmd.add(GOAL);
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            if (environment != null) {
                pb.environment().putAll(environment);
            }
            if (projectRoot != null && Files.isDirectory(projectRoot)) {
                pb.directory(projectRoot.toFile());
            }
            final Process process = pb.start();
            final Deque<String> tail = new ArrayDeque<String>();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), Charset.defaultCharset()));
                    try {
                        for (String line = r.readLine(); line != null; line = r.readLine()) {
                            synchronized (tail) {
                                tail.addLast(line);
                                if (tail.size() > TAIL) {
                                    tail.removeFirst();
                                }
                            }
                        }
                    } catch (IOException ignored) {
                        // Prozess beendet
                    }
                }
            }, "maven-detective-fetch");
            reader.setDaemon(true);
            reader.start();
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
            try {
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    cancel.check();
                    if (System.currentTimeMillis() > deadline) {
                        process.destroy();
                        throw new IOException(Texts.t("fetch.timeout", timeoutSeconds));
                    }
                }
                reader.join(2000);
            } catch (InterruptedException e) {
                process.destroy();
                Thread.currentThread().interrupt();
                throw new IOException("Unterbrochen", e);
            } catch (RuntimeException e) {
                process.destroy();   // Abbruch durch den Aufrufer
                throw e;
            }
            List<String> out;
            synchronized (tail) {
                out = new ArrayList<String>(tail);
            }
            return new Outcome(cmd, process.exitValue(), out);
        } finally {
            Files.deleteIfExists(pom);
            Files.deleteIfExists(dir);
        }
    }

    /** Befehl für die Anzeige, wenn jemand selbst laden will. */
    public static String manualCommand(String gav) {
        return "mvn dependency:get -Dartifact=" + gav + ":pom -Dtransitive=false";
    }
}
