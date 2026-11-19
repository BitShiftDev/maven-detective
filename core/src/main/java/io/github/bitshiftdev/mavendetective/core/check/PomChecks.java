package io.github.bitshiftdev.mavendetective.core.check;

import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.env.MavenSettings;
import io.github.bitshiftdev.mavendetective.core.model.ErrorRecord;
import io.github.bitshiftdev.mavendetective.core.model.PomProblem;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prüfungen an POMs: Fehlerstelle, Parent, Module, Plugins. */
final class PomChecks {

    private PomChecks() {
    }

    // ------------------------------------------------------------------ pom-location

    static final class PomLocation implements Check {
        /** Zeilennummern bis 9 Stellen – mehr passt nicht in {@code int} und kommt nur in verstümmelten Logs vor. */
        private static final Pattern WITH_FILE = Pattern.compile("@ ([^,\\r\\n]{1,500}?\\.xml), line (\\d{1,9}), column (\\d+)");
        private static final Pattern LINE_ONLY = Pattern.compile("@ line (\\d{1,9}), column (\\d+)");
        private static final Pattern PROJECT_FILE = Pattern.compile("The project \\S+ \\(([^)]+\\.xml)\\) has \\d+ errors?");

        @Override
        public String id() { return "pom-location"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            String file = null;
            int line = -1;
            String message = null;
            // 1. strukturierte POM-Probleme aus den Events
            for (ErrorRecord e : ctx.failure().getErrors()) {
                for (PomProblem p : e.getProblems()) {
                    if (p.getLine() > 0 && p.getSource() != null && p.getSource().endsWith(".xml")) {
                        file = p.getSource();
                        line = p.getLine();
                        message = p.getMessage();
                        break;
                    }
                }
                if (file != null) {
                    break;
                }
            }
            // 2. Text: "@ /pfad/pom.xml, line 6, column 72" oder "@ line 6, column 17" + "The project … (/pfad/pom.xml)"
            if (file == null) {
                Matcher m = ctx.find(WITH_FILE);
                if (m != null) {
                    file = m.group(1);
                    line = Integer.parseInt(m.group(2));
                } else {
                    Matcher lm = ctx.find(LINE_ONLY);
                    Matcher pf = ctx.find(PROJECT_FILE);
                    if (lm != null) {
                        line = Integer.parseInt(lm.group(1));
                        if (pf != null) {
                            file = pf.group(1);
                        } else if (ctx.var("file") != null) {
                            file = ctx.var("file");
                        } else if (ctx.projectPoms().root() != null) {
                            file = ctx.projectPoms().root().resolve("pom.xml").toString();
                        }
                    }
                }
            }
            if (file == null || line < 1) {
                return CheckResult.notApplicable();
            }
            Path p = Text.localPath(file);
            if (p == null) {
                return CheckResult.notApplicable();
            }
            CheckResult r = CheckResult.info().var("pomFile", file).var("pomLine", String.valueOf(line));
            String snippet = null;
            if (Files.isRegularFile(p)) {
                ProjectPoms.Pom pom = ctx.projectPoms().read(p);
                if (pom != null && line <= pom.lines.size()) {
                    snippet = pom.lines.get(line - 1).trim();
                }
            }
            r.evidence(Evidence.file(snippet != null ? Texts.t("check.pom.line", line, Text.truncate(snippet, 120))
                    : (message != null ? message : Texts.t("check.pom.location")), file, line));
            return r.fix(FixAction.of(FixAction.OPEN_FILE, Texts.t("check.pom.goto"), "file", file, "line", String.valueOf(line)));
        }
    }

    // ------------------------------------------------------------------ parent-relative-path

    static final class ParentRelativePath implements Check {
        private static final Pattern PROJECT_FILE = Pattern.compile("The project \\S+ \\(([^)]+\\.xml)\\) has");

        @Override
        public String id() { return "parent-relative-path"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            Path childFile = null;
            Matcher m = ctx.find(PROJECT_FILE);
            if (m != null) {
                childFile = Text.localPath(m.group(1));
            } else if (ctx.var("project") != null) {
                Coordinates c = Coordinates.parse(ctx.var("project"));
                ProjectPoms.Pom pom = c == null ? null : ctx.projectPoms().find(c.groupId, c.artifactId);
                childFile = pom == null ? null : pom.file;
            }
            if (childFile == null && ctx.projectPoms().rootPom() != null) {
                childFile = ctx.projectPoms().rootPom().file;
            }
            ProjectPoms.Pom child = childFile == null ? null : ctx.projectPoms().read(childFile);
            if (child == null || !child.hasParent) {
                return CheckResult.notApplicable();
            }
            String expected = child.parentGroupId + ":" + child.parentArtifactId + ":" + child.parentVersion;
            String rel = child.parentRelativePath == null ? "../pom.xml" : child.parentRelativePath;
            int parentLine = child.lineOf("<parent>");
            CheckResult r = CheckResult.info().var("parent", expected).var("relativePath", rel);
            if (rel.isEmpty()) {
                return r.evidence(Evidence.file(Texts.t("check.parent.relative.empty"), child.file.toString(), parentLine));
            }
            Path target = child.file.getParent().resolve(rel).normalize();
            if (Files.isDirectory(target)) {
                target = target.resolve("pom.xml");
            }
            ProjectPoms.Pom parent = ctx.projectPoms().read(target);
            if (parent == null) {
                return r.evidence(Evidence.file(Texts.t("check.parent.relative.none", rel), child.file.toString(), parentLine))
                        .fix(FixAction.of(FixAction.OPEN_FILE, Texts.t("check.parent.open"), "file", child.file.toString(), "line", String.valueOf(parentLine)));
            }
            String found = parent.gav();
            if (parent.parseable && !found.equals(expected)) {
                return CheckResult.confirmed().var("parent", expected).var("relativePath", rel).var("foundGav", found)
                        .cause(Texts.t("check.parent.mismatch.cause"))
                        .evidence(Evidence.file(Texts.t("check.parent.expected", expected), child.file.toString(), parentLine))
                        .evidence(Evidence.file(Texts.t("check.parent.found", found), parent.file.toString(), parent.lineOf("<artifactId>")))
                        .fix(FixAction.of(FixAction.OPEN_FILE, Texts.t("check.parent.open"), "file", child.file.toString(), "line", String.valueOf(parentLine)));
            }
            return r;
        }
    }

    // ------------------------------------------------------------------ child-module

    static final class ChildModule implements Check {
        @Override
        public String id() { return "child-module"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            String module = ctx.var("module");
            String parentFile = ctx.var("file");
            // Maven 4 nennt das Verzeichnis der POM und den Namen relativ dazu
            String dir = ctx.var("dir");
            if (parentFile == null && dir != null) {
                Path d = Text.localPath(dir);
                parentFile = d == null ? null : d.resolve("pom.xml").toString();
            }
            if (module == null || parentFile == null) {
                return CheckResult.notApplicable();
            }
            Path moduleDir = Text.localPath(module);
            Path parentPath = Text.localPath(parentFile);
            if (moduleDir == null || parentPath == null) {
                return CheckResult.notApplicable();
            }
            Path parentDir = parentPath.getParent();
            if (!moduleDir.isAbsolute() && parentDir != null) {
                moduleDir = parentDir.resolve(moduleDir).normalize();
            }
            if (Files.isDirectory(moduleDir) && Files.isRegularFile(moduleDir.resolve("pom.xml"))) {
                return CheckResult.info().evidence(Evidence.file(Texts.t("check.module.exists.now"), moduleDir.toString(), -1));
            }
            String name = parentDir == null ? module : parentDir.relativize(moduleDir).toString().replace(File.separatorChar, '/');
            ProjectPoms.Pom parent = ctx.projectPoms().read(parentPath);
            int line = parent == null ? -1 : parent.lineOf("<module>" + name + "</module>");
            if (line < 0 && parent != null) {
                line = parent.lineOf("<subproject>" + name + "</subproject>");
            }
            if (line < 0 && parent != null) {
                line = parent.lineOf(name);
            }
            CheckResult r = CheckResult.confirmed().var("moduleName", name).var("file", parentFile)
                    .evidence(Evidence.file(Files.isDirectory(moduleDir) ? Texts.t("check.module.no.pom", name) : Texts.t("check.module.missing", name), parentFile, line));
            if (parentDir != null) {
                for (String sibling : directories(parentDir)) {
                    if (Text.similar(sibling, moduleDir.getFileName().toString())) {
                        r.evidence(Evidence.hint(Texts.t("check.module.similar", sibling)));
                    }
                }
            }
            return r.fix(FixAction.of(FixAction.OPEN_FILE, Texts.t("check.module.open"), "file", parentFile, "line", String.valueOf(line)));
        }

        private static List<String> directories(Path dir) {
            List<String> out = new ArrayList<String>();
            DirectoryStream<Path> ds = null;
            try {
                ds = Files.newDirectoryStream(dir);
                for (Path p : ds) {
                    if (Files.isDirectory(p)) {
                        out.add(p.getFileName().toString());
                    }
                }
            } catch (IOException ignored) {
                // nichts
            } finally {
                if (ds != null) {
                    try {
                        ds.close();
                    } catch (IOException ignored) {
                        // egal
                    }
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ plugin-prefix

    static final class PluginPrefix implements Check {
        /** Verbreitete Prefixe, deren groupId nicht in den Standard-pluginGroups liegt. */
        static final Map<String, String> KNOWN = new LinkedHashMap<String, String>();
        static {
            KNOWN.put("spring-boot", "org.springframework.boot:spring-boot-maven-plugin");
            KNOWN.put("quarkus", "io.quarkus.platform:quarkus-maven-plugin");
            KNOWN.put("jib", "com.google.cloud.tools:jib-maven-plugin");
            KNOWN.put("sonar", "org.sonarsource.scanner.maven:sonar-maven-plugin");
            KNOWN.put("flyway", "org.flywaydb:flyway-maven-plugin");
            KNOWN.put("liquibase", "org.liquibase:liquibase-maven-plugin");
            KNOWN.put("jacoco", "org.jacoco:jacoco-maven-plugin");
            KNOWN.put("spotless", "com.diffplug.spotless:spotless-maven-plugin");
            KNOWN.put("openapi-generator", "org.openapitools:openapi-generator-maven-plugin");
            KNOWN.put("native", "org.graalvm.buildtools:native-maven-plugin");
            KNOWN.put("cyclonedx", "org.cyclonedx:cyclonedx-maven-plugin");
            KNOWN.put("dependency-check", "org.owasp:dependency-check-maven");
            KNOWN.put("spotbugs", "com.github.spotbugs:spotbugs-maven-plugin");
            KNOWN.put("jetty", "org.eclipse.jetty.ee10:jetty-ee10-maven-plugin");
            KNOWN.put("wildfly", "org.wildfly.plugins:wildfly-maven-plugin");
            KNOWN.put("helidon", "io.helidon.build-tools:helidon-maven-plugin");
            KNOWN.put("micronaut", "io.micronaut.maven:micronaut-maven-plugin");
            KNOWN.put("git-commit-id", "io.github.git-commit-id:git-commit-id-maven-plugin");
            KNOWN.put("frontend", "com.github.eirslett:frontend-maven-plugin");
            KNOWN.put("docker", "io.fabric8:docker-maven-plugin");
        }

        /** Prefixe aus den Standard-Gruppen org.apache.maven.plugins und org.codehaus.mojo. */
        static final List<String> STANDARD = Arrays.asList(
                "clean", "compiler", "surefire", "failsafe", "install", "deploy", "dependency", "help", "jar",
                "war", "ear", "resources", "site", "javadoc", "source", "enforcer", "release", "assembly", "shade",
                "antrun", "archetype", "checkstyle", "pmd", "toolchains", "wrapper", "versions", "exec",
                "build-helper", "flatten", "license", "properties", "buildnumber", "jaxb2", "sql", "tidy");

        @Override
        public String id() { return "plugin-prefix"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            String prefix = ctx.var("prefix");
            if (prefix == null) {
                return CheckResult.notApplicable();
            }
            CheckResult r;
            String known = KNOWN.get(prefix);
            ProjectPoms.Pom root = ctx.projectPoms().rootPom();
            if (known != null) {
                String artifactId = known.substring(known.indexOf(':') + 1);
                List<ProjectPoms.Location> declared = ctx.projectPoms().findText("<artifactId>" + artifactId + "</artifactId>");
                r = CheckResult.confirmed().var("plugin", known)
                        .var("pluginGroup", known.substring(0, known.indexOf(':')));
                if (declared.isEmpty()) {
                    r.cause(Texts.t("check.prefix.missing.cause"));
                } else {
                    ProjectPoms.Location l = declared.get(0);
                    r.cause(Texts.t("check.prefix.other.module.cause"))
                            .evidence(Evidence.file(Texts.t("check.prefix.declared.in"), l.pom.file.toString(), l.line));
                }
                r.fix(FixAction.of(FixAction.MANUAL, Texts.t("check.prefix.full", known)));
                if (root != null) {
                    int line = root.lineOf("<plugins>");
                    r.fix(FixAction.of(FixAction.OPEN_FILE, Texts.t("check.prefix.add"),
                            "file", root.file.toString(), "line", String.valueOf(line > 0 ? line : root.lineOf("</project>"))));
                }
                return r;
            }
            r = CheckResult.info();
            for (String candidate : candidates()) {
                if (Text.similar(prefix, candidate)) {
                    r.evidence(Evidence.hint(Texts.t("check.prefix.did.you.mean", candidate)));
                }
            }
            MavenSettings s = ctx.env().userSettings();
            if (s != null) {
                r.evidence(Evidence.setting(Texts.t("check.prefix.groups", s.getPluginGroups()), s.getFile().toString(), s.lineOf("<pluginGroups>")));
            }
            if (Boolean.TRUE.equals(ctx.env().getOffline()) || ctx.anyLineContains("offline")) {
                r.evidence(Evidence.hint(Texts.t("check.prefix.offline")));
            }
            return r;
        }

        private static List<String> candidates() {
            List<String> all = new ArrayList<String>(STANDARD);
            all.addAll(KNOWN.keySet());
            return all;
        }
    }

    // ------------------------------------------------------------------ plugin-version

    static final class PluginVersion implements Check {
        @Override
        public String id() { return "plugin-version"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            String plugin = ctx.var("plugin");
            // Nur die Warnung „'build.plugins.plugin.version' for … is missing“ handelt von einer fehlenden Version;
            // dieselbe Regel deckt auch unbekannte Prefixe und Goals ab
            String[] parts = plugin == null ? new String[0] : plugin.split(":", -1);
            if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()
                    || !ctx.anyLineContains("'build.plugins.plugin.version'")) {
                return CheckResult.notApplicable();
            }
            String groupId = parts[0];
            String artifactId = parts[1];
            boolean declared = false;
            List<ProjectPoms.Location> hits = new ArrayList<ProjectPoms.Location>();
            for (ProjectPoms.Pom pom : ctx.projectPoms().all()) {
                for (PluginEntry e : pluginEntries(pom.lines)) {
                    if (!artifactId.equals(e.artifactId) || !groupId.equals(e.groupId == null ? "org.apache.maven.plugins" : e.groupId)) {
                        continue;
                    }
                    declared = true;
                    if (!e.hasVersion) {
                        hits.add(new ProjectPoms.Location(pom, e.line));
                    }
                }
            }
            if (!declared) {
                return CheckResult.info().evidence(Evidence.hint(Texts.t("check.plugin.version.not.declared")));
            }
            if (hits.isEmpty()) {
                return CheckResult.notApplicable(); // überall mit <version> – fehlt dann im Parent außerhalb des Projekts
            }
            ProjectPoms.Location l = hits.get(0);
            CheckResult r = CheckResult.confirmed();
            for (ProjectPoms.Location h : hits) {
                r.evidence(Evidence.file(Texts.t("check.plugin.version.missing"), h.pom.file.toString(), h.line));
            }
            return r.fix(FixAction.of(FixAction.INSERT_PLUGIN_VERSION, Texts.t("check.plugin.version.insert", plugin),
                            "file", l.pom.file.toString(), "line", String.valueOf(l.line), "plugin", plugin))
                    .fix(FixAction.of(FixAction.OPEN_FILE, Texts.t("check.open.pom"),
                            "file", l.pom.file.toString(), "line", String.valueOf(l.line)));
        }
    }

    /** Ein {@code <plugin>}-Eintrag: Koordinaten, ob er eine eigene {@code <version>} hat, Zeile der artifactId. */
    static final class PluginEntry {
        String groupId;
        String artifactId;
        boolean hasVersion;
        int line;
    }

    private static final Pattern PLUGIN_BLOCK = Pattern.compile("<plugin\\s*>(.*?)</plugin\\s*>", Pattern.DOTALL);
    private static final Pattern NESTED = Pattern.compile(
            "<(dependencies|configuration|executions|goals)\\b[^>]*>.*?</\\1\\s*>|<(dependencies|configuration|executions|goals)\\b[^>]*/>",
            Pattern.DOTALL);
    private static final Pattern CHILD = Pattern.compile("<(groupId|artifactId|version)\\s*>\\s*([^<]*?)\\s*</\\1\\s*>");
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /**
     * Alle {@code <plugin>}-Einträge einer POM (build, pluginManagement, Profile, reporting). Kommentare
     * zählen nicht; Koordinaten aus verschachtelten {@code <dependencies>}/{@code <configuration>} auch nicht –
     * auch kein {@code <plugin>} in der Konfiguration eines anderen (z. B. {@code reportPlugins}).
     */
    static List<PluginEntry> pluginEntries(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        // verschachtelte Blöcke vor der Plugin-Suche ausblenden, sonst endet ein Plugin am </plugin> seiner Konfiguration
        String text = blank(blank(sb.toString(), COMMENT), NESTED);
        List<PluginEntry> out = new ArrayList<PluginEntry>();
        Matcher m = PLUGIN_BLOCK.matcher(text);
        while (m.find()) {
            String body = m.group(1);
            PluginEntry e = new PluginEntry();
            Matcher c = CHILD.matcher(body);
            while (c.find()) {
                if ("groupId".equals(c.group(1))) {
                    e.groupId = c.group(2);
                } else if ("artifactId".equals(c.group(1))) {
                    e.artifactId = c.group(2);
                    e.line = lineAt(text, m.start(1) + c.start());
                } else {
                    e.hasVersion = !c.group(2).isEmpty();
                }
            }
            if (e.artifactId != null) {
                out.add(e);
            }
        }
        return out;
    }

    /** Ersetzt jeden Treffer durch Leerzeichen; Zeilenumbrüche bleiben, damit Offsets und Zeilen stimmen. */
    private static String blank(String text, Pattern p) {
        Matcher m = p.matcher(text);
        StringBuilder out = new StringBuilder(text);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) {
                if (out.charAt(i) != '\n') {
                    out.setCharAt(i, ' ');
                }
            }
        }
        return out.toString();
    }

    private static int lineAt(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    // ------------------------------------------------------------------ plugin-declaration

    /**
     * Zeigt, wo ein Plugin (Variable {@code pluginArtifact}, z. B. maven-war-plugin) in den Projekt-POMs
     * steht – bevorzugt in der POM des gescheiterten Moduls ({@code failedModule}).
     */
    static final class PluginDeclaration implements Check {
        @Override
        public String id() { return "plugin-declaration"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            String artifactId = ctx.var("pluginArtifact");
            if (artifactId == null) {
                return CheckResult.notApplicable();
            }
            List<ProjectPoms.Location> hits = ctx.projectPoms().findText("<artifactId>" + artifactId + "</artifactId>");
            if (hits.isEmpty()) {
                return CheckResult.info().evidence(Evidence.hint(Texts.t("check.plugin.not.in.project", artifactId)));
            }
            ProjectPoms.Location best = hits.get(0);
            String failed = ctx.var("failedModule");
            for (ProjectPoms.Location h : hits) {
                if (failed != null && failed.equals(h.pom.artifactId)) {
                    best = h;
                    break;
                }
            }
            CheckResult r = CheckResult.info();
            int shown = 0;
            for (ProjectPoms.Location h : hits) {
                if (shown++ < 3) {
                    r.evidence(Evidence.file(Texts.t("check.plugin.declared", artifactId), h.pom.file.toString(), h.line));
                }
            }
            return r.fix(FixAction.of(FixAction.OPEN_FILE, Texts.t("check.plugin.open", artifactId),
                    "file", best.pom.file.toString(), "line", String.valueOf(best.line)));
        }
    }

    // ------------------------------------------------------------------ ear-module

    /**
     * Für „Artifact[war:g:a] is not a dependency of the project“: sucht die EAR-POM, den Modul-Eintrag in
     * der Plugin-Konfiguration und das Zielmodul im Reactor.
     */
    static final class EarModule implements Check {
        @Override
        public String id() { return "ear-module"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            String type = ctx.var("moduleType");
            String module = ctx.var("module");
            if (type == null || module == null || module.split(":").length < 2) {
                return CheckResult.notApplicable();
            }
            String groupId = module.split(":")[0];
            String artifactId = module.split(":")[1];
            String failed = ctx.var("failedModule");
            ProjectPoms.Pom ear = null;
            for (ProjectPoms.Pom p : ctx.projectPoms().all()) {
                if ("ear".equals(p.effectivePackaging()) && (failed == null || failed.equals(p.artifactId))) {
                    ear = p;
                    break;
                }
            }
            if (ear == null || dependency(ear, artifactId) != null) {
                return CheckResult.notApplicable();
            }
            CheckResult r = CheckResult.confirmed();
            int entry = lineAfter(ear, ear.lineOf("maven-ear-plugin"), "<artifactId>" + artifactId + "</artifactId>");
            if (entry > 0) {
                r.evidence(Evidence.file(Texts.t("check.ear.module", type), ear.file.toString(), entry));
            }
            r.evidence(Evidence.hint(Texts.t("check.ear.no.dependency", ear.artifactId, artifactId)));
            ProjectPoms.Pom target = ctx.projectPoms().find(groupId, artifactId);
            if (target != null) {
                String packaging = target.effectivePackaging();
                r.var("modulePackaging", packaging)
                        .evidence(Evidence.file(Texts.t("check.reactor.type.module", packaging), target.file.toString(),
                                target.lineOf("<artifactId>" + artifactId + "</artifactId>")));
                if (!packaging.equals(type)) {
                    r.evidence(Evidence.hint(Texts.t("check.ear.packaging", packaging, type)));
                }
            }
            int deps = ear.lineOf("<dependencies>");
            int at = deps > 0 ? deps : Math.max(1, ear.lineOf("<packaging>"));
            return r.fix(FixAction.of(FixAction.OPEN_FILE, Texts.t("check.ear.open"), "file", ear.file.toString(), "line", String.valueOf(at)));
        }

        private static int lineAfter(ProjectPoms.Pom pom, int from, String needle) {
            for (int i = Math.max(0, from); i < pom.lines.size(); i++) {
                if (pom.lines.get(i).contains(needle)) {
                    return i + 1;
                }
            }
            return -1;
        }
    }

    // ------------------------------------------------------------------ ee-namespace

    /**
     * Fehlendes javax.*-/jakarta.*-Paket: passt der Namensraum zur eingebundenen API (Jakarta EE 9 hat javax.*
     * in jakarta.* umbenannt), oder ist es ein Paket, das Java 11 aus dem JDK entfernt hat?
     */
    static final class EeNamespace implements Check {
        /** Pakete, die bis Java 10 im JDK lagen (JEP 320). */
        private static final Pattern REMOVED_FROM_JDK = Pattern.compile(
                "javax\\.(?:xml\\.bind|xml\\.ws|xml\\.soap|jws|activation|annotation(?!\\.processing)|transaction(?!\\.xa))(?:\\..*)?");
        private static final String[] JAVAX_APIS = {
                "<artifactId>javaee-api</artifactId>", "<artifactId>javaee-web-api</artifactId>",
                "<artifactId>javax.servlet-api</artifactId>", "<artifactId>javax.ws.rs-api</artifactId>",
                "<artifactId>javax.persistence-api</artifactId>", "<artifactId>javax.ejb-api</artifactId>"};

        @Override
        public String id() { return "ee-namespace"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            String pkg = ctx.var("package");
            if (pkg == null) {
                return CheckResult.notApplicable();
            }
            if (pkg.startsWith("jakarta.")) {
                List<ProjectPoms.Location> javax = new ArrayList<ProjectPoms.Location>();
                for (String needle : JAVAX_APIS) {
                    javax.addAll(ctx.projectPoms().findText(needle));
                }
                if (javax.isEmpty()) {
                    return CheckResult.notApplicable();
                }
                ProjectPoms.Location l = javax.get(0);
                CheckResult r = ctx.projectPoms().findText("<groupId>jakarta.").isEmpty()
                        ? CheckResult.confirmed() : CheckResult.info();
                return r.cause(Texts.t("check.jakarta.old.api.cause"))
                        .evidence(Evidence.file(Texts.t("check.jakarta.javax.declared", Text.truncate(l.pom.lines.get(l.line - 1).trim(), 100)), l.pom.file.toString(), l.line))
                        .fix(FixAction.of(FixAction.OPEN_FILE, Texts.t("check.jakarta.open.api"),
                                "file", l.pom.file.toString(), "line", String.valueOf(l.line)));
            }
            if (!pkg.startsWith("javax.")) {
                return CheckResult.notApplicable();
            }
            List<ProjectPoms.Location> jakarta = ctx.projectPoms().findText("<groupId>jakarta.");
            if (!jakarta.isEmpty()) {
                ProjectPoms.Location l = jakarta.get(0);
                return CheckResult.info()
                        .cause(Texts.t("check.jakarta.javax.import.cause"))
                        .evidence(Evidence.file(Texts.t("check.jakarta.artifact", Text.truncate(l.pom.lines.get(l.line - 1).trim(), 100)), l.pom.file.toString(), l.line))
                        .fix(FixAction.of(FixAction.MANUAL, Texts.t("check.jakarta.switch.imports")));
            }
            if (REMOVED_FROM_JDK.matcher(pkg).matches()) {
                return CheckResult.info()
                        .cause(Texts.t("check.jakarta.removed.cause"))
                        .evidence(Evidence.environment(Texts.t("check.jakarta.java", ctx.env().getRunnerJavaVersion() == null ? "?" : ctx.env().getRunnerJavaVersion())))
                        .fix(FixAction.of(FixAction.MANUAL, Texts.t("check.jakarta.add.api")));
            }
            return CheckResult.notApplicable();
        }
    }

    /** Direkte Dependency (nicht dependencyManagement) mit dieser artifactId, sonst {@code null}. */
    static PomFile.Dep dependency(ProjectPoms.Pom pom, String artifactId) {
        try {
            for (PomFile.Dep d : PomFile.read(pom.file).getDependencies()) {
                if (artifactId.equals(d.getArtifactId())) {
                    return d;
                }
            }
        } catch (Exception e) {
            // unlesbare POM: keine Aussage
        }
        return null;
    }

    static List<Check> all() {
        List<Check> l = new ArrayList<Check>();
        l.add(new PomLocation());
        l.add(new ParentRelativePath());
        l.add(new ChildModule());
        l.add(new PluginPrefix());
        l.add(new PluginVersion());
        l.add(new PluginDeclaration());
        l.add(new EarModule());
        l.add(new EeNamespace());
        return l;
    }
}
