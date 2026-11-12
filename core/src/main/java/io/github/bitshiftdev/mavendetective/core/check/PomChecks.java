package io.github.bitshiftdev.mavendetective.core.check;

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
import java.nio.file.Paths;
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
        private static final Pattern WITH_FILE = Pattern.compile("@ (\\S+?\\.xml), line (\\d+), column (\\d+)");
        private static final Pattern LINE_ONLY = Pattern.compile("@ line (\\d+), column (\\d+)");
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
            CheckResult r = CheckResult.info().var("pomFile", file).var("pomLine", String.valueOf(line));
            Path p = Paths.get(file);
            String snippet = null;
            if (Files.isRegularFile(p)) {
                ProjectPoms.Pom pom = ctx.projectPoms().read(p);
                if (pom != null && line <= pom.lines.size()) {
                    snippet = pom.lines.get(line - 1).trim();
                }
            }
            r.evidence(Evidence.file(snippet != null ? "Zeile " + line + ": " + Text.truncate(snippet, 120)
                    : (message != null ? message : "Fehlerstelle"), file, line));
            return r.fix(FixAction.of(FixAction.OPEN_FILE, "Zur fehlerhaften Zeile springen", "file", file, "line", String.valueOf(line)));
        }
    }

    // ------------------------------------------------------------------ parent-relative-path

    static final class ParentRelativePath implements Check {
        @Override
        public String id() { return "parent-relative-path"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            Path childFile = null;
            Matcher m = ctx.find(Pattern.compile("The project \\S+ \\(([^)]+\\.xml)\\) has"));
            if (m != null) {
                childFile = Paths.get(m.group(1));
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
                return r.evidence(Evidence.file("<relativePath/> ist leer – Parent kommt nur aus dem Repository", child.file.toString(), parentLine));
            }
            Path target = child.file.getParent().resolve(rel).normalize();
            if (Files.isDirectory(target)) {
                target = target.resolve("pom.xml");
            }
            ProjectPoms.Pom parent = ctx.projectPoms().read(target);
            if (parent == null) {
                return r.evidence(Evidence.file("Unter relativePath " + rel + " liegt keine POM", child.file.toString(), parentLine))
                        .fix(FixAction.of(FixAction.OPEN_FILE, "<parent> in der POM öffnen", "file", child.file.toString(), "line", String.valueOf(parentLine)));
            }
            String found = parent.gav();
            if (parent.parseable && !found.equals(expected)) {
                return CheckResult.confirmed().var("parent", expected).var("relativePath", rel).var("foundGav", found)
                        .cause("relativePath ({relativePath}) zeigt auf eine POM mit {foundGav}, erwartet wird aber {parent} – meist eine veraltete Parent-Version.")
                        .evidence(Evidence.file("Parent erwartet: " + expected, child.file.toString(), parentLine))
                        .evidence(Evidence.file("Gefunden: " + found, parent.file.toString(), parent.lineOf("<artifactId>")))
                        .fix(FixAction.of(FixAction.OPEN_FILE, "<parent> in der POM öffnen", "file", child.file.toString(), "line", String.valueOf(parentLine)));
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
            if (module == null || parentFile == null) {
                return CheckResult.notApplicable();
            }
            Path moduleDir = Paths.get(module);
            Path parentPath = Paths.get(parentFile);
            if (Files.isDirectory(moduleDir) && Files.isRegularFile(moduleDir.resolve("pom.xml"))) {
                return CheckResult.info().evidence(Evidence.file("Verzeichnis existiert inzwischen", module, -1));
            }
            Path parentDir = parentPath.getParent();
            String name = parentDir == null ? module : parentDir.relativize(moduleDir).toString().replace(File.separatorChar, '/');
            ProjectPoms.Pom parent = ctx.projectPoms().read(parentPath);
            int line = parent == null ? -1 : parent.lineOf("<module>" + name + "</module>");
            if (line < 0 && parent != null) {
                line = parent.lineOf(name);
            }
            CheckResult r = CheckResult.confirmed().var("moduleName", name)
                    .evidence(Evidence.file(Files.isDirectory(moduleDir) ? "Verzeichnis ohne pom.xml: " + name : "Verzeichnis fehlt: " + name, parentFile, line));
            if (parentDir != null) {
                for (String sibling : directories(parentDir)) {
                    if (Text.similar(sibling, moduleDir.getFileName().toString())) {
                        r.evidence(Evidence.hint("Ähnliches Verzeichnis: '" + sibling + "' – Tippfehler oder Groß-/Kleinschreibung?"));
                    }
                }
            }
            return r.fix(FixAction.of(FixAction.OPEN_FILE, "<module>-Eintrag öffnen", "file", parentFile, "line", String.valueOf(line)));
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
                    r.cause("Der Prefix '{prefix}' gehört zu {plugin}. Das Plugin ist im Projekt nicht eingebunden und {pluginGroup} steht nicht in <pluginGroups>.");
                } else {
                    ProjectPoms.Location l = declared.get(0);
                    r.cause("Der Prefix '{prefix}' gehört zu {plugin}. Es ist nur in einem anderen Modul eingebunden, nicht in dem, in dem der Befehl lief.")
                            .evidence(Evidence.file("Plugin eingebunden in", l.pom.file.toString(), l.line));
                }
                r.fix(FixAction.of(FixAction.MANUAL, "Vollständig aufrufen: " + known + ":<version>:<goal> oder -pl auf das Modul mit dem Plugin setzen"));
                if (root != null) {
                    int line = root.lineOf("<plugins>");
                    r.fix(FixAction.of(FixAction.OPEN_FILE, "Plugin in der pom.xml eintragen",
                            "file", root.file.toString(), "line", String.valueOf(line > 0 ? line : root.lineOf("</project>"))));
                }
                return r;
            }
            r = CheckResult.info();
            for (String candidate : candidates()) {
                if (Text.similar(prefix, candidate)) {
                    r.evidence(Evidence.hint("Meinten Sie '" + candidate + "'?"));
                }
            }
            MavenSettings s = ctx.env().userSettings();
            if (s != null) {
                r.evidence(Evidence.setting("pluginGroups in settings.xml: " + s.getPluginGroups(), s.getFile().toString(), s.lineOf("<pluginGroups>")));
            }
            if (Boolean.TRUE.equals(ctx.env().getOffline()) || ctx.anyLineContains("offline")) {
                r.evidence(Evidence.hint("Offline-Modus: Prefixe werden nur aus bereits geladenen Plugin-Metadaten aufgelöst"));
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
            if (plugin == null || plugin.indexOf(':') < 0) {
                return CheckResult.notApplicable();
            }
            String artifactId = plugin.substring(plugin.indexOf(':') + 1);
            List<ProjectPoms.Location> hits = ctx.projectPoms().findText("<artifactId>" + artifactId + "</artifactId>");
            if (hits.isEmpty()) {
                return CheckResult.info().evidence(Evidence.hint("Plugin ist in keiner Projekt-POM eingetragen – kommt es aus einem Parent außerhalb des Projekts?"));
            }
            ProjectPoms.Location l = hits.get(0);
            CheckResult r = CheckResult.confirmed();
            for (ProjectPoms.Location h : hits) {
                r.evidence(Evidence.file("Plugin ohne <version>", h.pom.file.toString(), h.line));
            }
            return r.fix(FixAction.of(FixAction.INSERT_PLUGIN_VERSION, "Version für " + plugin + " einfügen",
                            "file", l.pom.file.toString(), "line", String.valueOf(l.line), "plugin", plugin))
                    .fix(FixAction.of(FixAction.OPEN_FILE, "POM an der Stelle öffnen",
                            "file", l.pom.file.toString(), "line", String.valueOf(l.line)));
        }
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
                return CheckResult.info().evidence(Evidence.hint(artifactId + " steht in keiner Projekt-POM – die Version kommt aus einem Parent"
                        + " außerhalb des Projekts oder aus Mavens Voreinstellung für das packaging."));
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
                    r.evidence(Evidence.file(artifactId + " eingetragen", h.pom.file.toString(), h.line));
                }
            }
            return r.fix(FixAction.of(FixAction.OPEN_FILE, artifactId + " in der POM öffnen",
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
                r.evidence(Evidence.file("Im maven-ear-plugin als " + type + "-Modul eingetragen", ear.file.toString(), entry));
            }
            r.evidence(Evidence.hint("In den <dependencies> von " + ear.artifactId + " steht kein " + artifactId + "."));
            ProjectPoms.Pom target = ctx.projectPoms().find(groupId, artifactId);
            if (target != null) {
                String packaging = target.effectivePackaging();
                r.var("modulePackaging", packaging)
                        .evidence(Evidence.file("Modul im Projekt: packaging " + packaging, target.file.toString(),
                                target.lineOf("<artifactId>" + artifactId + "</artifactId>")));
                if (!packaging.equals(type)) {
                    r.evidence(Evidence.hint("packaging " + packaging + " passt nicht zum Modultyp " + type + " im EAR."));
                }
            }
            int deps = ear.lineOf("<dependencies>");
            int at = deps > 0 ? deps : Math.max(1, ear.lineOf("<packaging>"));
            return r.fix(FixAction.of(FixAction.OPEN_FILE, "EAR-POM öffnen", "file", ear.file.toString(), "line", String.valueOf(at)));
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
                return r.cause("Der Code importiert {package} (Jakarta EE 9 und neuer), das Projekt bindet aber die alte Java-EE-API mit javax.*-Paketen ein.")
                        .evidence(Evidence.file("javax-API eingebunden: " + Text.truncate(l.pom.lines.get(l.line - 1).trim(), 100), l.pom.file.toString(), l.line))
                        .fix(FixAction.of(FixAction.OPEN_FILE, "API-Dependency öffnen (jakarta.platform:jakarta.jakartaee-api, provided)",
                                "file", l.pom.file.toString(), "line", String.valueOf(l.line)));
            }
            if (!pkg.startsWith("javax.")) {
                return CheckResult.notApplicable();
            }
            List<ProjectPoms.Location> jakarta = ctx.projectPoms().findText("<groupId>jakarta.");
            if (!jakarta.isEmpty()) {
                ProjectPoms.Location l = jakarta.get(0);
                return CheckResult.info()
                        .cause("Der Code importiert {package}, das Projekt bindet aber Jakarta-Artefakte ein. Ab Jakarta EE 9 heißen die Pakete jakarta.* statt javax.* – Imports umstellen oder bei einer API-Version bleiben, die noch javax.* enthält (Jakarta EE 8).")
                        .evidence(Evidence.file("Jakarta-Artefakt: " + Text.truncate(l.pom.lines.get(l.line - 1).trim(), 100), l.pom.file.toString(), l.line))
                        .fix(FixAction.of(FixAction.MANUAL, "Imports von javax.* auf jakarta.* umstellen"));
            }
            if (REMOVED_FROM_JDK.matcher(pkg).matches()) {
                return CheckResult.info()
                        .cause("{package} lag bis Java 10 im JDK und wurde mit Java 11 entfernt; jetzt braucht es die API als eigene Dependency.")
                        .evidence(Evidence.environment("Maven läuft mit Java " + (ctx.env().getRunnerJavaVersion() == null ? "?" : ctx.env().getRunnerJavaVersion())))
                        .fix(FixAction.of(FixAction.MANUAL, "API als Dependency ergänzen (z. B. javax.xml.bind:jaxb-api, javax.annotation:javax.annotation-api) oder die Java-EE-API mit Scope provided einbinden"));
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
