package io.github.bitshiftdev.mavendetective.core.check;

import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.model.ErrorRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prüfungen zu JDK, Quelltext-Fehlern, Enforcer und Speicher. */
final class RuntimeChecks {

    private RuntimeChecks() {
    }

    // ------------------------------------------------------------------ jdk-version

    static final class JdkVersion implements Check {
        private static final String[] RELEASE_MARKERS = {
            "<maven.compiler.release>", "<maven.compiler.target>", "<maven.compiler.source>", "<java.version>", "<release>",
        };

        @Override
        public String id() { return "jdk-version"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            int required = Text.javaFeatureVersion(ctx.var("required"));
            if (required < 0 && ctx.var("classVersion") != null) {
                required = Text.javaForClassVersion(Integer.parseInt(ctx.var("classVersion")));
            }
            int runner = Text.javaFeatureVersion(ctx.env().getRunnerJavaVersion());
            if (runner < 0 && ctx.var("runtimeClassVersion") != null) {
                runner = Text.javaForClassVersion(Integer.parseInt(ctx.var("runtimeClassVersion")));
            }
            if (runner < 0 && ctx.var("runner") != null) {
                runner = Text.javaFeatureVersion(ctx.var("runner"));
            }
            boolean tooOld = ctx.anyLineContains("is no longer supported");
            CheckResult r = runner > 0 && required > 0 && (tooOld || runner < required)
                    ? CheckResult.confirmed() : CheckResult.info();
            if (required > 0) {
                r.var("required", String.valueOf(required));
            }
            if (runner > 0) {
                r.var("runner", String.valueOf(runner));
            }
            String home = ctx.failure().getSession().getJavaHome();
            r.evidence(Evidence.environment("JDK des Maven-Laufs: "
                    + (ctx.env().getRunnerJavaVersion() != null ? ctx.env().getRunnerJavaVersion() : runner > 0 ? String.valueOf(runner) : "unbekannt")
                    + (home == null ? "" : " (" + home + ")")));
            for (String marker : RELEASE_MARKERS) {
                for (ProjectPoms.Location l : ctx.projectPoms().findText(marker)) {
                    r.evidence(Evidence.file("Java-Version im Projekt: " + l.pom.lines.get(l.line - 1).trim(), l.pom.file.toString(), l.line));
                    if (tooOld) {
                        r.fix(FixAction.of(FixAction.OPEN_FILE, "Java-Release im Projekt anheben", "file", l.pom.file.toString(), "line", String.valueOf(l.line)));
                    }
                }
            }
            if (ctx.anyLineContains("toolchain")) {
                Path toolchains = ctx.env().getUserHome() == null ? null : ctx.env().getUserHome().resolve(".m2").resolve("toolchains.xml");
                if (toolchains != null && Files.isRegularFile(toolchains)) {
                    r.evidence(Evidence.file("toolchains.xml vorhanden", toolchains.toString(), 1))
                            .fix(FixAction.of(FixAction.OPEN_FILE, "toolchains.xml öffnen", "file", toolchains.toString(), "line", "1"));
                } else {
                    r.evidence(Evidence.hint("~/.m2/toolchains.xml fehlt"));
                }
            }
            if (!tooOld && required > 0) {
                r.fix(FixAction.of(FixAction.SWITCH_JDK, "Runner-JDK auf Java " + required + " umstellen", "required", String.valueOf(required)));
            }
            return r;
        }
    }

    // ------------------------------------------------------------------ source-location

    static final class SourceLocation implements Check {
        private static final Pattern LOCATION = Pattern.compile("((?:[A-Za-z]:)?[^\\s:\\[]+\\.(?:java|kt|groovy)):\\[(\\d+),(\\d+)\\]");

        @Override
        public String id() { return "source-location"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            Matcher m = ctx.find(LOCATION);
            if (m == null) {
                return CheckResult.notApplicable();
            }
            String file = m.group(1);
            String line = m.group(2);
            CheckResult r = CheckResult.info().var("sourceFile", file).var("sourceLine", line)
                    .evidence(Evidence.file("Fehlerstelle im Quelltext", file, Integer.parseInt(line)))
                    .fix(FixAction.of(FixAction.OPEN_FILE, "Zur Fehlerstelle springen", "file", file, "line", line));
            if (file.contains("/src/test/") || file.contains("\\src\\test\\")) {
                r.evidence(Evidence.hint("Fehler im Testcode – fehlt eine Test-Dependency (Scope test)?"));
            } else {
                r.evidence(Evidence.hint("Fehler im Hauptcode – steht die Dependency nur mit Scope test oder provided im Projekt, oder wurde sie per Exclusion entfernt?"));
            }
            return r;
        }
    }

    // ------------------------------------------------------------------ enforcer-rule

    static final class EnforcerRule implements Check {
        @Override
        public String id() { return "enforcer-rule"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            String rule = ctx.var("rule");
            if (rule == null) {
                return CheckResult.notApplicable();
            }
            String simple = rule.substring(rule.lastIndexOf('.') + 1);
            CheckResult r = CheckResult.info().var("ruleName", simple);
            // Die Meldung der Regel steht in den Zeilen nach "failed with message:"
            List<String> lines = ctx.lines();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(rule) && lines.get(i).contains("failed")) {
                    for (int j = i + 1; j < lines.size() && j <= i + 4; j++) {
                        if (lines.get(j).startsWith("Rule ") || lines.get(j).startsWith("Some Enforcer rules")) {
                            break;
                        }
                        r.evidence(Evidence.log(Text.truncate(lines.get(j), 200)));
                    }
                    break;
                }
            }
            String artifact = ctx.var("artifact");
            if (artifact != null && (simple.contains("Convergence") || simple.contains("Banned") || simple.contains("UpperBound"))) {
                r.fix(FixAction.of(FixAction.SHOW_DEPENDENCY, "Dependency im Baum zeigen", "artifact", artifact));
            }
            return r;
        }
    }

    // ------------------------------------------------------------------ memory-settings

    static final class MemorySettings implements Check {
        private static final Pattern XMX = Pattern.compile("-Xmx(\\d+)([kKmMgG]?)");

        @Override
        public String id() { return "memory-settings"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            boolean metaspace = ctx.anyLineContains("Metaspace");
            boolean fork = ctx.anyLineContains("surefire") || ctx.anyLineContains("failsafe")
                    || ctx.anyLineContains("forked VM") || ctx.anyLineContains("ForkedBooter");
            for (ErrorRecord e : ctx.failure().getErrors()) {
                if (e.getMojo() != null && (e.getMojo().contains("surefire") || e.getMojo().contains("failsafe"))) {
                    fork = true;
                }
            }
            boolean killed = ctx.anyLineContains("Exit Code: 137") || ctx.anyLineContains("exit code 137");
            CheckResult r = killed ? CheckResult.confirmed()
                    .cause("Die {memoryTarget} wurde vom Betriebssystem beendet (Exit-Code 137 = SIGKILL) – typisch für den OOM-Killer, wenn Rechner oder Container zu wenig RAM haben.")
                    .evidence(Evidence.hint("Exit-Code 137 = 128 + 9 (SIGKILL)"))
                    : CheckResult.info();
            r.var("memoryTarget", fork ? "Test-JVM (Surefire/Failsafe)" : "Maven-JVM");

            String currentXmx = null;
            String mavenOpts = ctx.env().getenv("MAVEN_OPTS");
            if (mavenOpts != null && !mavenOpts.trim().isEmpty()) {
                r.evidence(Evidence.environment("MAVEN_OPTS=" + mavenOpts));
                currentXmx = xmx(mavenOpts, currentXmx);
            }
            Path jvmConfig = ctx.projectPoms().root() == null ? null : ctx.projectPoms().root().resolve(".mvn").resolve("jvm.config");
            if (jvmConfig != null && Files.isRegularFile(jvmConfig)) {
                try {
                    String content = new String(Files.readAllBytes(jvmConfig), StandardCharsets.UTF_8).trim();
                    r.evidence(Evidence.file(".mvn/jvm.config: " + Text.truncate(content, 120), jvmConfig.toString(), 1));
                    currentXmx = xmx(content, currentXmx);
                } catch (IOException ignored) {
                    // nicht lesbar
                }
            }
            List<ProjectPoms.Location> argLines = ctx.projectPoms().findText("<argLine>");
            String forkXmx = null;
            for (ProjectPoms.Location l : argLines) {
                String text = l.pom.lines.get(l.line - 1).trim();
                r.evidence(Evidence.file("Surefire argLine: " + Text.truncate(text, 120), l.pom.file.toString(), l.line));
                forkXmx = xmx(text, forkXmx);
            }
            if (fork && forkXmx == null) {
                // Surefire loggt die Kommandozeile des Forks: "Command was … java -Xmx256m …"
                for (String line : ctx.linesContaining("Command was")) {
                    forkXmx = xmx(line, forkXmx);
                    if (forkXmx != null) {
                        r.evidence(Evidence.log("Test-JVM lief mit -Xmx" + forkXmx));
                    }
                }
            }

            String option = metaspace ? "-XX:MaxMetaspaceSize=512m" : "-Xmx" + doubled(fork ? forkXmx : currentXmx);
            r.var("memoryOption", option);
            if (fork) {
                if (!argLines.isEmpty()) {
                    ProjectPoms.Location l = argLines.get(0);
                    r.fix(FixAction.of(FixAction.OPEN_FILE, "argLine erhöhen auf " + option, "file", l.pom.file.toString(), "line", String.valueOf(l.line)));
                } else {
                    r.fix(FixAction.of(FixAction.MANUAL, "Surefire konfigurieren: <argLine>" + option + "</argLine>"));
                }
            } else if (jvmConfig != null) {
                r.fix(FixAction.of(FixAction.SET_JVM_OPTION, option + " in .mvn/jvm.config eintragen", "file", jvmConfig.toString(), "option", option));
            } else {
                r.fix(FixAction.of(FixAction.MANUAL, "MAVEN_OPTS=\"" + option + "\" setzen"));
            }
            return r;
        }

        private static String xmx(String text, String fallback) {
            Matcher m = XMX.matcher(text);
            String found = fallback;
            while (m.find()) {
                found = m.group(1) + m.group(2);
            }
            return found;
        }

        /** Verdoppelt einen -Xmx-Wert ("1g" → "2g", "768m" → "1536m"); ohne Wert 2g. */
        static String doubled(String xmx) {
            if (xmx == null) {
                return "2g";
            }
            Matcher m = Pattern.compile("(\\d+)([kKmMgG]?)").matcher(xmx);
            if (!m.matches()) {
                return "2g";
            }
            long n = Long.parseLong(m.group(1)) * 2;
            String unit = m.group(2).toLowerCase();
            if (unit.equals("m") && n >= 1024 && n % 1024 == 0) {
                return (n / 1024) + "g";
            }
            return n + unit;
        }
    }

    static List<Check> all() {
        List<Check> l = new ArrayList<Check>();
        l.add(new JdkVersion());
        l.add(new SourceLocation());
        l.add(new EnforcerRule());
        l.add(new MemorySettings());
        return l;
    }
}
