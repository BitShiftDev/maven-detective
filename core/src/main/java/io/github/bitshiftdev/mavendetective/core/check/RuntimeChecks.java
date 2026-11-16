package io.github.bitshiftdev.mavendetective.core.check;

import io.github.bitshiftdev.mavendetective.core.Texts;
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
            r.evidence(Evidence.environment(Texts.t("check.jdk.of.run",
                    (ctx.env().getRunnerJavaVersion() != null ? ctx.env().getRunnerJavaVersion() : runner > 0 ? String.valueOf(runner) : Texts.t("unknown"))
                    + (home == null ? "" : " (" + home + ")"))));
            for (String marker : RELEASE_MARKERS) {
                for (ProjectPoms.Location l : ctx.projectPoms().findText(marker)) {
                    r.evidence(Evidence.file(Texts.t("check.jdk.project", l.pom.lines.get(l.line - 1).trim()), l.pom.file.toString(), l.line));
                    if (tooOld) {
                        r.fix(FixAction.of(FixAction.OPEN_FILE, Texts.t("check.jdk.raise"), "file", l.pom.file.toString(), "line", String.valueOf(l.line)));
                    }
                }
            }
            if (ctx.anyLineContains("toolchain")) {
                Path toolchains = ctx.env().getUserHome() == null ? null : ctx.env().getUserHome().resolve(".m2").resolve("toolchains.xml");
                if (toolchains != null && Files.isRegularFile(toolchains)) {
                    r.evidence(Evidence.file(Texts.t("check.jdk.toolchains"), toolchains.toString(), 1))
                            .fix(FixAction.of(FixAction.OPEN_FILE, Texts.t("check.jdk.toolchains.open"), "file", toolchains.toString(), "line", "1"));
                } else {
                    r.evidence(Evidence.hint(Texts.t("check.jdk.toolchains.missing")));
                }
            }
            if (!tooOld && required > 0) {
                r.fix(FixAction.of(FixAction.SWITCH_JDK, Texts.t("check.jdk.switch", required), "required", String.valueOf(required)));
            }
            return r;
        }
    }

    // ------------------------------------------------------------------ source-location

    static final class SourceLocation implements Check {
        private static final Pattern LOCATION = Pattern.compile("(?<![^\\s:\\[(])(" + Text.absolutePath("java|kt|groovy")
                + "|[^\\s:\\[\\\\/][^\\s:\\[]*\\.(?:java|kt|groovy)):\\[(\\d+),(\\d+)\\]");

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
            if (Text.localPath(file) == null) {
                return CheckResult.notApplicable();
            }
            CheckResult r = CheckResult.info().var("sourceFile", file).var("sourceLine", line)
                    .evidence(Evidence.file(Texts.t("check.compile.location"), file, Integer.parseInt(line)))
                    .fix(FixAction.of(FixAction.OPEN_FILE, Texts.t("check.compile.goto"), "file", file, "line", line));
            if (file.contains("/src/test/") || file.contains("\\src\\test\\")) {
                r.evidence(Evidence.hint(Texts.t("check.compile.test")));
            } else {
                r.evidence(Evidence.hint(Texts.t("check.compile.main")));
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
                r.fix(FixAction.of(FixAction.SHOW_DEPENDENCY, Texts.t("check.enforcer.show"), "artifact", artifact));
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
                    .cause(Texts.t("check.memory.killed.cause"))
                    .evidence(Evidence.hint(Texts.t("check.memory.killed.evidence")))
                    : CheckResult.info();
            r.var("memoryTarget", Texts.t(fork ? "check.memory.test.jvm" : "check.memory.maven.jvm"));

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
                r.evidence(Evidence.file(Texts.t("check.memory.argline.found", Text.truncate(text, 120)), l.pom.file.toString(), l.line));
                forkXmx = xmx(text, forkXmx);
            }
            if (fork && forkXmx == null) {
                // Surefire loggt die Kommandozeile des Forks: "Command was … java -Xmx256m …"
                for (String line : ctx.linesContaining("Command was")) {
                    forkXmx = xmx(line, forkXmx);
                    if (forkXmx != null) {
                        r.evidence(Evidence.log(Texts.t("check.memory.fork.xmx", forkXmx)));
                    }
                }
            }

            String option = metaspace ? "-XX:MaxMetaspaceSize=512m" : "-Xmx" + doubled(fork ? forkXmx : currentXmx);
            r.var("memoryOption", option);
            if (fork) {
                if (!argLines.isEmpty()) {
                    ProjectPoms.Location l = argLines.get(0);
                    r.fix(FixAction.of(FixAction.OPEN_FILE, Texts.t("check.memory.argline", option), "file", l.pom.file.toString(), "line", String.valueOf(l.line)));
                } else {
                    r.fix(FixAction.of(FixAction.MANUAL, Texts.t("check.memory.surefire", option)));
                }
            } else if (jvmConfig != null) {
                r.fix(FixAction.of(FixAction.SET_JVM_OPTION, Texts.t("check.memory.jvm.config", option), "file", jvmConfig.toString(), "option", option));
            } else {
                r.fix(FixAction.of(FixAction.MANUAL, Texts.t("check.memory.maven.opts", option)));
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
