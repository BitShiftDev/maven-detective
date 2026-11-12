package io.github.bitshiftdev.mavendetective.core.check;

import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.env.MavenSettings;
import io.github.bitshiftdev.mavendetective.core.model.RepositoryRequest;

import java.io.File;
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

/** Prüfungen rund um das Auflösen von Artifacts. */
final class ResolutionChecks {

    private ResolutionChecks() {
    }

    /** Alle Koordinaten, um die es geht: Variable {@code artifact} plus gescheiterte Anfragen aus Events. */
    static List<Coordinates> affectedArtifacts(CheckContext ctx) {
        Set<String> raw = new LinkedHashSet<String>();
        if (ctx.var("artifact") != null) {
            raw.add(ctx.var("artifact"));
        }
        for (RepositoryRequest r : ctx.failure().getRepositoryRequests()) {
            if (r.isFailed() && r.getArtifact() != null) {
                raw.add(r.getArtifact());
            }
        }
        List<Coordinates> out = new ArrayList<Coordinates>();
        for (String s : raw) {
            Coordinates c = Coordinates.parse(s);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ offline-mode

    static final class OfflineMode implements Check {
        @Override
        public String id() { return "offline-mode"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            MavenSettings settings = ctx.env().userSettings();
            boolean fromSettings = settings != null && Boolean.TRUE.equals(settings.getOffline());
            boolean offline = Boolean.TRUE.equals(ctx.env().getOffline()) || fromSettings
                    || ctx.anyLineContains("in offline mode");
            if (!offline) {
                return CheckResult.notApplicable();
            }
            CheckResult r = CheckResult.confirmed()
                    .cause("Maven läuft im Offline-Modus und {artifact|das benötigte Artifact} liegt noch nicht im lokalen Repository.");
            if (fromSettings) {
                int line = settings.lineOf("<offline>");
                r.evidence(Evidence.setting("<offline>true</offline> in settings.xml", settings.getFile().toString(), line))
                        .fix(FixAction.of(FixAction.OPEN_FILE, "Offline-Modus in settings.xml abschalten",
                                "file", settings.getFile().toString(), "line", String.valueOf(line)));
            } else {
                r.evidence(Evidence.environment("Maven wurde mit -o / --offline gestartet"))
                        .fix(FixAction.of(FixAction.REBUILD, "Ohne Offline-Modus neu bauen", "removeArgs", "-o --offline"));
            }
            return r;
        }
    }

    // ------------------------------------------------------------------ network-failure

    static final class NetworkFailure implements Check {
        private static final Pattern UNKNOWN_HOST = Pattern.compile(
                "(?:Unknown host (\\S+?)[:\\s]|(\\S+?): (?:Name or service not known|nodename nor servname provided|No such host is known|Temporary failure in name resolution))");
        private static final Pattern REFUSED = Pattern.compile(
                "(?:Connect to (\\S+?) .*failed|Connection refused|No route to host|Network is unreachable)");
        private static final Pattern TIMEOUT = Pattern.compile("(?:connect timed out|Connection timed out|Read timed out|ConnectTimeoutException)");
        private static final Pattern TLS = Pattern.compile(
                "(?:PKIX path building failed|unable to find valid certification path|SSLHandshakeException|Remote host terminated the handshake|certificate_unknown)");
        private static final Pattern PROXY_AUTH = Pattern.compile("(?:Proxy Authentication Required|status code: 407|\\(407\\))");

        @Override
        public String id() { return "network-failure"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            Matcher m;
            if ((m = ctx.find(TLS)) != null) {
                return CheckResult.confirmed()
                        .cause("Dem TLS-Zertifikat von {repoUrl,repoId|des Repositorys} wird nicht vertraut – typisch für Firmen-Proxys mit eigener CA.")
                        .evidence(Evidence.log(m.group()))
                        .evidence(jdkEvidence(ctx))
                        .fix(FixAction.of(FixAction.MANUAL,
                                "Firmen-CA in den Truststore des Maven-JDK importieren: keytool -importcert -cacerts -file firma-ca.crt"));
            }
            if ((m = ctx.find(PROXY_AUTH)) != null) {
                MavenSettings s = ctx.env().userSettings();
                CheckResult r = CheckResult.confirmed()
                        .cause("Der HTTP-Proxy verlangt eine Anmeldung (407); in settings.xml fehlen Proxy-Zugangsdaten.")
                        .evidence(Evidence.log(m.group()));
                if (s != null) {
                    int line = s.lineOf("<proxies>");
                    r.fix(FixAction.of(FixAction.OPEN_FILE, "Proxy-Einstellungen in settings.xml öffnen",
                            "file", s.getFile().toString(), "line", String.valueOf(line)));
                }
                return r;
            }
            String reason = null;
            String host = null;
            if ((m = ctx.find(UNKNOWN_HOST)) != null) {
                host = m.group(1) != null ? m.group(1) : m.group(2);
                reason = "Hostname " + host + " lässt sich nicht auflösen (DNS, VPN oder Proxy?)";
            } else if ((m = ctx.find(REFUSED)) != null) {
                host = m.group(1);
                reason = "die Verbindung wurde abgelehnt";
            } else if ((m = ctx.find(TIMEOUT)) != null) {
                reason = "Zeitüberschreitung beim Verbindungsaufbau";
            }
            if (reason == null) {
                return CheckResult.notApplicable();
            }
            return CheckResult.confirmed()
                    .var("networkReason", reason)
                    .var("host", host)
                    .cause("{repoId|Das Repository} ({repoUrl,host|unbekannte URL}) war nicht erreichbar: {networkReason}.")
                    .evidence(Evidence.log(m.group()))
                    .fix(FixAction.of(FixAction.MANUAL, "Netzwerk, VPN und Proxy-Einstellungen prüfen (Preflight-Check)"))
                    .fix(FixAction.of(FixAction.REBUILD, "Neu bauen, sobald das Netz steht"));
        }

        private static Evidence jdkEvidence(CheckContext ctx) {
            String v = ctx.env().getRunnerJavaVersion();
            return Evidence.environment("JDK des Maven-Laufs: " + (v == null ? "unbekannt" : v)
                    + (ctx.failure().getSession().getJavaHome() == null ? "" : " (" + ctx.failure().getSession().getJavaHome() + ")"));
        }
    }

    // ------------------------------------------------------------------ last-updated-files

    static final class LastUpdatedFiles implements Check {
        @Override
        public String id() { return "last-updated-files"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            Path repo = ctx.env().getLocalRepository();
            boolean cachedMessage = ctx.anyLineContains("was cached in the local repository")
                    || ctx.anyLineContains("during a previous attempt");
            List<String> files = new ArrayList<String>();
            if (repo != null) {
                for (Coordinates c : affectedArtifacts(ctx)) {
                    files.addAll(lastUpdatedFiles(c.directoryIn(repo), c));
                }
            }
            if (files.isEmpty()) {
                if (!cachedMessage) {
                    return CheckResult.notApplicable();
                }
                return CheckResult.info()
                        .evidence(Evidence.hint("Maven meldet einen gecachten Fehlversuch, eine .lastUpdated-Datei wurde aber nicht gefunden"))
                        .fix(FixAction.of(FixAction.REBUILD, "Mit -U neu bauen (Update erzwingen)", "args", "-U"));
            }
            CheckResult r = CheckResult.confirmed()
                    .cause("Ein früherer Fehlversuch für {artifact|das Artifact} ist im lokalen Repository gecacht; Maven fragt erst nach Ablauf des Update-Intervalls erneut.");
            for (String f : files) {
                r.evidence(Evidence.file("Fehlversuch gecacht", f, -1));
            }
            StringBuilder joined = new StringBuilder();
            for (String f : files) {
                joined.append(joined.length() == 0 ? "" : "\n").append(f);
            }
            return r.fix(FixAction.of(FixAction.DELETE_FILES, ".lastUpdated löschen und mit -U neu bauen",
                    "files", joined.toString(), "args", "-U"));
        }

        private static List<String> lastUpdatedFiles(Path dir, Coordinates c) {
            List<String> out = new ArrayList<String>();
            if (!Files.isDirectory(dir)) {
                return out;
            }
            DirectoryStream<Path> ds = null;
            try {
                ds = Files.newDirectoryStream(dir, "*.lastUpdated");
                for (Path p : ds) {
                    if (p.getFileName().toString().startsWith(c.artifactId + "-" + c.version)) {
                        out.add(p.toString());
                    }
                }
            } catch (IOException ignored) {
                // nicht lesbar → nichts gefunden
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

    // ------------------------------------------------------------------ reactor-sibling

    static final class ReactorSibling implements Check {
        @Override
        public String id() { return "reactor-sibling"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            for (Coordinates c : affectedArtifacts(ctx)) {
                ProjectPoms.Pom module = ctx.projectPoms().find(c.groupId, c.artifactId);
                if (module == null) {
                    continue;
                }
                Path root = ctx.projectPoms().root();
                String rel = root == null ? module.file.getParent().toString()
                        : root.relativize(module.file.getParent()).toString();
                if (rel.isEmpty()) {
                    rel = ".";
                }
                String packaging = module.effectivePackaging();
                String produced = extensionOf(packaging);
                if (c.classifier == null && !produced.equals(c.extension)) {
                    return typeMismatch(ctx, c, module, rel, packaging);
                }
                return CheckResult.confirmed()
                        .var("module", rel.replace(File.separatorChar, '/'))
                        .cause("{artifact} ist ein Modul dieses Projekts ({module}), wurde in diesem Lauf aber nicht mitgebaut und liegt nicht im lokalen Repository – typisch für -pl ohne -am.")
                        .evidence(Evidence.file("Modul im Projekt gefunden", module.file.toString(), module.lineOf("<artifactId>" + c.artifactId)))
                        .fix(FixAction.of(FixAction.REBUILD, "Mit -am neu bauen (benötigte Module mitbauen)", "args", "-am"))
                        .fix(FixAction.of(FixAction.MANUAL, "Oder das Modul vorher einmal mit mvn install bauen"));
            }
            return CheckResult.notApplicable();
        }

        /**
         * Reactor-Modul mit packaging war/ear/rar/pom, die Dependency fragt aber nach einem anderen
         * Typ (meist das voreingestellte jar): Maven sucht dann ein Artifact, das es nirgends gibt.
         */
        private static CheckResult typeMismatch(CheckContext ctx, Coordinates c, ProjectPoms.Pom module, String rel, String packaging) {
            CheckResult r = CheckResult.confirmed()
                    .var("module", rel.replace(File.separatorChar, '/'))
                    .var("packaging", packaging)
                    .var("requestedType", c.extension)
                    .cause("{artifact} ist das Modul {module} mit packaging {packaging}; die Dependency verlangt aber type {requestedType}"
                            + " – in der Dependency fehlt <type>{packaging}</type>.")
                    .evidence(Evidence.file("Modul im Projekt: packaging " + packaging, module.file.toString(),
                            module.lineOf("<packaging>")));
            Coordinates consumer = Coordinates.parse(ctx.var("project"));
            ProjectPoms.Pom pom = consumer == null ? null : ctx.projectPoms().find(consumer.groupId, consumer.artifactId);
            PomFile.Dep dep = pom == null ? null : PomChecks.dependency(pom, c.artifactId);
            if (dep != null && dep.getLine() > 0) {
                r.evidence(Evidence.file("Dependency ohne <type>" + packaging + "</type>", pom.file.toString(), dep.getLine()))
                        .fix(FixAction.of(FixAction.OPEN_FILE, "Dependency öffnen und <type>" + packaging + "</type> ergänzen",
                                "file", pom.file.toString(), "line", String.valueOf(dep.getLine())));
            } else {
                r.fix(FixAction.of(FixAction.MANUAL, "In der Dependency auf " + c.ga() + " <type>" + packaging + "</type> ergänzen"));
            }
            return r;
        }

        /** Dateiendung, die ein Modul mit diesem packaging erzeugt. */
        static String extensionOf(String packaging) {
            if ("war".equals(packaging) || "ear".equals(packaging) || "rar".equals(packaging) || "pom".equals(packaging)) {
                return packaging;
            }
            return "jar"; // jar, ejb, maven-plugin, bundle, …
        }
    }

    // ------------------------------------------------------------------ requested-repository

    static final class RequestedRepository implements Check {
        @Override
        public String id() { return "requested-repository"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            CheckResult r = CheckResult.info();
            boolean any = false;
            Set<String> seen = new LinkedHashSet<String>();
            for (RepositoryRequest req : ctx.failure().getRepositoryRequests()) {
                if (req.isFailed() && req.getRepositoryId() != null
                        && seen.add(req.getRepositoryId() + " " + req.getRepositoryUrl()) && seen.size() <= 5) {
                    r.evidence(Evidence.environment("Gefragt: " + req.getRepositoryId() + " (" + req.getRepositoryUrl() + ") → " + req.getFailure()));
                    r.var("repoId", req.getRepositoryId()).var("repoUrl", req.getRepositoryUrl());
                    any = true;
                }
            }
            String repoId = ctx.var("repoId");
            String repoUrl = ctx.var("repoUrl");
            if (!any && repoId != null) {
                r.evidence(Evidence.environment("Gefragt: " + repoId + (repoUrl == null ? "" : " (" + repoUrl + ")")));
                any = true;
            }
            MavenSettings settings = ctx.env().userSettings();
            if (settings != null && repoId != null) {
                for (MavenSettings.Mirror m : settings.getMirrors()) {
                    if (repoId.equals(m.id)) {
                        r.evidence(Evidence.setting("Anfrage lief über Mirror '" + m.id + "' (mirrorOf " + m.mirrorOf + ") → " + m.url,
                                settings.getFile().toString(), settings.lineOf("<id>" + m.id + "</id>")));
                        any = true;
                    }
                }
                MavenSettings.Mirror m = settings.mirrorFor(repoId, repoUrl);
                if (m != null && !repoId.equals(m.id)) {
                    r.evidence(Evidence.setting("Mirror '" + m.id + "' greift für '" + repoId + "' → " + m.url,
                            settings.getFile().toString(), settings.lineOf("<id>" + m.id + "</id>")));
                    any = true;
                }
            }
            return any ? r : CheckResult.notApplicable();
        }
    }
}
