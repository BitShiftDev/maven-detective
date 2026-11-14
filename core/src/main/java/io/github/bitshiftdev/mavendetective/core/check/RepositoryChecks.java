package io.github.bitshiftdev.mavendetective.core.check;

import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.env.MavenSettings;
import io.github.bitshiftdev.mavendetective.core.model.RepositoryRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Prüfungen zu Repositories, Zugangsdaten und Dateien im lokalen Repository. */
final class RepositoryChecks {

    private RepositoryChecks() {
    }

    // ------------------------------------------------------------------ server-id

    static final class ServerId implements Check {
        @Override
        public String id() { return "server-id"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            String repoId = ctx.var("repoId");
            String repoUrl = ctx.var("repoUrl");
            if (repoId == null) {
                for (RepositoryRequest r : ctx.failure().getRepositoryRequests()) {
                    if (r.isFailed() && r.getFailure().matches("(?s).*\\b40[13]\\b.*")) {
                        repoId = r.getRepositoryId();
                        repoUrl = r.getRepositoryUrl();
                        break;
                    }
                }
            }
            if (repoId == null) {
                return CheckResult.notApplicable();
            }
            String status = ctx.var("status");
            CheckResult base;
            if (Text.isMavenCentral(repoUrl)) {
                return CheckResult.info().var("repoId", repoId).var("repoUrl", repoUrl)
                        .cause(Texts.t("check.central.denied.cause"))
                        .fix(FixAction.of(FixAction.MANUAL, Texts.t("check.central.denied.fix")));
            }
            MavenSettings user = ctx.env().userSettings();
            MavenSettings global = ctx.env().globalSettings();
            if (user == null && global == null) {
                Path expected = ctx.env().getUserSettingsFile();
                return CheckResult.confirmed().var("repoId", repoId)
                        .cause(Texts.t("check.server.no.settings.cause", expected))
                        .evidence(Evidence.file(Texts.t("check.file.missing"), String.valueOf(expected), -1))
                        .fix(FixAction.of(FixAction.MANUAL, Texts.t("check.server.create.settings", repoId)));
            }
            MavenSettings settings = user != null ? user : global;
            for (MavenSettings s : new MavenSettings[] {user, global}) {
                if (s != null && s.getServerIds().contains(repoId)) {
                    int line = s.lineOf("<id>" + repoId + "</id>");
                    base = CheckResult.info().var("repoId", repoId)
                            .evidence(Evidence.setting(Texts.t("check.server.present", repoId), s.getFile().toString(), line))
                            .fix(FixAction.of(FixAction.OPEN_FILE, Texts.t("check.open.settings"),
                                    "file", s.getFile().toString(), "line", String.valueOf(line)));
                    if ("403".equals(status)) {
                        return base.cause(Texts.t("check.server.forbidden.cause"));
                    }
                    return base.cause(Texts.t("check.server.rejected.cause"));
                }
            }
            int line = settings.lineOf("<servers>");
            if (line < 0) {
                line = settings.lineOf("</settings>");
            }
            CheckResult r = CheckResult.confirmed().var("repoId", repoId)
                    .cause(Texts.t("check.server.missing.cause"))
                    .evidence(Evidence.setting(Texts.t("check.server.ids", settings.getServerIds()), settings.getFile().toString(), line));
            for (String id : settings.getServerIds()) {
                if (Text.similar(id, repoId)) {
                    r.evidence(Evidence.hint(Texts.t("check.server.similar", id)));
                }
            }
            MavenSettings.Mirror mirror = settings.mirrorFor(repoId, repoUrl);
            if (mirror != null && !repoId.equals(mirror.id)) {
                r.evidence(Evidence.hint(Texts.t("check.server.mirror", mirror.id, repoId)));
            }
            return r.fix(FixAction.of(FixAction.OPEN_FILE, Texts.t("check.open.settings"),
                    "file", settings.getFile().toString(), "line", String.valueOf(line)));
        }
    }

    // ------------------------------------------------------------------ http-repository

    static final class HttpRepository implements Check {
        @Override
        public String id() { return "http-repository"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            String url = ctx.var("repoUrl");
            if (url == null || !url.startsWith("http://")) {
                return CheckResult.notApplicable();
            }
            String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            String https = "https://" + trimmed.substring("http://".length());
            List<ProjectPoms.Location> hits = ctx.projectPoms().findText(trimmed);
            if (!hits.isEmpty()) {
                ProjectPoms.Location loc = hits.get(0);
                CheckResult r = CheckResult.confirmed()
                        .var("file", loc.pom.file.toString()).var("line", String.valueOf(loc.line))
                        .cause(Texts.t("check.http.pom.cause"));
                for (ProjectPoms.Location l : hits) {
                    r.evidence(Evidence.file(Texts.t("check.http.pom.evidence"), l.pom.file.toString(), l.line));
                }
                return r.fix(FixAction.of(FixAction.REPLACE_URL, Texts.t("check.http.switch"),
                                "file", loc.pom.file.toString(), "search", trimmed, "replace", https))
                        .fix(FixAction.of(FixAction.OPEN_FILE, Texts.t("check.open.pom"),
                                "file", loc.pom.file.toString(), "line", String.valueOf(loc.line)));
            }
            MavenSettings s = ctx.env().userSettings();
            if (s != null && s.lineOf(trimmed) > 0) {
                int line = s.lineOf(trimmed);
                return CheckResult.confirmed()
                        .cause(Texts.t("check.http.settings.cause"))
                        .evidence(Evidence.setting(Texts.t("check.http.settings.evidence"), s.getFile().toString(), line))
                        .fix(FixAction.of(FixAction.REPLACE_IN_FILE, Texts.t("check.http.switch"),
                                "file", s.getFile().toString(), "search", trimmed, "replace", https));
            }
            CheckResult r = CheckResult.info()
                    .cause(Texts.t("check.http.transitive.cause"))
                    .evidence(Evidence.hint(Texts.t("check.http.transitive.evidence")));
            if (s != null) {
                r.fix(FixAction.of(FixAction.OPEN_FILE, Texts.t("check.http.mirror", ctx.var("repoId")),
                        "file", s.getFile().toString(), "line", String.valueOf(Math.max(1, s.lineOf("<mirrors>")))));
            } else {
                r.fix(FixAction.of(FixAction.MANUAL, Texts.t("check.http.mirror", ctx.var("repoId"))));
            }
            return r;
        }
    }

    // ------------------------------------------------------------------ corrupt-jar

    static final class CorruptJar implements Check {
        /** Nur am Anfang eines Pfads ansetzen, sonst wächst {@code find()} quadratisch mit langen Zeilen. */
        private static final Pattern PATH = Pattern.compile("(?<![^\\s;:'\"=(\\[,])((?:[A-Za-z]:)?[\\\\/][^\\s;:'\"]*\\.(?:jar|zip|war))");

        @Override
        public String id() { return "corrupt-jar"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            Path file = null;
            if (ctx.var("file") != null) {
                file = Text.localPath(ctx.var("file"));
            } else {
                Matcher m = ctx.find(PATH);
                if (m != null) {
                    file = Text.localPath(m.group(1));
                } else if (ctx.var("artifact") != null && ctx.env().getLocalRepository() != null) {
                    Coordinates c = Coordinates.parse(ctx.var("artifact"));
                    if (c != null) {
                        file = c.directoryIn(ctx.env().getLocalRepository()).resolve(c.fileName());
                    }
                }
            }
            if (file == null) {
                return CheckResult.notApplicable();
            }
            if (!Files.isRegularFile(file)) {
                return CheckResult.info()
                        .evidence(Evidence.file(Texts.t("check.jar.gone"), file.toString(), -1))
                        .fix(FixAction.of(FixAction.REBUILD, Texts.t("fix.rebuild")));
            }
            CheckResult r;
            String zipError = openZip(file);
            String checksum = compareSha1(file);
            if (zipError == null && checksum == null) {
                return CheckResult.info()
                        .evidence(Evidence.file(Texts.t("check.jar.ok"), file.toString(), -1));
            }
            r = CheckResult.confirmed().var("file", file.toString())
                    .cause(Texts.t("check.jar.cause"));
            if (zipError != null) {
                r.evidence(Evidence.file(Texts.t("check.jar.zip", zipError), file.toString(), -1));
            }
            if (checksum != null) {
                r.evidence(Evidence.file(checksum, file.toString(), -1));
            }
            return r.fix(FixAction.of(FixAction.DELETE_FILES, Texts.t("check.jar.delete"),
                    "files", file.toString(), "args", ""));
        }

        private static String openZip(Path file) {
            ZipFile zip = null;
            try {
                zip = new ZipFile(file.toFile());
                return null;
            } catch (ZipException e) {
                return e.getMessage() == null ? "ZipException" : e.getMessage();
            } catch (IOException e) {
                return e.getMessage();
            } finally {
                if (zip != null) {
                    try {
                        zip.close();
                    } catch (IOException ignored) {
                        // egal
                    }
                }
            }
        }

        /** Vergleicht mit der .sha1-Datei, die Maven beim Download ablegt (falls vorhanden). Lokal, ohne Netz. */
        private static String compareSha1(Path file) {
            Path sha1 = file.resolveSibling(file.getFileName() + ".sha1");
            if (!Files.isRegularFile(sha1)) {
                return null;
            }
            try {
                String expected = new String(Files.readAllBytes(sha1), StandardCharsets.US_ASCII).trim().split("\\s+")[0].toLowerCase();
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                InputStream in = Files.newInputStream(file);
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        md.update(buf, 0, n);
                    }
                } finally {
                    in.close();
                }
                StringBuilder actual = new StringBuilder();
                for (byte b : md.digest()) {
                    actual.append(String.format("%02x", b & 0xff));
                }
                return expected.equals(actual.toString()) ? null
                        : Texts.t("check.jar.sha1", expected, actual);
            } catch (Exception e) {
                return null;
            }
        }
    }

    static List<Check> all() {
        List<Check> l = new ArrayList<Check>();
        l.add(new ServerId());
        l.add(new HttpRepository());
        l.add(new CorruptJar());
        return l;
    }
}
