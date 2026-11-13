package io.github.bitshiftdev.mavendetective.core.preflight;

import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.check.ProjectPoms;
import io.github.bitshiftdev.mavendetective.core.check.Text;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.env.MavenSettings;
import io.github.bitshiftdev.mavendetective.core.preflight.Preflight.Item;
import io.github.bitshiftdev.mavendetective.core.preflight.Preflight.Topic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remote-Repositories des Builds: Maven Central plus die {@code <repositories>} und
 * {@code <pluginRepositories>} der Projekt-POMs, nach den Mirrors der settings.xml umgelenkt. Ohne
 * Netz wird nur geprüft, ob Maven ein Repository sperrt (http); mit Netz zusätzlich, ob es antwortet
 * und ob fehlende Parents/BOMs dort liegen.
 */
final class RemoteRepositories {

    static final String CENTRAL_ID = "central";
    static final String CENTRAL_URL = "https://repo.maven.apache.org/maven2";
    private static final Pattern ID = Pattern.compile("<id>\\s*([^<]+?)\\s*</id>");
    private static final Pattern URL = Pattern.compile("<url>\\s*([^<]+?)\\s*</url>");
    private static final int MAX_MISSING_PROBES = 10;

    /** Ein Repository, wie es im Projekt steht, und wohin Maven tatsächlich geht. */
    static final class Repo {
        final String id;
        final String url;
        /** POM, in der es steht; {@code null} bei Central. */
        final ProjectPoms.Pom pom;
        final int line;
        MavenSettings.Mirror mirror;
        MavenSettings mirrorSettings;

        Repo(String id, String url, ProjectPoms.Pom pom, int line) {
            this.id = id;
            this.url = url;
            this.pom = pom;
            this.line = line;
        }

        String effectiveId() {
            return mirror != null ? mirror.id : id;
        }

        String effectiveUrl() {
            return mirror != null && mirror.url != null ? mirror.url : url;
        }

        String label() {
            return mirror == null ? id : Texts.t("preflight.remote.via.mirror", id, mirror.id);
        }
    }

    private RemoteRepositories() {
    }

    static List<Repo> collect(PreflightContext ctx) {
        Map<String, Repo> repos = new LinkedHashMap<String, Repo>();
        repos.put(CENTRAL_ID, new Repo(CENTRAL_ID, CENTRAL_URL, null, -1));
        if (ctx.hasRootPom()) {
            for (ProjectPoms.Pom pom : ctx.projectPoms().all()) {
                for (Repo r : declared(pom)) {
                    Repo known = repos.get(r.id);
                    if (known == null || known.pom == null) {
                        repos.put(r.id, r);
                    }
                }
            }
        }
        for (Repo r : repos.values()) {
            for (MavenSettings s : ctx.settings()) {
                MavenSettings.Mirror m = s.mirrorFor(r.id, r.url);
                if (m != null) {
                    r.mirror = m;
                    r.mirrorSettings = s;
                    break;
                }
            }
        }
        return new ArrayList<Repo>(repos.values());
    }

    /** {@code <repository>}/{@code <pluginRepository>} einer POM mit Zeile der URL. */
    static List<Repo> declared(ProjectPoms.Pom pom) {
        List<Repo> out = new ArrayList<Repo>();
        boolean inList = false;
        boolean inRepo = false;
        String id = null;
        String url = null;
        int urlLine = -1;
        for (int i = 0; i < pom.lines.size(); i++) {
            String line = pom.lines.get(i);
            if (line.contains("<repositories>") || line.contains("<pluginRepositories>")) {
                inList = true;
            }
            if (inList && (line.contains("<repository>") || line.contains("<pluginRepository>"))) {
                inRepo = true;
                id = null;
                url = null;
                urlLine = -1;
            }
            if (inRepo) {
                Matcher m = ID.matcher(line);
                if (id == null && m.find()) {
                    id = m.group(1);
                }
                m = URL.matcher(line);
                if (url == null && m.find()) {
                    url = m.group(1);
                    urlLine = i + 1;
                }
                if (line.contains("</repository>") || line.contains("</pluginRepository>")) {
                    inRepo = false;
                    if (id != null && url != null && !url.contains("${")) {
                        out.add(new Repo(id, url, pom, urlLine));
                    }
                }
            }
            if (line.contains("</repositories>") || line.contains("</pluginRepositories>")) {
                inList = false;
            }
        }
        return out;
    }

    static List<Item> check(PreflightContext ctx) {
        List<Item> items = new ArrayList<Item>();
        List<Repo> repos = collect(ctx);
        boolean blocks = PreflightChecks.blocksHttp(ctx.setup.getMavenVersion());
        List<Repo> usable = new ArrayList<Repo>();
        for (Repo r : repos) {
            Item blocked = blocked(r, blocks);
            if (blocked != null) {
                items.add(blocked);
            } else {
                usable.add(r);
            }
        }
        if (!ctx.options.network) {
            if (ctx.offline()) {
                items.add(Item.info(Topic.REPOSITORY, Texts.t("preflight.remote.offline")));
                return items;
            }
            Item item = Item.info(Topic.REPOSITORY, Texts.t("preflight.remote.not.checked", usable.size()));
            for (Repo r : usable) {
                item.evidence(Evidence.hint(r.label() + " → " + r.effectiveUrl()));
            }
            items.add(item);
            return items;
        }
        RepositoryProbe probe = ctx.options.probe != null ? ctx.options.probe : RepositoryProbe.HTTP;
        Map<String, RepositoryProbe.Response> reachable = new LinkedHashMap<String, RepositoryProbe.Response>();
        for (Repo r : usable) {
            ctx.options.cancellation.check();
            String url = r.effectiveUrl();
            if (reachable.containsKey(url)) {
                continue;
            }
            RepositoryProbe.Response response = probe.head(slash(url), proxyFor(ctx, url), ctx.options.timeoutMillis);
            reachable.put(url, response);
            items.add(answer(ctx, r, response));
        }
        items.addAll(missingPoms(ctx, usable, reachable, probe));
        return items;
    }

    /** Zeile für ein Repository, das Maven gar nicht erst anfragt (http seit 3.8.1). */
    private static Item blocked(Repo r, boolean blocks) {
        boolean blocker = r.mirror != null && PreflightChecks.HTTP_BLOCKER.equals(r.mirror.id);
        boolean http = PreflightChecks.isRemoteHttp(r.effectiveUrl());
        if (!blocker && !(http && blocks)) {
            if (http) {
                return Item.warning(Topic.REPOSITORY, Texts.t("preflight.remote.http.old", r.label()));
            }
            return null;
        }
        Item item = Item.error(Topic.REPOSITORY, Texts.t("preflight.remote.http", r.label(), r.url));
        if (r.pom != null && PreflightChecks.isRemoteHttp(r.url)) {
            String https = "https://" + r.url.substring("http://".length());
            item.evidence(Evidence.file(Texts.t("check.http.pom.evidence"), r.pom.file.toString(), r.line))
                    .fix(FixAction.of(FixAction.REPLACE_IN_FILE, Texts.t("preflight.switch.https"),
                            "file", r.pom.file.toString(), "search", r.url, "replace", https))
                    .fix(PreflightChecks.open(r.pom.file, r.line, Texts.t("check.open.pom")));
        } else if (r.mirrorSettings != null && !blocker) {
            int line = Math.max(1, r.mirrorSettings.lineOf(r.mirror.url));
            item.evidence(Evidence.setting(Texts.t("check.http.settings.evidence"), r.mirrorSettings.getFile().toString(), line))
                    .fix(PreflightChecks.open(r.mirrorSettings.getFile(), line, Texts.t("preflight.open.settings")));
        }
        return item;
    }

    private static Item answer(PreflightContext ctx, Repo r, RepositoryProbe.Response response) {
        String url = r.effectiveUrl();
        String host = Text.host(url);
        if (response.failure != null) {
            String key;
            switch (response.failure) {
                case DNS: key = "preflight.remote.dns"; break;
                case TIMEOUT: key = "preflight.remote.timeout"; break;
                case CERTIFICATE: key = "preflight.remote.certificate"; break;
                case REFUSED: key = "preflight.remote.refused"; break;
                default: key = "preflight.remote.failed"; break;
            }
            Item item = Item.error(Topic.REPOSITORY, Texts.t(key, r.label(), host))
                    .evidence(Evidence.hint(url));
            if (response.message != null) {
                item.evidence(Evidence.log(Text.truncate(response.message, 200)));
            }
            if (response.failure == RepositoryProbe.Failure.CERTIFICATE) {
                item.evidence(Evidence.hint(Texts.t("preflight.remote.certificate.jvm")));
            }
            return item;
        }
        int code = response.code;
        if (code == 401 || code == 403) {
            MavenSettings withServer = serverFor(ctx, r.effectiveId());
            if (withServer != null) {
                int line = Math.max(1, withServer.lineOf("<id>" + r.effectiveId() + "</id>"));
                return Item.info(Topic.REPOSITORY, Texts.t("preflight.remote.login.configured", r.label(), code))
                        .evidence(Evidence.setting(Texts.t("preflight.remote.server", r.effectiveId()), withServer.getFile().toString(), line));
            }
            Item item = Item.error(Topic.REPOSITORY, Texts.t("preflight.remote.login.missing", r.label(), code, r.effectiveId()))
                    .evidence(Evidence.hint(url));
            MavenSettings user = ctx.userSettings();
            if (user != null) {
                item.fix(PreflightChecks.open(user.getFile(), Math.max(1, user.lineOf("<servers>")), Texts.t("preflight.open.settings")));
            } else {
                item.fix(FixAction.of(FixAction.MANUAL, Texts.t("preflight.remote.add.server", r.effectiveId())));
            }
            return item;
        }
        if (code == 407) {
            return Item.warning(Topic.REPOSITORY, Texts.t("preflight.remote.proxy.login", r.label()))
                    .evidence(Evidence.hint(url));
        }
        if (code >= 500) {
            return Item.warning(Topic.REPOSITORY, Texts.t("preflight.remote.server.error", r.label(), code))
                    .evidence(Evidence.hint(url));
        }
        return Item.ok(Topic.REPOSITORY, Texts.t("preflight.remote.ok", r.label(), code, response.millis))
                .evidence(Evidence.hint(url));
    }

    /** Parents/BOMs, die lokal fehlen: liegt die POM in einem der erreichbaren Repositories? */
    private static List<Item> missingPoms(PreflightContext ctx, List<Repo> repos,
                                          Map<String, RepositoryProbe.Response> reachable, RepositoryProbe probe) {
        List<Item> items = new ArrayList<Item>();
        int probed = 0;
        for (String gav : ctx.hasRootPom() ? ctx.missingPoms() : new ArrayList<String>()) {
            String[] p = gav.split(":");
            if (p.length != 3 || gav.contains("${") || probed++ >= MAX_MISSING_PROBES) {
                continue;
            }
            String path = p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2] + ".pom";
            boolean found = false;
            boolean allAnswered = true;
            List<String> asked = new ArrayList<String>();
            for (Repo r : repos) {
                ctx.options.cancellation.check();
                RepositoryProbe.Response base = reachable.get(r.effectiveUrl());
                if (base == null || base.failure != null || base.code == 401 || base.code == 403 || base.code == 407) {
                    allAnswered = false;
                    continue;
                }
                String url = slash(r.effectiveUrl()) + path;
                RepositoryProbe.Response resp = probe.head(url, proxyFor(ctx, url), ctx.options.timeoutMillis);
                asked.add(r.label());
                if (resp.failure == null && resp.code >= 200 && resp.code < 300) {
                    found = true;
                    break;
                }
                if (resp.failure != null || resp.code != 404) {
                    allAnswered = false;
                }
            }
            if (!found && allAnswered && !asked.isEmpty()) {
                Item item = Item.error(Topic.REPOSITORY, Texts.t("preflight.remote.pom.missing", gav, asked.size()));
                for (String a : asked) {
                    item.evidence(Evidence.hint(a));
                }
                items.add(item.fix(FixAction.of(FixAction.MANUAL, Texts.t("preflight.remote.pom.missing.fix"))));
            }
        }
        return items;
    }

    private static MavenSettings serverFor(PreflightContext ctx, String id) {
        for (MavenSettings s : ctx.settings()) {
            if (s.getServerIds().contains(id)) {
                return s;
            }
        }
        return null;
    }

    /** Proxy aus settings.xml: gleiches Protokoll zuerst, sonst ein http-Proxy (gilt bei Maven oft für beide). */
    static MavenSettings.Proxy proxyFor(PreflightContext ctx, String url) {
        String protocol = url.toLowerCase(java.util.Locale.ROOT).startsWith("https") ? "https" : "http";
        String host = Text.host(url);
        for (MavenSettings s : ctx.settings()) {
            MavenSettings.Proxy p = s.activeProxy(protocol);
            if (p == null) {
                p = s.activeProxy("http");
            }
            if (p != null) {
                return p.bypasses(host) ? null : p;
            }
        }
        return null;
    }

    private static String slash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }
}
