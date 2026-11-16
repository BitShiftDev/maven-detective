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
 * und ob fehlende Parents/BOMs dort liegen. Mit eigenem Nexus ({@link Preflight.Options#nexus(String)})
 * gehen alle Anfragen dorthin, wie bei einem Mirror mit {@code mirrorOf *}.
 */
final class RemoteRepositories {

    static final String CENTRAL_ID = "central";
    static final String CENTRAL_URL = "https://repo.maven.apache.org/maven2";
    /** ID des eigenen Nexus, wenn ihn keine settings.xml als Mirror kennt. */
    static final String NEXUS_ID = "maven-detective-nexus";
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
        /** Über den eigenen Nexus des Preflights umgelenkt. */
        boolean viaNexus;

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

        /** Name in Ergebniszeilen; beim eigenen Nexus steht eine Zeile für alle Repositories. */
        String label() {
            if (viaNexus) {
                return Texts.t("preflight.remote.nexus");
            }
            return mirror == null ? id : Texts.t("preflight.remote.via.mirror", id, mirror.id);
        }

        /** Name in der Liste der Repositories (ohne Netz). */
        String listLabel() {
            return viaNexus ? Texts.t("preflight.remote.via.nexus", id) : label();
        }
    }

    private RemoteRepositories() {
    }

    static List<Repo> collect(PreflightContext ctx) {
        Map<String, Repo> repos = new LinkedHashMap<String, Repo>();
        repos.put(CENTRAL_ID, new Repo(CENTRAL_ID, CENTRAL_URL, null, -1));
        if (ctx.hasRootPom()) {
            for (ProjectPoms.Pom pom : ctx.modulePoms()) {
                for (Repo r : declared(pom, ctx.code(pom, false))) {
                    Repo known = repos.get(r.id);
                    if (known == null || known.pom == null) {
                        repos.put(r.id, r);
                    }
                }
            }
        }
        String nexus = ctx.options.nexus;
        if (nexus != null) {
            MavenSettings known = settingsWithMirror(ctx, nexus);
            MavenSettings.Mirror mirror = known != null ? mirrorWithUrl(known, nexus)
                    : MavenSettings.Mirror.of(NEXUS_ID, nexus, "*");
            for (Repo r : repos.values()) {
                r.mirror = mirror;
                r.mirrorSettings = known;
                r.viaNexus = true;
            }
            return new ArrayList<Repo>(repos.values());
        }
        List<MavenSettings> settings = ctx.settings();
        for (Repo r : repos.values()) {
            MavenSettings.Chosen<MavenSettings.Mirror> m = MavenSettings.mirrorFor(settings, r.id, r.url);
            if (m != null) {
                r.mirror = m.value;
                r.mirrorSettings = m.from;
            }
        }
        return new ArrayList<Repo>(repos.values());
    }

    /** Erste settings.xml mit einem Mirror auf diese URL (dessen ID gilt dann für {@code <server>}). */
    static MavenSettings settingsWithMirror(PreflightContext ctx, String url) {
        for (MavenSettings s : ctx.settings()) {
            if (mirrorWithUrl(s, url) != null) {
                return s;
            }
        }
        return null;
    }

    private static MavenSettings.Mirror mirrorWithUrl(MavenSettings s, String url) {
        for (MavenSettings.Mirror m : s.getMirrors()) {
            if (m.url != null && sameUrl(m.url, url)) {
                return m;
            }
        }
        return null;
    }

    static boolean sameUrl(String a, String b) {
        return trim(a).equalsIgnoreCase(trim(b));
    }

    private static String trim(String url) {
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /** {@code <repository>}/{@code <pluginRepository>} einer POM mit Zeile der URL; {@code lines} ohne Kommentare. */
    static List<Repo> declared(ProjectPoms.Pom pom, List<String> lines) {
        List<Repo> out = new ArrayList<Repo>();
        boolean inList = false;
        boolean inRepo = false;
        String id = null;
        String url = null;
        int urlLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
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
        if (ctx.options.nexus != null) {
            items.add(nexusNotice(ctx));
        }
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
                item.evidence(Evidence.hint(r.listLabel() + " → " + Text.maskUserInfo(r.effectiveUrl())));
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
            if (MavenSettings.unresolved(url)) {
                // Platzhalter, den nur die Build-Umgebung kennt: nicht anfragen, kein Urteil (fehlt in reachable)
                Item item = Item.info(Topic.REPOSITORY, Texts.t("preflight.remote.unresolved", r.label(), Text.maskUserInfo(url)));
                if (r.mirrorSettings != null && r.mirror != null && url.equals(r.mirror.url)) {
                    int line = Math.max(1, r.mirrorSettings.lineOf(url));
                    item.evidence(Evidence.setting(url, r.mirrorSettings.getFile().toString(), line));
                }
                items.add(item);
                continue;
            }
            RepositoryProbe.Response response = probe.head(slash(url), proxyFor(ctx, url), ctx.options.timeoutMillis);
            reachable.put(url, response);
            items.add(answer(ctx, r, response));
        }
        items.addAll(missingPoms(ctx, usable, reachable, probe));
        return items;
    }

    /**
     * Hinweis zum eigenen Nexus: ob ihn eine settings.xml als Mirror kennt – sonst prüft der Preflight
     * einen anderen Weg als den, den der Build nimmt.
     */
    private static Item nexusNotice(PreflightContext ctx) {
        String nexus = ctx.options.nexus;
        MavenSettings known = settingsWithMirror(ctx, nexus);
        if (known != null) {
            MavenSettings.Mirror m = mirrorWithUrl(known, nexus);
            int line = Math.max(1, known.lineOf(m.url));
            return Item.info(Topic.REPOSITORY, Texts.t("preflight.remote.nexus.mirror", nexus, m.id))
                    .evidence(Evidence.setting(Texts.t("preflight.remote.nexus.mirror.evidence", m.id), known.getFile().toString(), line));
        }
        Item item = Item.info(Topic.REPOSITORY, Texts.t("preflight.remote.nexus.unlisted", nexus));
        MavenSettings user = ctx.userSettings();
        if (user != null) {
            int line = user.lineOf("<mirrors>");
            item.fix(PreflightChecks.open(user.getFile(), Math.max(1, line), Texts.t("preflight.open.settings")));
        }
        return item;
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
        Item item = Item.error(Topic.REPOSITORY, Texts.t("preflight.remote.http", r.label(),
                Text.maskUserInfo(r.viaNexus ? r.effectiveUrl() : r.url)));
        if (!r.viaNexus && r.pom != null && PreflightChecks.isRemoteHttp(r.url)) {
            String https = "https://" + r.url.substring("http://".length());
            item.evidence(Evidence.file(Texts.t("check.http.pom.evidence"), r.pom.file.toString(), r.line))
                    .fix(FixAction.of(FixAction.REPLACE_URL, Texts.t("preflight.switch.https"),
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
                    .evidence(Evidence.hint(Text.maskUserInfo(url)));
            if (response.message != null) {
                item.evidence(Evidence.log(Text.truncate(response.message, 200)));
            }
            if (response.failure == RepositoryProbe.Failure.CERTIFICATE) {
                item.evidence(Evidence.hint(Texts.t("preflight.remote.certificate.jvm")));
            }
            return item;
        }
        int code = response.code;
        if ((code == 401 || code == 403) && r.viaNexus && r.mirrorSettings == null) {
            // ohne Mirror-Eintrag gibt es keine ID, unter der ein <server> stehen könnte
            return Item.info(Topic.REPOSITORY, Texts.t("preflight.remote.nexus.login", r.label(), code))
                    .evidence(Evidence.hint(Text.maskUserInfo(url)));
        }
        if (code == 401 || code == 403) {
            MavenSettings withServer = serverFor(ctx, r.effectiveId());
            if (withServer != null) {
                int line = Math.max(1, withServer.lineOf("<id>" + r.effectiveId() + "</id>"));
                return Item.info(Topic.REPOSITORY, Texts.t("preflight.remote.login.configured", r.label(), code))
                        .evidence(Evidence.setting(Texts.t("preflight.remote.server", r.effectiveId()), withServer.getFile().toString(), line));
            }
            Item item = Item.error(Topic.REPOSITORY, Texts.t("preflight.remote.login.missing", r.label(), code, r.effectiveId()))
                    .evidence(Evidence.hint(Text.maskUserInfo(url)));
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
                    .evidence(Evidence.hint(Text.maskUserInfo(url)));
        }
        if (code >= 500) {
            return Item.warning(Topic.REPOSITORY, Texts.t("preflight.remote.server.error", r.label(), code))
                    .evidence(Evidence.hint(Text.maskUserInfo(url)));
        }
        return Item.ok(Topic.REPOSITORY, Texts.t("preflight.remote.ok", r.label(), code, response.millis))
                .evidence(Evidence.hint(Text.maskUserInfo(url)));
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
            java.util.Set<String> askedUrls = new java.util.HashSet<String>();
            for (Repo r : repos) {
                ctx.options.cancellation.check();
                RepositoryProbe.Response base = reachable.get(r.effectiveUrl());
                if (base == null || base.failure != null || base.code == 401 || base.code == 403 || base.code == 407) {
                    allAnswered = false;
                    continue;
                }
                String url = slash(r.effectiveUrl()) + path;
                if (!askedUrls.add(url)) {
                    continue;   // mehrere Repositories hinter demselben Mirror: einmal fragen reicht
                }
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

    /** Proxy aus settings.xml, gewählt wie von Maven ({@link MavenSettings#proxyFor(List, String)}). */
    static MavenSettings.Proxy proxyFor(PreflightContext ctx, String url) {
        return MavenSettings.proxyFor(ctx.settings(), url);
    }

    private static String slash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }
}
