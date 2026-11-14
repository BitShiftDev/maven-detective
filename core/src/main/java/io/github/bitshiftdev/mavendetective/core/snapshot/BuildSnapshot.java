package io.github.bitshiftdev.mavendetective.core.snapshot;

import io.github.bitshiftdev.mavendetective.core.io.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Was einen Build ausmacht, ohne den Quellcode: Maven, JDK, Profile, Mirrors und pro Modul Parent,
 * Hash der Effective POM, Versions-Properties, Plugins und aufgelöste Dependencies.
 *
 * <p>Nach jedem grünen Build wird ein Schnappschuss gespeichert ({@link SnapshotStore}). Scheitert ein
 * späterer Build, zeigt {@link SnapshotDiff}, was sich seitdem geändert hat – etwa
 * „Parent spring-boot-starter-parent 3.4.1 → 3.5.0, JDK 21 → 17“.
 *
 * <p>Schnappschüsse entstehen in der Maven-Extension (Kommandozeile, CI) und in der IDE (aus dem
 * Maven-Modell von IntelliJ). Beide schreiben dasselbe JSON-Format.
 */
public final class BuildSnapshot {

    public static final int FORMAT_VERSION = 1;

    /** Woher der Schnappschuss stammt. */
    public static final String ORIGIN_EXTENSION = "extension";
    public static final String ORIGIN_IDE = "ide";

    private String origin;
    private String createdAt;
    /** Ergebnis des Laufs: {@code true} grün, {@code false} rot, {@code null} unbekannt (z. B. IDE-Import). */
    private Boolean successful;
    private String projectDirectory;
    private String mavenVersion;
    private String javaVersion;
    private String javaHome;
    private Boolean offline;
    private String userSettings;
    private final List<String> activeProfiles = new ArrayList<String>();
    /** Mirror-ID → URL (mirrorOf steckt im Wert, falls bekannt: "url (mirrorOf)"). */
    private final Map<String, String> mirrors = new TreeMap<String, String>();
    /** groupId:artifactId → Modul. */
    private final Map<String, Module> modules = new TreeMap<String, Module>();

    /** Ein Modul des Reactors. */
    public static final class Module {
        private String version;
        private String parent;
        private String effectivePomHash;
        /** Nur Properties, die eine Version tragen ({@code *.version}, {@code version.*}). */
        private final Map<String, String> versionProperties = new TreeMap<String, String>();
        /** groupId:artifactId → Version. */
        private final Map<String, String> plugins = new TreeMap<String, String>();
        /** groupId:artifactId[:classifier] → Version. */
        private final Map<String, String> dependencies = new TreeMap<String, String>();
        /** true: transitiv aufgelöst; false: nur direkte Dependencies der Effective POM. */
        private boolean dependenciesResolved;
        /**
         * Scopes, die der Lauf aufgelöst hat ({@code mvn compile} kennt keine test-Dependencies, {@code verify} schon).
         * Leer heißt unbekannt = alle (IDE, ältere Schnappschüsse).
         */
        private final Set<String> resolvedScopes = new TreeSet<String>();
        /** groupId:artifactId[:classifier] → Scope, soweit bekannt. */
        private final Map<String, String> dependencyScopes = new TreeMap<String, String>();

        public String getVersion() { return version; }
        public Module setVersion(String version) { this.version = version; return this; }

        public String getParent() { return parent; }
        public Module setParent(String parent) { this.parent = parent; return this; }

        public String getEffectivePomHash() { return effectivePomHash; }
        public Module setEffectivePomHash(String hash) { this.effectivePomHash = hash; return this; }

        public Map<String, String> getVersionProperties() { return versionProperties; }

        public Map<String, String> getPlugins() { return plugins; }

        public Map<String, String> getDependencies() { return dependencies; }

        public boolean isDependenciesResolved() { return dependenciesResolved; }
        public Module setDependenciesResolved(boolean resolved) { this.dependenciesResolved = resolved; return this; }

        public Set<String> getResolvedScopes() { return resolvedScopes; }

        public Map<String, String> getDependencyScopes() { return dependencyScopes; }

        /**
         * Hat dieser Lauf Dependencies dieses Scopes gesehen? Ohne Auflösung stehen die direkten Dependencies aller
         * Scopes drin; ohne Angabe der Scopes (IDE, alte Schnappschüsse) gilt alles als gesehen.
         */
        public boolean covers(String scope) {
            return !dependenciesResolved || resolvedScopes.isEmpty() || scope == null || resolvedScopes.contains(scope);
        }

        /** Übernimmt eine Property, wenn ihr Name nach Version aussieht. */
        public Module addPropertyIfVersion(String name, String value) {
            if (name != null && value != null && isVersionProperty(name)) {
                versionProperties.put(name, value);
            }
            return this;
        }
    }

    /**
     * Scopes, die ein Mojo mit {@code requiresDependencyResolution = value} auflösen lässt (Mavens
     * {@code ResolutionScope}); leer bei {@code none}/{@code null}/Unbekanntem.
     */
    public static List<String> scopesResolvedBy(String value) {
        if (value == null) {
            return Collections.emptyList();
        }
        String v = value.trim();
        if ("compile".equals(v)) {
            return Arrays.asList("compile", "provided", "system");
        }
        if ("compile+runtime".equals(v)) {
            return Arrays.asList("compile", "provided", "system", "runtime");
        }
        if ("runtime".equals(v)) {
            return Arrays.asList("compile", "runtime");
        }
        if ("runtime+system".equals(v)) {
            return Arrays.asList("compile", "runtime", "system");
        }
        if ("test".equals(v)) {
            return Arrays.asList("compile", "provided", "system", "runtime", "test");
        }
        return Collections.emptyList();
    }

    public static boolean isVersionProperty(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".version") || n.endsWith("-version") || n.startsWith("version.")
                || n.equals("maven.compiler.release") || n.equals("maven.compiler.source")
                || n.equals("maven.compiler.target") || n.equals("java.version");
    }

    public String getOrigin() { return origin; }
    public BuildSnapshot setOrigin(String origin) { this.origin = origin; return this; }

    public String getCreatedAt() { return createdAt; }
    public BuildSnapshot setCreatedAt(String createdAt) { this.createdAt = createdAt; return this; }

    public Boolean getSuccessful() { return successful; }
    public BuildSnapshot setSuccessful(Boolean successful) { this.successful = successful; return this; }

    public String getProjectDirectory() { return projectDirectory; }
    public BuildSnapshot setProjectDirectory(String dir) { this.projectDirectory = dir; return this; }

    public String getMavenVersion() { return mavenVersion; }
    public BuildSnapshot setMavenVersion(String v) { this.mavenVersion = v; return this; }

    public String getJavaVersion() { return javaVersion; }
    public BuildSnapshot setJavaVersion(String v) { this.javaVersion = v; return this; }

    public String getJavaHome() { return javaHome; }
    public BuildSnapshot setJavaHome(String v) { this.javaHome = v; return this; }

    public Boolean getOffline() { return offline; }
    public BuildSnapshot setOffline(Boolean offline) { this.offline = offline; return this; }

    public String getUserSettings() { return userSettings; }
    public BuildSnapshot setUserSettings(String s) { this.userSettings = s; return this; }

    public List<String> getActiveProfiles() { return activeProfiles; }

    public Map<String, String> getMirrors() { return mirrors; }

    public Map<String, Module> getModules() { return modules; }

    public Module module(String groupAndArtifact) {
        Module m = modules.get(groupAndArtifact);
        if (m == null) {
            m = new Module();
            modules.put(groupAndArtifact, m);
        }
        return m;
    }

    // ---------------------------------------------------------------- JSON

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("formatVersion", (long) FORMAT_VERSION);
        root.put("origin", origin);
        root.put("createdAt", createdAt);
        root.put("successful", successful);
        root.put("projectDirectory", projectDirectory);
        root.put("maven", mavenVersion);
        root.put("java", javaVersion);
        root.put("javaHome", javaHome);
        root.put("offline", offline);
        root.put("userSettings", userSettings);
        List<String> profiles = new ArrayList<String>(activeProfiles);
        Collections.sort(profiles);
        root.put("profiles", profiles);
        root.put("mirrors", new LinkedHashMap<String, Object>(mirrors));
        Map<String, Object> mods = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Module> e : modules.entrySet()) {
            Module m = e.getValue();
            Map<String, Object> mo = new LinkedHashMap<String, Object>();
            mo.put("version", m.version);
            mo.put("parent", m.parent);
            mo.put("effectivePomHash", m.effectivePomHash);
            mo.put("versionProperties", new LinkedHashMap<String, Object>(m.versionProperties));
            mo.put("plugins", new LinkedHashMap<String, Object>(m.plugins));
            mo.put("dependenciesResolved", m.dependenciesResolved);
            mo.put("dependencies", new LinkedHashMap<String, Object>(m.dependencies));
            if (!m.resolvedScopes.isEmpty()) {
                mo.put("resolvedScopes", new ArrayList<String>(m.resolvedScopes));
            }
            if (!m.dependencyScopes.isEmpty()) {
                mo.put("dependencyScopes", new LinkedHashMap<String, Object>(m.dependencyScopes));
            }
            mods.put(e.getKey(), mo);
        }
        root.put("modules", mods);
        return Json.write(root);
    }

    /** Liest das JSON wieder ein. Unbekannte Felder werden ignoriert, fehlende bleiben leer. */
    public static BuildSnapshot fromJson(String json) {
        Map<String, Object> root = Json.parseObject(json);
        BuildSnapshot s = new BuildSnapshot();
        s.origin = str(root.get("origin"));
        s.createdAt = str(root.get("createdAt"));
        s.successful = root.get("successful") instanceof Boolean ? (Boolean) root.get("successful") : null;
        s.projectDirectory = str(root.get("projectDirectory"));
        s.mavenVersion = str(root.get("maven"));
        s.javaVersion = str(root.get("java"));
        s.javaHome = str(root.get("javaHome"));
        s.offline = root.get("offline") instanceof Boolean ? (Boolean) root.get("offline") : null;
        s.userSettings = str(root.get("userSettings"));
        if (root.get("profiles") instanceof List) {
            for (Object o : (List<?>) root.get("profiles")) {
                s.activeProfiles.add(String.valueOf(o));
            }
        }
        copy(root.get("mirrors"), s.mirrors);
        if (root.get("modules") instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) root.get("modules")).entrySet()) {
                if (!(e.getValue() instanceof Map)) {
                    continue;
                }
                Map<?, ?> mo = (Map<?, ?>) e.getValue();
                Module m = s.module(String.valueOf(e.getKey()));
                m.version = str(mo.get("version"));
                m.parent = str(mo.get("parent"));
                m.effectivePomHash = str(mo.get("effectivePomHash"));
                m.dependenciesResolved = Boolean.TRUE.equals(mo.get("dependenciesResolved"));
                copy(mo.get("versionProperties"), m.versionProperties);
                copy(mo.get("plugins"), m.plugins);
                copy(mo.get("dependencies"), m.dependencies);
                copy(mo.get("dependencyScopes"), m.dependencyScopes);
                if (mo.get("resolvedScopes") instanceof List) {
                    for (Object o : (List<?>) mo.get("resolvedScopes")) {
                        m.resolvedScopes.add(String.valueOf(o));
                    }
                }
            }
        }
        return s;
    }

    private static void copy(Object from, Map<String, String> to) {
        if (from instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) from).entrySet()) {
                if (e.getValue() != null) {
                    to.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
