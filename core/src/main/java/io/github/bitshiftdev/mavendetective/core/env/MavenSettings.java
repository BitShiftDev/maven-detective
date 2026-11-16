package io.github.bitshiftdev.mavendetective.core.env;

import io.github.bitshiftdev.mavendetective.core.Texts;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Die Teile einer {@code settings.xml}, die Build Doctor prüft. */
public final class MavenSettings {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}$]+)}");

    public static final class Mirror {
        public final String id;
        public final String url;
        public final String mirrorOf;

        Mirror(String id, String url, String mirrorOf) {
            this.id = id;
            this.url = url;
            this.mirrorOf = mirrorOf;
        }

        /** Mirror, der nicht aus einer settings.xml stammt (z. B. der eigene Nexus des Preflights). */
        public static Mirror of(String id, String url, String mirrorOf) {
            return new Mirror(id, url, mirrorOf);
        }

        /** Mavens mirrorOf-Logik: {@code *}, {@code external:*}, {@code external:http:*}, Listen, {@code !id}. */
        public boolean matches(String repositoryId, String repositoryUrl) {
            if (mirrorOf == null || repositoryId == null) {
                return false;
            }
            boolean matched = false;
            for (String raw : mirrorOf.split(",")) {
                String p = raw.trim();
                if (p.startsWith("!")) {
                    if (p.substring(1).equals(repositoryId)) {
                        return false;
                    }
                } else if (p.equals(repositoryId) || p.equals("*")) {
                    matched = true;
                } else if (p.equals("external:*")) {
                    matched |= !isLocal(repositoryUrl);
                } else if (p.equals("external:http:*")) {
                    matched |= repositoryUrl != null && repositoryUrl.startsWith("http://") && !isLocal(repositoryUrl);
                }
            }
            return matched;
        }

        private static boolean isLocal(String url) {
            return url == null || url.startsWith("file:") || url.contains("://localhost") || url.contains("://127.0.0.1");
        }
    }

    /** Ein {@code <proxy>}-Eintrag; Zugangsdaten werden nicht gelesen. */
    public static final class Proxy {
        public final String id;
        public final boolean active;
        public final String protocol;
        public final String host;
        public final int port;
        public final String nonProxyHosts;

        Proxy(String id, boolean active, String protocol, String host, int port, String nonProxyHosts) {
            this.id = id;
            this.active = active;
            this.protocol = protocol;
            this.host = host;
            this.port = port;
            this.nonProxyHosts = nonProxyHosts;
        }

        /** Mavens {@code nonProxyHosts}: mit {@code |} getrennt, {@code *} als Platzhalter. */
        public boolean bypasses(String targetHost) {
            if (nonProxyHosts == null || targetHost == null) {
                return false;
            }
            for (String raw : nonProxyHosts.split("[|,]")) {
                String p = raw.trim().toLowerCase(java.util.Locale.ROOT);
                if (p.isEmpty()) {
                    continue;
                }
                String regex = java.util.regex.Pattern.quote(p).replace("*", "\\E.*\\Q");
                if (targetHost.toLowerCase(java.util.Locale.ROOT).matches(regex)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final Path file;
    private final List<String> lines;
    private final List<String> serverIds;
    private final List<Mirror> mirrors;
    private final List<String> pluginGroups;
    private final List<Proxy> proxies;
    private final Boolean offline;
    private final String localRepository;

    private MavenSettings(Path file, List<String> lines, List<String> serverIds, List<Mirror> mirrors,
                          List<String> pluginGroups, List<Proxy> proxies, Boolean offline, String localRepository) {
        this.file = file;
        this.lines = lines;
        this.serverIds = serverIds;
        this.mirrors = mirrors;
        this.pluginGroups = pluginGroups;
        this.proxies = proxies;
        this.offline = offline;
        this.localRepository = localRepository;
    }

    /** Liest die Datei mit Umgebung und System-Properties dieser JVM (siehe {@link #read(Path, Map, Path)}). */
    public static MavenSettings read(Path file) throws IOException {
        return read(file, System.getenv(), null);
    }

    /**
     * Liest die Datei; liefert {@code null}, wenn sie fehlt. Wirft bei ungültigem XML. Platzhalter in den Werten werden
     * wie in Maven ersetzt: {@code ${env.X}} aus {@code env}, {@code ${user.home}} aus {@code userHome} (sonst
     * System-Property), andere aus den System-Properties. Was sich nicht auflösen lässt, bleibt stehen (wie in Maven);
     * Aufrufer behandeln Werte mit {@code ${} als unbekannt.
     */
    public static MavenSettings read(Path file, Map<String, String> env, Path userHome) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        Interpolator in = new Interpolator(env, userHome);
        byte[] bytes = Files.readAllBytes(file);
        List<String> lines = new ArrayList<String>();
        Collections.addAll(lines, new String(bytes, StandardCharsets.UTF_8).split("\\r?\\n", -1));
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            doc = b.parse(file.toFile());
        } catch (Exception e) {
            throw new IOException(Texts.t("settings.invalid", file, e.getMessage()), e);
        }
        Element root = doc.getDocumentElement();
        List<String> servers = new ArrayList<String>();
        for (Element server : children(child(root, "servers"), "server")) {
            servers.add(in.text(child(server, "id")));
        }
        List<Mirror> mirrors = new ArrayList<Mirror>();
        for (Element m : children(child(root, "mirrors"), "mirror")) {
            mirrors.add(new Mirror(in.text(child(m, "id")), in.text(child(m, "url")), in.text(child(m, "mirrorOf"))));
        }
        List<String> groups = new ArrayList<String>();
        for (Element g : children(child(root, "pluginGroups"), "pluginGroup")) {
            groups.add(in.text(g));
        }
        List<Proxy> proxies = new ArrayList<Proxy>();
        for (Element p : children(child(root, "proxies"), "proxy")) {
            String active = in.text(child(p, "active"));
            String protocol = in.text(child(p, "protocol"));
            String port = in.text(child(p, "port"));
            int portNumber = 8080;
            try {
                portNumber = port == null ? 8080 : Integer.parseInt(port);
            } catch (NumberFormatException ignored) {
                // Mavens Standard
            }
            String host = in.text(child(p, "host"));
            proxies.add(new Proxy(in.text(child(p, "id")), active == null || Boolean.parseBoolean(active),
                    protocol == null ? "http" : protocol, unresolved(host) ? null : host, portNumber,
                    in.text(child(p, "nonProxyHosts"))));
        }
        String offlineText = in.text(child(root, "offline"));
        Boolean offline = offlineText == null || unresolved(offlineText) ? null : Boolean.valueOf(offlineText.trim());
        String localRepository = in.text(child(root, "localRepository"));
        return new MavenSettings(file, lines, servers, mirrors, groups, proxies, offline,
                unresolved(localRepository) ? null : localRepository);
    }

    /** Wert mit Platzhalter, den weder Umgebung noch System-Properties auflösen: Maven sieht etwas anderes als wir. */
    public static boolean unresolved(String value) {
        return value != null && value.contains("${");
    }

    /** Ersetzt Platzhalter wie Mavens Settings-Interpolation (Umgebung, System-Properties). */
    private static final class Interpolator {
        private final Map<String, String> env;
        private final Path userHome;

        Interpolator(Map<String, String> env, Path userHome) {
            this.env = env;
            this.userHome = userHome;
        }

        String text(Element e) {
            return interpolate(MavenSettings.text(e));
        }

        String interpolate(String value) {
            if (value == null || !value.contains("${")) {
                return value;
            }
            Matcher m = PLACEHOLDER.matcher(value);
            StringBuffer out = new StringBuffer();
            while (m.find()) {
                String replacement = lookup(m.group(1).trim());
                m.appendReplacement(out, Matcher.quoteReplacement(replacement == null ? m.group() : replacement));
            }
            m.appendTail(out);
            return out.toString();
        }

        private String lookup(String name) {
            if (name.startsWith("env.")) {
                return env == null ? null : env.get(name.substring(4));
            }
            if ("user.home".equals(name) && userHome != null) {
                return userHome.toString();
            }
            try {
                return System.getProperty(name);
            } catch (SecurityException e) {
                return null;
            }
        }
    }

    public Path getFile() { return file; }

    public List<String> getServerIds() { return serverIds; }

    public List<Mirror> getMirrors() { return mirrors; }

    public List<String> getPluginGroups() { return pluginGroups; }

    public List<Proxy> getProxies() { return proxies; }

    /** Erster aktiver Proxy für das Protokoll (wie Maven), oder {@code null}. */
    public Proxy activeProxy(String protocol) {
        for (Proxy p : proxies) {
            if (p.active && p.host != null && protocol.equalsIgnoreCase(p.protocol)) {
                return p;
            }
        }
        return null;
    }

    public Boolean getOffline() { return offline; }

    public String getLocalRepository() { return localRepository; }

    /** Ein Eintrag samt der settings.xml, aus der er stammt. */
    public static final class Chosen<T> {
        public final T value;
        public final MavenSettings from;

        Chosen(T value, MavenSettings from) {
            this.value = value;
            this.from = from;
        }
    }

    /**
     * Mirror wie Maven über mehrere Dateien: Nutzer- und globale settings.xml werden zusammengeführt (Nutzer zuerst,
     * ein globaler Eintrag mit derselben ID fällt weg), dann gewinnt eine exakte ID über alle Dateien vor jedem
     * Muster ({@code DefaultMirrorSelector}). {@code null}, wenn keiner greift.
     */
    public static Chosen<Mirror> mirrorFor(List<MavenSettings> files, String repositoryId, String repositoryUrl) {
        for (int pass = 0; pass < 2; pass++) {
            Set<String> ids = new HashSet<String>();
            for (MavenSettings s : files) {
                List<String> here = new ArrayList<String>();
                for (Mirror m : s.mirrors) {
                    if (m.id != null && ids.contains(m.id)) {
                        continue;
                    }
                    here.add(m.id);
                    boolean hit = pass == 0 ? m.mirrorOf != null && m.mirrorOf.trim().equals(repositoryId)
                            : m.matches(repositoryId, repositoryUrl);
                    if (hit) {
                        return new Chosen<Mirror>(m, s);
                    }
                }
                ids.addAll(here);
            }
        }
        return null;
    }

    /**
     * Proxy wie Mavens {@code DefaultProxySelector} über mehrere Dateien: aktive Proxies, deren
     * {@code nonProxyHosts} den Host nicht ausnehmen, je Protokoll der erste; für https ersatzweise der für http.
     */
    public static Proxy proxyFor(List<MavenSettings> files, String url) {
        String protocol = url.toLowerCase(Locale.ROOT).startsWith("https") ? "https" : "http";
        String host = io.github.bitshiftdev.mavendetective.core.check.Text.host(url);
        Map<String, Proxy> candidates = new HashMap<String, Proxy>();
        Set<String> ids = new HashSet<String>();
        for (MavenSettings s : files) {
            List<String> here = new ArrayList<String>();
            for (Proxy p : s.proxies) {
                String id = p.id == null ? "default" : p.id;
                if (ids.contains(id)) {
                    continue;
                }
                here.add(id);
                if (!p.active || p.host == null || p.bypasses(host)) {
                    continue;
                }
                String key = p.protocol == null ? "http" : p.protocol.toLowerCase(Locale.ROOT);
                if (!candidates.containsKey(key)) {
                    candidates.put(key, p);
                }
            }
            ids.addAll(here);
        }
        Proxy p = candidates.get(protocol);
        return p == null && "https".equals(protocol) ? candidates.get("http") : p;
    }

    /** Erster Mirror, der für das Repository greift – so wählt ihn auch Maven. */
    public Mirror mirrorFor(String repositoryId, String repositoryUrl) {
        for (Mirror m : mirrors) {
            if (m.mirrorOf != null && m.mirrorOf.trim().equals(repositoryId)) {
                return m;
            }
        }
        for (Mirror m : mirrors) {
            if (m.matches(repositoryId, repositoryUrl)) {
                return m;
            }
        }
        return null;
    }

    /** Zeile (ab 1), in der der Text zuerst vorkommt, oder -1. */
    public int lineOf(String needle) {
        return lineOf(lines, needle);
    }

    public static int lineOf(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i + 1;
            }
        }
        return -1;
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element && name.equals(((Element) n).getTagName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<Element>();
        if (parent == null) {
            return out;
        }
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element && name.equals(((Element) n).getTagName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static String text(Element e) {
        return e == null ? null : e.getTextContent().trim();
    }
}
