package io.github.bitshiftdev.mavendetective.core.deps;

import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Eine POM, wie sie auf der Platte steht (ohne Vererbung, ohne Interpolation), mit Zeilennummern
 * für jede Fundstelle. Grundlage für „Warum diese Version?“: Jede Station der Kette soll zur
 * richtigen Zeile springen können, auch in einer Parent-POM oder einem BOM im lokalen Repository.
 *
 * <p>Profile werden bewusst nicht ausgewertet (ihre Aktivierung hängt von der Umgebung ab);
 * {@link #getProfilesTouchingVersions()} nennt die Profile, die Properties oder
 * {@code dependencyManagement} setzen, damit die Erklärung darauf hinweisen kann.
 */
public final class PomFile {

    /** Ein {@code <exclusion>} mit Zeile; {@code groupId}/{@code artifactId} dürfen {@code *} sein. */
    public static final class Exclusion {
        final String groupId;
        final String artifactId;
        final int line;

        Exclusion(String groupId, String artifactId, int line) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.line = line;
        }

        public String getGroupId() { return groupId; }
        public String getArtifactId() { return artifactId; }
        /** Zeile von {@code <exclusion>}. */
        public int getLine() { return line; }
        public String ga() { return groupId + ":" + artifactId; }

        /** Schließt diese Exclusion {@code groupId:artifactId} aus (mit {@code *} als Platzhalter)? */
        public boolean matches(String g, String a) {
            return ("*".equals(groupId) || groupId != null && groupId.equals(g))
                    && ("*".equals(artifactId) || artifactId != null && artifactId.equals(a));
        }
    }

    /** Eine {@code <dependency>} aus {@code dependencies} oder {@code dependencyManagement}. */
    public static final class Dep {
        String groupId;
        String artifactId;
        String version;
        String type;
        String classifier;
        String scope;
        String optional;
        int line;
        int versionLine;
        final List<String> exclusions = new ArrayList<String>();
        final List<Exclusion> exclusionEntries = new ArrayList<Exclusion>();

        public String getGroupId() { return groupId; }
        public String getArtifactId() { return artifactId; }
        /** Roh, also ggf. mit {@code ${…}}; {@code null}, wenn keine Version angegeben ist. */
        public String getVersion() { return version; }
        public String getType() { return type == null ? "jar" : type; }
        public String getClassifier() { return classifier; }
        public String getScope() { return scope; }
        /** Zeile von {@code <dependency>}. */
        public int getLine() { return line; }
        /** Zeile von {@code <version>}, sonst die von {@code <dependency>}. */
        public int getVersionLine() { return versionLine > 0 ? versionLine : line; }
        /** Ausgeschlossene {@code groupId:artifactId} (auch mit {@code *}). */
        public List<String> getExclusions() { return Collections.unmodifiableList(exclusions); }
        /** Die Exclusions mit Zeilennummer. */
        public List<Exclusion> getExclusionEntries() { return Collections.unmodifiableList(exclusionEntries); }
        /** {@code <optional>true</optional>} – wird nicht transitiv weitergegeben. */
        public boolean isOptional() { return "true".equalsIgnoreCase(optional); }

        public String ga() { return groupId + ":" + artifactId; }

        /** Schlüssel, unter dem Maven verwaltete Einträge zusammenführt. */
        public String managementKey() {
            return groupId + ":" + artifactId + ":" + getType() + (classifier == null || classifier.isEmpty() ? "" : ":" + classifier);
        }

        public boolean isImport() {
            return "import".equals(scope) && "pom".equals(type);
        }
    }

    /**
     * Ein {@code <profile>}: id, Art der Aktivierung, Dependencies und Properties. Für „Was hat sich geändert?“,
     * wenn ein Lauf andere Profile aktiviert als der Maven-Import der IDE.
     */
    public static final class Profile {
        /** Wie das Profil ohne {@code -P} aktiv würde. */
        public enum Activation {
            /** Kein {@code <activation>}: nur über {@code -P}. */
            NONE,
            /** Nur {@code <activeByDefault>true}: aktiv, solange kein anderes Profil derselben POM aktiv ist. */
            BY_DEFAULT,
            /** JDK, Betriebssystem, Property, Datei …: hängt von der Umgebung ab. */
            CONDITION
        }

        String id;
        boolean activeByDefault;
        boolean condition;
        final List<Dep> dependencies = new ArrayList<Dep>();
        final Map<String, String> properties = new LinkedHashMap<String, String>();

        public String getId() { return id; }

        public Activation getActivation() {
            return condition ? Activation.CONDITION : activeByDefault ? Activation.BY_DEFAULT : Activation.NONE;
        }

        /** Dependencies aus {@code profile/dependencies} (nicht aus dessen dependencyManagement). */
        public List<Dep> getDependencies() { return Collections.unmodifiableList(dependencies); }

        public Map<String, String> getProperties() { return Collections.unmodifiableMap(properties); }
    }

    /** Eine Property mit ihrer Zeile. */
    public static final class Property {
        final String name;
        final String value;
        final int line;

        Property(String name, String value, int line) {
            this.name = name;
            this.value = value;
            this.line = line;
        }

        public String getName() { return name; }
        public String getValue() { return value; }
        public int getLine() { return line; }
    }

    private final Path file;
    String groupId;
    String artifactId;
    String version;
    String packaging;
    String parentGroupId;
    String parentArtifactId;
    String parentVersion;
    String parentRelativePath;
    boolean parentRelativePathSet;
    int parentLine;
    final Map<String, Property> properties = new LinkedHashMap<String, Property>();
    final List<Dep> dependencies = new ArrayList<Dep>();
    final List<Dep> managed = new ArrayList<Dep>();
    final List<String> profilesTouchingVersions = new ArrayList<String>();
    final List<String> profilesWithDependencies = new ArrayList<String>();
    final List<Profile> profiles = new ArrayList<Profile>();
    boolean relocated;
    String relocationGroupId;
    String relocationArtifactId;
    String relocationVersion;
    final List<String> modules = new ArrayList<String>();

    private PomFile(Path file) {
        this.file = file;
    }

    /** Liest die Datei; wirft bei fehlender Datei oder ungültigem XML. */
    public static PomFile read(Path file) throws Exception {
        PomFile pom = new PomFile(file.toAbsolutePath().normalize());
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        SAXParser parser = f.newSAXParser();
        parser.parse(pom.file.toFile(), new Handler(pom));
        return pom;
    }

    /**
     * Liest eine POM aus Text, als stünde er in {@code file} – für Simulationen, bei denen die
     * geänderte Fassung nur im Speicher existiert (Modul A6). Wirft bei ungültigem XML.
     */
    public static PomFile parse(Path file, String xml) throws Exception {
        PomFile pom = new PomFile(file.toAbsolutePath().normalize());
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        SAXParser parser = f.newSAXParser();
        parser.parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)), new Handler(pom));
        return pom;
    }

    public Path getFile() { return file; }
    public String getGroupId() { return groupId != null ? groupId : parentGroupId; }
    public String getArtifactId() { return artifactId; }
    public String getVersion() { return version != null ? version : parentVersion; }
    /** Version wie im POM notiert, ohne Rückgriff auf den Parent. */
    public String getDeclaredVersion() { return version; }
    public String getPackaging() { return packaging == null ? "jar" : packaging; }
    public boolean hasParent() { return parentArtifactId != null; }
    public String getParentGroupId() { return parentGroupId; }
    public String getParentArtifactId() { return parentArtifactId; }
    public String getParentVersion() { return parentVersion; }
    /** {@code null}, wenn nicht angegeben (Maven nimmt dann {@code ../pom.xml}); leer heißt „nur Repository“. */
    public String getParentRelativePath() { return parentRelativePathSet ? parentRelativePath : null; }
    public int getParentLine() { return parentLine; }
    public Map<String, Property> getProperties() { return Collections.unmodifiableMap(properties); }
    public List<Dep> getDependencies() { return Collections.unmodifiableList(dependencies); }
    public List<Dep> getManagedDependencies() { return Collections.unmodifiableList(managed); }
    public List<String> getProfilesTouchingVersions() { return Collections.unmodifiableList(profilesTouchingVersions); }
    public List<String> getModules() { return Collections.unmodifiableList(modules); }
    /** Profile (id), die Dependencies oder dependencyManagement mitbringen – je nach Aktivierung fehlen sie im Modell. */
    public List<String> getProfilesWithDependencies() { return Collections.unmodifiableList(profilesWithDependencies); }
    /** Alle Profile der POM in Dokumentreihenfolge. */
    public List<Profile> getProfiles() { return Collections.unmodifiableList(profiles); }
    /** Steht {@code distributionManagement/relocation} in der POM (Artefakt ist umgezogen)? */
    public boolean isRelocated() { return relocated; }

    /** Ziel der Relocation als {@code groupId:artifactId:version} (fehlende Teile wie diese POM); {@code null} ohne Relocation. */
    public String relocationTarget() {
        if (!relocated) {
            return null;
        }
        return (relocationGroupId != null ? relocationGroupId : getGroupId()) + ":"
                + (relocationArtifactId != null ? relocationArtifactId : artifactId) + ":"
                + (relocationVersion != null ? relocationVersion : getVersion());
    }

    public String gav() {
        return getGroupId() + ":" + artifactId + ":" + getVersion();
    }

    public String parentGav() {
        return parentGroupId + ":" + parentArtifactId + ":" + parentVersion;
    }

    @Override
    public String toString() {
        return gav() + " (" + file + ")";
    }

    /** SAX-Handler, der über den Elementpfad entscheidet, was wohin gehört. */
    private static final class Handler extends DefaultHandler {
        private final PomFile pom;
        private final List<String> path = new ArrayList<String>();
        private final StringBuilder text = new StringBuilder();
        private Locator locator;
        private Dep dep;
        private boolean depManaged;
        private String exclusionGroup;
        private String exclusionArtifact;
        private int exclusionLine;
        private String profileId;
        private boolean profileTouches;
        private boolean profileDependencies;
        private Profile profile;
        private int elementLine;

        Handler(PomFile pom) {
            this.pom = pom;
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        private int line() {
            return locator == null ? -1 : locator.getLineNumber();
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            path.add(qName);
            text.setLength(0);
            elementLine = line();
            String p = joined();
            if ("project/dependencies/dependency".equals(p) || "project/dependencyManagement/dependencies/dependency".equals(p)
                    || "project/profiles/profile/dependencies/dependency".equals(p)) {
                dep = new Dep();
                dep.line = line();
                depManaged = p.startsWith("project/dependencyManagement");
                if (p.startsWith("project/profiles")) {
                    profileDependencies = true;
                }
            } else if (dep != null && p.endsWith("/dependency/exclusions/exclusion")) {
                exclusionLine = line();
            } else if ("project/parent".equals(p)) {
                pom.parentLine = line();
            } else if ("project/profiles/profile".equals(p)) {
                profileId = null;
                profileTouches = false;
                profileDependencies = false;
                profile = new Profile();
            } else if (p.startsWith("project/profiles/profile/activation/") && path.size() == 5
                    && !"activeByDefault".equals(qName)) {
                if (profile != null) {
                    profile.condition = true;
                }
            } else if (p.startsWith("project/profiles/profile/properties/")
                    || "project/profiles/profile/dependencyManagement".equals(p)) {
                profileTouches = true;
                profileDependencies |= "project/profiles/profile/dependencyManagement".equals(p);
            } else if ("project/distributionManagement/relocation".equals(p)) {
                pom.relocated = true;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String p = joined();
            String value = text.toString().trim();
            if (dep != null && p.endsWith("/dependency/exclusions/exclusion/groupId")) {
                exclusionGroup = value;
            } else if (dep != null && p.endsWith("/dependency/exclusions/exclusion/artifactId")) {
                exclusionArtifact = value;
            } else if (dep != null && p.endsWith("/dependency/exclusions/exclusion")) {
                dep.exclusions.add(exclusionGroup + ":" + exclusionArtifact);
                dep.exclusionEntries.add(new Exclusion(exclusionGroup, exclusionArtifact, exclusionLine));
                exclusionGroup = null;
                exclusionArtifact = null;
            } else if (dep != null && isDepChild(p)) {
                String field = qName;
                if ("groupId".equals(field)) {
                    dep.groupId = value;
                } else if ("artifactId".equals(field)) {
                    dep.artifactId = value;
                } else if ("version".equals(field)) {
                    dep.version = value;
                    dep.versionLine = elementLine;
                } else if ("type".equals(field)) {
                    dep.type = value;
                } else if ("classifier".equals(field)) {
                    dep.classifier = value;
                } else if ("scope".equals(field)) {
                    dep.scope = value;
                } else if ("optional".equals(field)) {
                    dep.optional = value;
                }
            } else if (dep != null && "project/profiles/profile/dependencies/dependency".equals(p)) {
                if (dep.groupId != null && dep.artifactId != null && profile != null) {
                    profile.dependencies.add(dep);
                }
                dep = null;
            } else if (dep != null && ("project/dependencies/dependency".equals(p)
                    || "project/dependencyManagement/dependencies/dependency".equals(p))) {
                if (dep.groupId != null && dep.artifactId != null) {
                    (depManaged ? pom.managed : pom.dependencies).add(dep);
                }
                dep = null;
            } else if (profile != null && path.size() == 5 && p.startsWith("project/profiles/profile/properties/")) {
                profile.properties.put(qName, value);
            } else if (profile != null && "project/profiles/profile/activation/activeByDefault".equals(p)) {
                profile.activeByDefault = "true".equalsIgnoreCase(value);
            } else if (path.size() == 3 && "project/properties".equals(p.substring(0, p.lastIndexOf('/')))) {
                // doppelt definiert: wie Maven gewinnt die letzte Angabe
                pom.properties.put(qName, new Property(qName, value, elementLine));
            } else if ("project/groupId".equals(p)) {
                pom.groupId = value;
            } else if ("project/artifactId".equals(p)) {
                pom.artifactId = value;
            } else if ("project/version".equals(p)) {
                pom.version = value;
            } else if ("project/packaging".equals(p)) {
                pom.packaging = value;
            } else if ("project/parent/groupId".equals(p)) {
                pom.parentGroupId = value;
            } else if ("project/parent/artifactId".equals(p)) {
                pom.parentArtifactId = value;
            } else if ("project/parent/version".equals(p)) {
                pom.parentVersion = value;
            } else if ("project/parent/relativePath".equals(p)) {
                pom.parentRelativePath = value;
                pom.parentRelativePathSet = true;
            } else if ("project/modules/module".equals(p)) {
                pom.modules.add(value);
            } else if ("project/profiles/profile/id".equals(p)) {
                profileId = value;
                if (profile != null) {
                    profile.id = value;
                }
            } else if ("project/distributionManagement/relocation/groupId".equals(p)) {
                pom.relocationGroupId = value.isEmpty() ? null : value;
            } else if ("project/distributionManagement/relocation/artifactId".equals(p)) {
                pom.relocationArtifactId = value.isEmpty() ? null : value;
            } else if ("project/distributionManagement/relocation/version".equals(p)) {
                pom.relocationVersion = value.isEmpty() ? null : value;
            } else if ("project/profiles/profile".equals(p)) {
                if (profileTouches) {
                    pom.profilesTouchingVersions.add(profileId == null ? "(ohne id)" : profileId);
                }
                if (profileDependencies) {
                    pom.profilesWithDependencies.add(profileId == null ? "(ohne id)" : profileId);
                }
                if (profile != null) {
                    pom.profiles.add(profile);
                    profile = null;
                }
            }
            path.remove(path.size() - 1);
            text.setLength(0);
        }

        private static boolean isDepChild(String p) {
            return isChild(p, "project/dependencies/dependency/")
                    || isChild(p, "project/dependencyManagement/dependencies/dependency/")
                    || isChild(p, "project/profiles/profile/dependencies/dependency/");
        }

        private static boolean isChild(String p, String prefix) {
            return p.startsWith(prefix) && p.indexOf('/', prefix.length()) < 0;
        }

        private String joined() {
            StringBuilder sb = new StringBuilder();
            for (String s : path) {
                if (sb.length() > 0) {
                    sb.append('/');
                }
                sb.append(s);
            }
            return sb.toString();
        }
    }
}
