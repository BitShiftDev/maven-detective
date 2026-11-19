package io.github.bitshiftdev.mavendetective.core.preflight;

import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.check.ProjectPoms;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel;
import io.github.bitshiftdev.mavendetective.core.deps.MavenVersion;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.env.BuildSetup;
import io.github.bitshiftdev.mavendetective.core.env.MavenSettings;
import io.github.bitshiftdev.mavendetective.core.preflight.Preflight.Item;
import io.github.bitshiftdev.mavendetective.core.preflight.Preflight.Topic;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Die Prüfungen des Preflights ohne Netz. Jede liefert mindestens eine Zeile. */
final class PreflightChecks {

    /** Mirror aus Mavens eigener {@code conf/settings.xml}, der http-Repositories sperrt. */
    static final String HTTP_BLOCKER = "maven-default-http-blocker";
    private static final Pattern LINE_NUMBER = Pattern.compile("lineNumber: (\\d{1,9})(?!\\d)");
    private static final Pattern RELEASE_TAG = Pattern.compile("<release>\\s*([^<\\s]+)\\s*</release>");
    private static final Pattern VERSION_TAG = Pattern.compile("<version>\\s*([^<]+?)\\s*</version>");
    private static final String[] RELEASE_PROPERTIES = {
        "maven.compiler.release", "maven.compiler.target", "maven.compiler.source",
    };
    private static final int MAX_EVIDENCE = 10;

    private PreflightChecks() {
    }

    // ------------------------------------------------------------------ settings.xml

    static List<Item> settings(PreflightContext ctx) {
        List<Item> items = new ArrayList<Item>();
        Path user = ctx.userSettingsFile();
        if (ctx.setup.getUserSettings() != null && user != null && !Files.isRegularFile(user)) {
            items.add(Item.error(Topic.SETTINGS, Texts.t("preflight.settings.missing", user))
                    .evidence(Evidence.hint(Texts.t("preflight.settings.missing.hint"))));
        }
        invalid(items, user, ctx.userSettingsError(), ctx.userSettingsErrorLine());
        invalid(items, ctx.globalSettingsFile(), ctx.globalSettingsError(), ctx.globalSettingsErrorLine());

        if (ctx.offlineByConfig()) {
            Path config = ctx.project.resolve(".mvn").resolve("maven.config");
            items.add(Item.warning(Topic.SETTINGS, Texts.t("preflight.offline.config"))
                    .evidence(Evidence.setting(Texts.t("preflight.offline.config.evidence"), config.toString(), 1))
                    .fix(open(config, 1, Texts.t("preflight.open.maven.config"))));
        }
        MavenSettings offline = ctx.offlineBySettings();
        if (offline != null) {
            int line = Math.max(1, offline.lineOf("<offline>"));
            items.add(Item.warning(Topic.SETTINGS, Texts.t("preflight.offline.settings"))
                    .evidence(Evidence.setting("<offline>true</offline>", offline.getFile().toString(), line))
                    .fix(open(offline.getFile(), line, Texts.t("preflight.open.settings"))));
        }

        boolean blocks = blocksHttp(ctx.setup.getMavenVersion());
        for (MavenSettings s : ctx.settings()) {
            for (MavenSettings.Mirror m : s.getMirrors()) {
                if (HTTP_BLOCKER.equals(m.id) || m.url == null || !isRemoteHttp(m.url)) {
                    continue;
                }
                int line = Math.max(1, s.lineOf(m.url));
                String https = https(m.url);
                Item item = blocks ? Item.error(Topic.SETTINGS, Texts.t("preflight.mirror.http", m.id))
                        : Item.warning(Topic.SETTINGS, Texts.t("preflight.mirror.http.old", m.id));
                item.evidence(Evidence.setting(m.url, s.getFile().toString(), line));
                if (https != null) {
                    item.fix(FixAction.of(FixAction.REPLACE_IN_FILE, Texts.t("preflight.switch.https"),
                            "file", s.getFile().toString(), "search", m.url, "replace", https));
                }
                items.add(item.fix(open(s.getFile(), line, Texts.t("preflight.open.settings"))));
            }
        }

        if (items.isEmpty()) {
            MavenSettings s = ctx.userSettings();
            if (s == null) {
                items.add(Item.ok(Topic.SETTINGS, Texts.t("preflight.settings.none")));
            } else {
                items.add(Item.ok(Topic.SETTINGS, Texts.t("preflight.settings.ok", s.getFile(),
                        s.getMirrors().size(), s.getServerIds().size())));
            }
        }
        return items;
    }

    private static void invalid(List<Item> items, Path file, String error, int parsedLine) {
        if (error == null || file == null) {
            return;
        }
        Matcher m = LINE_NUMBER.matcher(error);
        int line = m.find() ? Integer.parseInt(m.group(1)) : parsedLine;
        items.add(Item.error(Topic.SETTINGS, Texts.t("preflight.settings.invalid", file))
                .evidence(Evidence.setting(error, file.toString(), line))
                .fix(open(file, line, Texts.t("preflight.open.settings"))));
    }

    // ------------------------------------------------------------------ JDK

    static List<Item> jdk(PreflightContext ctx) {
        List<Item> items = new ArrayList<Item>();
        BuildSetup setup = ctx.setup;
        if (setup.getJavaHome() == null) {
            Item item = Item.error(Topic.JDK, Texts.t("preflight.jdk.none"));
            for (String note : setup.getNotes()) {
                item.evidence(Evidence.hint(note));
            }
            items.add(item.fix(FixAction.of(FixAction.MANUAL, Texts.t("preflight.jdk.set.home"))));
            return items;
        }
        Path home = java.nio.file.Paths.get(setup.getJavaHome());
        String version = setup.getJavaVersion();
        int runner = BuildSetup.javaMajor(version);
        String shown = version == null ? Texts.t("unknown") : version;
        Evidence runnerEvidence = Evidence.environment(Texts.t("preflight.jdk.runner", shown, home));

        if (!Files.isRegularFile(home.resolve("bin").resolve("javac")) && !Files.isRegularFile(home.resolve("bin").resolve("javac.exe"))
                && Files.isDirectory(home.resolve("bin"))) {
            items.add(Item.error(Topic.JDK, Texts.t("preflight.jdk.jre", home))
                    .evidence(runnerEvidence)
                    .fix(FixAction.of(FixAction.SWITCH_JDK, Texts.t("preflight.jdk.switch.any"), "required",
                            runner > 0 ? String.valueOf(runner) : null)));
        }

        boolean toolchains = !ctx.findText("maven-toolchains-plugin").isEmpty();
        Requirement release = requiredRelease(ctx);
        if (release != null && runner > 0 && runner < release.version) {
            Item item = toolchains
                    ? Item.info(Topic.JDK, Texts.t("preflight.jdk.old.toolchains", runner, release.version))
                    : Item.error(Topic.JDK, Texts.t("preflight.jdk.old", runner, release.version));
            item.evidence(runnerEvidence).evidence(release.evidence());
            if (!toolchains) {
                item.fix(FixAction.of(FixAction.SWITCH_JDK, Texts.t("check.jdk.switch", release.version),
                        "required", String.valueOf(release.version)));
            }
            items.add(item.fix(release.open()));
        }
        if (toolchains) {
            Path file = ctx.options.userHome == null ? null : ctx.options.userHome.resolve(".m2").resolve("toolchains.xml");
            ProjectPoms.Location where = ctx.findText("maven-toolchains-plugin").get(0);
            if (file == null || !Files.isRegularFile(file)) {
                items.add(Item.error(Topic.JDK, Texts.t("preflight.toolchains.missing"))
                        .evidence(Evidence.file(Texts.t("preflight.toolchains.used"), where.pom.file.toString(), where.line))
                        .fix(FixAction.of(FixAction.MANUAL, Texts.t("preflight.toolchains.create"))));
            } else {
                items.add(Item.info(Topic.JDK, Texts.t("preflight.toolchains.ok"))
                        .evidence(Evidence.file(Texts.t("preflight.toolchains.used"), where.pom.file.toString(), where.line))
                        .fix(open(file, 1, Texts.t("check.jdk.toolchains.open"))));
            }
        }
        for (Requirement r : enforcerRules(ctx, "requireJavaVersion")) {
            if (version != null && !VersionRange.contains(javaRange(r.range), normalizeJava(version))) {
                items.add(Item.error(Topic.JDK, Texts.t("preflight.jdk.enforcer", shown, r.range))
                        .evidence(runnerEvidence).evidence(r.evidence()).fix(r.open()));
            }
        }
        if (items.isEmpty()) {
            items.add(Item.ok(Topic.JDK, release == null ? Texts.t("preflight.jdk.ok", shown, home)
                    : Texts.t("preflight.jdk.ok.release", shown, home, release.version)));
        }
        return items;
    }

    private static final java.util.regex.Pattern OLD_JAVA_BOUND =
            java.util.regex.Pattern.compile("(?<pre>^|[\\[(,])\\s*(?<old>[5-8])\\s*(?=$|[\\]),])");

    /**
     * Grenzen wie {@code 8} meint der Enforcer ({@code RequireJavaVersion.setVersion}) als {@code 1.8} – sonst
     * läge Java {@code 1.8.0_392} unter „8“. Für 5 bis 7 ebenso, das schadet nie.
     */
    static String javaRange(String range) {
        return range == null ? null : OLD_JAVA_BOUND.matcher(range.trim()).replaceAll("${pre}1.${old}");
    }

    /** "1.8.0_392" → "1.8.0-392" wie der Enforcer, sonst unverändert. */
    static String normalizeJava(String version) {
        return version.replace('_', '-');
    }

    /** Höchste verlangte Java-Version über alle Module, mit Fundstelle. */
    static Requirement requiredRelease(PreflightContext ctx) {
        Requirement best = null;
        for (PomFile pom : ctx.poms()) {
            ctx.options.cancellation.check();
            EffectiveModel model = ctx.model(pom);
            for (String name : RELEASE_PROPERTIES) {
                EffectiveModel.Interpolated v = model.interpolate("${" + name + "}");
                if (!v.isResolved()) {
                    continue;
                }
                int feature = BuildSetup.javaMajor(v.value);
                if (feature > 0 && (best == null || feature > best.version)) {
                    EffectiveModel.PropertyOrigin origin = literal(v);
                    best = new Requirement(feature, "<" + name + ">" + v.value,
                            origin == null || origin.pom == null ? pom.getFile() : origin.pom.getFile(),
                            origin == null || origin.pom == null ? 1 : origin.line);
                }
                break;   // release schlägt target/source
            }
        }
        for (ProjectPoms.Pom pom : ctx.modulePoms()) {
            List<String> lines = ctx.code(pom, true);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = RELEASE_TAG.matcher(lines.get(i));
                if (!m.find()) {
                    continue;
                }
                String value = m.group(1);
                if (value.contains("${")) {
                    PomFile f = ctx.locator().load(pom.file);
                    value = f == null ? value : ctx.model(f).interpolate(value).value;
                }
                int feature = BuildSetup.javaMajor(value);
                if (feature > 0 && (best == null || feature > best.version)) {
                    best = new Requirement(feature, lines.get(i).trim(), pom.file, i + 1);
                }
            }
        }
        return best;
    }

    /** Die Property, die den Wert wörtlich hält (bei {@code ${java.version}} die zweite). */
    private static EffectiveModel.PropertyOrigin literal(EffectiveModel.Interpolated v) {
        EffectiveModel.PropertyOrigin found = null;
        for (EffectiveModel.PropertyOrigin o : v.used) {
            if (o.pom != null) {
                found = o;
                if (o.value != null && !o.value.contains("${")) {
                    return o;
                }
            }
        }
        return found;
    }

    /** Versionsbereiche aus Enforcer-Regeln ({@code requireJavaVersion}, {@code requireMavenVersion}). */
    static List<Requirement> enforcerRules(PreflightContext ctx, String rule) {
        List<Requirement> out = new ArrayList<Requirement>();
        for (ProjectPoms.Location l : ctx.findText("<" + rule + ">")) {
            List<String> lines = ctx.code(l.pom, true);
            for (int i = l.line - 1; i < lines.size() && i < l.line + 8; i++) {
                if (lines.get(i).contains("</" + rule + ">")) {
                    break;
                }
                Matcher m = VERSION_TAG.matcher(lines.get(i));
                if (m.find()) {
                    String range = m.group(1);
                    if (range.contains("${")) {
                        PomFile f = ctx.locator().load(l.pom.file);
                        range = f == null ? range : ctx.model(f).interpolate(range).value;
                    }
                    if (!range.contains("${")) {
                        out.add(new Requirement(-1, lines.get(i).trim(), l.pom.file, i + 1).range(range));
                    }
                    break;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Maven

    static List<Item> maven(PreflightContext ctx) {
        List<Item> items = new ArrayList<Item>();
        BuildSetup setup = ctx.setup;
        String version = setup.getMavenVersion();
        if (version == null && setup.getMavenSource() == null) {
            Item item = Item.error(Topic.MAVEN, Texts.t("preflight.maven.none"));
            for (String note : setup.getNotes()) {
                item.evidence(Evidence.hint(note));
            }
            items.add(item.fix(FixAction.of(FixAction.MANUAL, Texts.t("preflight.maven.install"))));
            return items;
        }
        String source = setup.getMavenSource() == null ? "" : " (" + setup.getMavenSource() + ")";
        Path wrapperProps = ctx.project.resolve(".mvn").resolve("wrapper").resolve("maven-wrapper.properties");
        if (Files.isRegularFile(wrapperProps) && !Files.isRegularFile(ctx.project.resolve("mvnw"))
                && !Files.isRegularFile(ctx.project.resolve("mvnw.cmd"))) {
            items.add(Item.warning(Topic.MAVEN, Texts.t("preflight.maven.wrapper.script"))
                    .evidence(Evidence.file(Texts.t("preflight.maven.wrapper.props"), wrapperProps.toString(), 1))
                    .fix(FixAction.of(FixAction.MANUAL, Texts.t("preflight.maven.wrapper.generate"))));
        }
        if (version == null) {
            items.add(Item.warning(Topic.MAVEN, Texts.t("preflight.maven.unknown", source.trim())));
        } else {
            int runner = BuildSetup.javaMajor(setup.getJavaVersion());
            if (version.startsWith("4") && runner > 0 && runner < 17) {
                items.add(Item.error(Topic.MAVEN, Texts.t("preflight.maven.java17", version, runner))
                        .fix(FixAction.of(FixAction.SWITCH_JDK, Texts.t("check.jdk.switch", 17), "required", "17")));
            }
            if (MavenVersion.compare(version, "3.6.3") < 0) {
                items.add(Item.warning(Topic.MAVEN, Texts.t("preflight.maven.old", version)));
            }
            for (Requirement r : enforcerRules(ctx, "requireMavenVersion")) {
                if (!VersionRange.contains(r.range, version)) {
                    items.add(Item.error(Topic.MAVEN, Texts.t("preflight.maven.enforcer", version, r.range))
                            .evidence(r.evidence()).fix(r.open()));
                }
            }
        }
        if (items.isEmpty()) {
            items.add(Item.ok(Topic.MAVEN, Texts.t("preflight.maven.ok", version, source.trim())));
        }
        return items;
    }

    /** Maven blockiert http-Repositories seit 3.8.1; unbekannte Version zählt als neu. */
    static boolean blocksHttp(String mavenVersion) {
        return mavenVersion == null || MavenVersion.compare(mavenVersion, "3.8.1") >= 0;
    }

    /** http-Repository, das Maven sperren würde (wie {@code external:http:*}, siehe {@link MavenSettings#isExternalHttp}). */
    static boolean isRemoteHttp(String url) {
        return MavenSettings.isExternalHttp(url);
    }

    /** Dieselbe URL mit https; {@code null}, wenn sie nicht mit {@code http://} beginnt (etwa {@code dav:http://}). */
    static String https(String url) {
        return url.regionMatches(true, 0, "http://", 0, "http://".length())
                ? "https://" + url.substring("http://".length()) : null;
    }

    // ------------------------------------------------------------------ Projekt

    static List<Item> project(PreflightContext ctx) {
        List<Item> items = new ArrayList<Item>();
        if (!ctx.hasRootPom()) {
            items.add(Item.error(Topic.PROJECT, Texts.t("preflight.project.no.pom", ctx.project)));
            return items;
        }
        List<ProjectPoms.Pom> all = ctx.modulePoms();
        for (ProjectPoms.Pom p : all) {
            if (p.parseable) {
                continue;
            }
            String message = Texts.t("unknown");
            int line = 1;
            try {
                PomFile.read(p.file);
            } catch (org.xml.sax.SAXParseException e) {
                message = e.getMessage();
                line = Math.max(1, e.getLineNumber());
            } catch (Exception e) {
                message = String.valueOf(e.getMessage());
            }
            items.add(Item.error(Topic.PROJECT, Texts.t("preflight.project.invalid", ctx.project.relativize(p.file)))
                    .evidence(Evidence.file(message, p.file.toString(), line))
                    .fix(open(p.file, line, Texts.t("check.open.pom"))));
        }
        for (PomFile pom : ctx.poms()) {
            Path dir = pom.getFile().getParent();
            for (String module : pom.getModules()) {
                Path target = dir.resolve(module).normalize();
                Path file = Files.isDirectory(target) ? target.resolve("pom.xml") : target;
                if (Files.isRegularFile(file)) {
                    continue;
                }
                int line = lineOf(ctx, pom.getFile(), "<module>" + module + "</module>");
                items.add(Item.error(Topic.PROJECT, Texts.t("preflight.project.module", module, ctx.project.relativize(pom.getFile())))
                        .evidence(Evidence.file(Texts.t("preflight.project.module.expected", file), pom.getFile().toString(), line))
                        .fix(open(pom.getFile(), line, Texts.t("check.open.pom"))));
            }
            versionless(ctx, pom, items);
            String rel = pom.getParentRelativePath();
            if (pom.hasParent() && rel != null && !rel.isEmpty()) {
                Path candidate = dir.resolve(rel).normalize();
                PomFile there = ctx.locator().load(candidate);
                if (there != null && !(java.util.Objects.equals(pom.getParentArtifactId(), there.getArtifactId())
                        && java.util.Objects.equals(pom.getParentGroupId(), there.getGroupId()))) {
                    items.add(Item.warning(Topic.PROJECT, Texts.t("preflight.project.relative.path",
                                    ctx.project.relativize(pom.getFile()), rel, there.gav(), pom.parentGav()))
                            .evidence(Evidence.file("<relativePath>" + rel + "</relativePath>", pom.getFile().toString(),
                                    Math.max(1, pom.getParentLine())))
                            .fix(open(pom.getFile(), Math.max(1, pom.getParentLine()), Texts.t("check.open.pom"))));
                }
            }
        }

        List<String> missing = new ArrayList<String>();
        for (String gav : ctx.missingPoms()) {
            if (!gav.contains("${") && !gav.contains("null")) {
                missing.add(gav);
            }
        }
        if (!missing.isEmpty()) {
            boolean offline = ctx.offline();
            Item item = offline
                    ? Item.error(Topic.PROJECT, Texts.t("preflight.project.missing.offline", missing.size()))
                    : Item.info(Topic.PROJECT, Texts.t("preflight.project.missing", missing.size()));
            for (int i = 0; i < missing.size() && i < MAX_EVIDENCE; i++) {
                item.evidence(Evidence.hint(missing.get(i)));
            }
            if (offline) {
                item.fix(FixAction.of(FixAction.MANUAL, Texts.t("preflight.project.missing.online")));
            }
            items.add(item);
        }
        if (items.isEmpty()) {
            items.add(Item.ok(Topic.PROJECT, Texts.t("preflight.project.ok", all.size())));
        }
        return items;
    }

    /**
     * Dependencies ohne Version, die auch kein dependencyManagement verwaltet – Maven bricht beim
     * Einlesen ab ("dependencies.dependency.version is missing"). Nur wenn Parents und BOMs vollständig
     * vorliegen, sonst könnte die Version von dort kommen.
     */
    private static void versionless(PreflightContext ctx, PomFile pom, List<Item> items) {
        EffectiveModel model = ctx.model(pom);
        List<PomFile> chain = model.getChain();
        if (!model.getMissingImports().isEmpty() || chain.isEmpty() || chain.get(chain.size() - 1).hasParent()) {
            return;
        }
        for (PomFile.Dep d : pom.getDependencies()) {
            if (d.getVersion() != null || d.getGroupId() == null || d.getArtifactId() == null
                    || model.managed(d.getGroupId(), d.getArtifactId()) != null) {
                continue;
            }
            items.add(Item.error(Topic.PROJECT, Texts.t("preflight.project.no.version", d.ga(), ctx.project.relativize(pom.getFile())))
                    .evidence(Evidence.file(Texts.t("preflight.project.no.version.evidence"), pom.getFile().toString(), Math.max(1, d.getLine())))
                    .fix(open(pom.getFile(), d.getLine(), Texts.t("check.open.pom"))));
        }
    }

    private static int lineOf(PreflightContext ctx, Path file, String needle) {
        ProjectPoms.Pom p = ctx.projectPoms().read(file);
        int line = p == null ? -1 : p.lineOf(needle);
        return Math.max(1, line);
    }

    // ------------------------------------------------------------------ lokales Repository

    static List<Item> localRepository(PreflightContext ctx) {
        List<Item> items = new ArrayList<Item>();
        Path repo = ctx.localRepository();
        if (repo == null) {
            items.add(Item.warning(Topic.LOCAL_REPOSITORY, Texts.t("preflight.repo.unknown")));
            return items;
        }
        if (!Files.isDirectory(repo)) {
            items.add(Item.info(Topic.LOCAL_REPOSITORY, Texts.t("preflight.repo.new", repo)));
            return items;
        }
        if (!Files.isWritable(repo)) {
            items.add(Item.error(Topic.LOCAL_REPOSITORY, Texts.t("preflight.repo.readonly", repo))
                    .fix(FixAction.of(FixAction.MANUAL, Texts.t("preflight.repo.readonly.fix"))));
        }
        Set<String> failed = new LinkedHashSet<String>();
        if (ctx.hasRootPom()) {
            for (String gav : ctx.missingPoms()) {
                failed.addAll(lastUpdated(repo, gav, "pom"));
            }
            for (PomFile pom : ctx.poms()) {
                ctx.options.cancellation.check();
                EffectiveModel model = ctx.model(pom);
                for (EffectiveModel.Declared d : model.getDependencies().values()) {
                    String version = d.dep.getVersion();
                    if (version == null) {
                        EffectiveModel.Managed managed = model.managed(d.dep.getGroupId(), d.dep.getArtifactId());
                        version = managed == null ? null : managed.version().value;
                    } else {
                        version = model.interpolate(version).value;
                    }
                    if (version == null || version.contains("${") || d.dep.getGroupId() == null || d.dep.getGroupId().contains("${")) {
                        continue;
                    }
                    String ext = "pom".equals(d.dep.getType()) ? "pom" : "jar";
                    failed.addAll(lastUpdated(repo, d.dep.getGroupId() + ":" + d.dep.getArtifactId() + ":" + version, ext));
                }
            }
        }
        if (!failed.isEmpty()) {
            Item item = Item.warning(Topic.LOCAL_REPOSITORY, Texts.t("preflight.repo.last.updated", failed.size()));
            StringBuilder files = new StringBuilder();
            int n = 0;
            for (String f : failed) {
                if (n++ < MAX_EVIDENCE) {
                    item.evidence(Evidence.file(Texts.t("check.cached.evidence"), f, -1));
                }
                files.append(files.length() == 0 ? "" : "\n").append(f);
            }
            items.add(item.fix(FixAction.of(FixAction.DELETE_FILES, Texts.t("check.cached.delete"),
                    "files", files.toString(), "args", "-U")));
        }
        if (items.isEmpty()) {
            items.add(Item.ok(Topic.LOCAL_REPOSITORY, Texts.t("preflight.repo.ok", repo)));
        }
        return items;
    }

    /**
     * {@code *.lastUpdated}-Dateien eines Artifacts, dessen Datei fehlt: Maven hat es schon einmal
     * vergeblich gesucht und versucht es bis zum nächsten Update-Intervall nicht erneut.
     */
    static List<String> lastUpdated(Path repo, String gav, String extension) {
        List<String> out = new ArrayList<String>();
        String[] p = gav.split(":");
        if (p.length != 3) {
            return out;
        }
        Path dir = repo;
        for (String segment : p[0].split("\\.")) {
            dir = dir.resolve(segment);
        }
        dir = dir.resolve(p[1]).resolve(p[2]);
        if (!Files.isDirectory(dir) || Files.isRegularFile(dir.resolve(p[1] + "-" + p[2] + "." + extension))) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.lastUpdated")) {
            for (Path f : ds) {
                if (f.getFileName().toString().startsWith(p[1] + "-" + p[2])) {
                    out.add(f.toString());
                }
            }
        } catch (IOException ignored) {
            // nicht lesbar → nichts gefunden
        }
        return out;
    }

    // ------------------------------------------------------------------ Hilfen

    static FixAction open(Path file, int line, String label) {
        return FixAction.of(FixAction.OPEN_FILE, label, "file", file.toString(), "line", String.valueOf(Math.max(1, line)));
    }

    /** Eine Anforderung aus dem Projekt (Java-Release, Enforcer-Bereich) mit Fundstelle. */
    static final class Requirement {
        final int version;
        final String text;
        final Path file;
        final int line;
        String range;

        Requirement(int version, String text, Path file, int line) {
            this.version = version;
            this.text = text;
            this.file = file;
            this.line = line;
        }

        Requirement range(String r) {
            this.range = r;
            return this;
        }

        Evidence evidence() {
            return Evidence.file(Texts.t("preflight.requirement", text), file.toString(), line);
        }

        FixAction open() {
            return PreflightChecks.open(file, line, Texts.t("check.open.pom"));
        }
    }
}
