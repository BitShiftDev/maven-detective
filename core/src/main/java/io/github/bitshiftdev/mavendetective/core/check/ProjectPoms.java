package io.github.bitshiftdev.mavendetective.core.check;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Die POMs eines Projekts, einmal pro Diagnose eingelesen. Fehlerhafte POMs werden roh (nur Text) geführt. */
public final class ProjectPoms {

    private static final int MAX_DEPTH = 6;
    private static final int MAX_POMS = 500;
    private static final Set<String> SKIP_DIRS = new HashSet<String>(Arrays.asList(
            "target", "node_modules", ".git", ".idea", ".mvn", "build", "out", ".gradle", "src"));

    /** Eine POM mit Koordinaten (sofern lesbar) und ihren Textzeilen. */
    public static final class Pom {
        public final Path file;
        public final List<String> lines;
        public String groupId;
        public String artifactId;
        public String version;
        /** Wie in der POM notiert; {@code null} heißt jar. */
        public String packaging;
        public String parentGroupId;
        public String parentArtifactId;
        public String parentVersion;
        /** {@code null}, wenn kein relativePath angegeben ist (Maven nimmt dann ../pom.xml). */
        public String parentRelativePath;
        public boolean hasParent;
        public boolean parseable;

        Pom(Path file, List<String> lines) {
            this.file = file;
            this.lines = lines;
        }

        public String effectiveGroupId() {
            return groupId != null ? groupId : parentGroupId;
        }

        public String effectiveVersion() {
            return version != null ? version : parentVersion;
        }

        public String effectivePackaging() {
            return packaging == null || packaging.isEmpty() ? "jar" : packaging;
        }

        public String gav() {
            return effectiveGroupId() + ":" + artifactId + ":" + effectiveVersion();
        }

        /** Erste Zeile (ab 1), die den Text enthält, oder -1. */
        public int lineOf(String needle) {
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(needle)) {
                    return i + 1;
                }
            }
            return -1;
        }
    }

    /** Fundstelle in einer POM. */
    public static final class Location {
        public final Pom pom;
        public final int line;

        Location(Pom pom, int line) {
            this.pom = pom;
            this.line = line;
        }
    }

    private final Path root;
    private List<Pom> poms;
    private final Map<Path, Pom> cache = new HashMap<Path, Pom>();

    public ProjectPoms(Path root) {
        // absolut, damit relativize() gegen die (absoluten) POM-Pfade funktioniert
        this.root = root == null ? null : root.toAbsolutePath().normalize();
    }

    public Path root() { return root; }

    public List<Pom> all() {
        if (poms == null) {
            poms = new ArrayList<Pom>();
            if (root != null && Files.isDirectory(root)) {
                scan(root, 0);
            }
        }
        return Collections.unmodifiableList(poms);
    }

    private void scan(Path dir, int depth) {
        if (poms.size() >= MAX_POMS) {
            return;
        }
        Path pom = dir.resolve("pom.xml");
        if (Files.isRegularFile(pom)) {
            Pom p = read(pom);
            if (p != null) {
                poms.add(p);
            }
        }
        if (depth >= MAX_DEPTH) {
            return;
        }
        DirectoryStream<Path> ds = null;
        try {
            ds = Files.newDirectoryStream(dir);
            List<Path> children = new ArrayList<Path>();
            for (Path child : ds) {
                if (Files.isDirectory(child) && !SKIP_DIRS.contains(String.valueOf(child.getFileName()))
                        && !String.valueOf(child.getFileName()).startsWith(".")) {
                    children.add(child);
                }
            }
            Collections.sort(children);
            for (Path child : children) {
                scan(child, depth + 1);
            }
        } catch (IOException ignored) {
            // unlesbares Verzeichnis überspringen
        } finally {
            if (ds != null) {
                try {
                    ds.close();
                } catch (IOException ignored) {
                    // egal
                }
            }
        }
    }

    public Pom rootPom() {
        return root == null ? null : read(root.resolve("pom.xml"));
    }

    /** Liest eine beliebige POM (auch außerhalb des Projekts); {@code null}, wenn sie nicht existiert. */
    public Pom read(Path file) {
        Path key = file.toAbsolutePath().normalize();
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        Pom pom = null;
        if (Files.isRegularFile(key)) {
            try {
                List<String> lines = Arrays.asList(new String(Files.readAllBytes(key), StandardCharsets.UTF_8).split("\\r?\\n", -1));
                pom = new Pom(key, lines);
                parseCoordinates(pom);
            } catch (IOException e) {
                pom = null;
            }
        }
        cache.put(key, pom);
        return pom;
    }

    public Pom find(String groupId, String artifactId) {
        for (Pom p : all()) {
            if (artifactId.equals(p.artifactId) && (groupId == null || groupId.equals(p.effectiveGroupId()))) {
                return p;
            }
        }
        return null;
    }

    /** Alle Stellen in Projekt-POMs, die den Text enthalten. */
    public List<Location> findText(String needle) {
        List<Location> out = new ArrayList<Location>();
        for (Pom p : all()) {
            for (int i = 0; i < p.lines.size(); i++) {
                if (p.lines.get(i).contains(needle)) {
                    out.add(new Location(p, i + 1));
                }
            }
        }
        return out;
    }

    private static void parseCoordinates(Pom pom) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            Document doc = b.parse(pom.file.toFile());
            Element project = doc.getDocumentElement();
            pom.groupId = text(child(project, "groupId"));
            pom.artifactId = text(child(project, "artifactId"));
            pom.version = text(child(project, "version"));
            pom.packaging = text(child(project, "packaging"));
            Element parent = child(project, "parent");
            if (parent != null) {
                pom.hasParent = true;
                pom.parentGroupId = text(child(parent, "groupId"));
                pom.parentArtifactId = text(child(parent, "artifactId"));
                pom.parentVersion = text(child(parent, "version"));
                Element rel = child(parent, "relativePath");
                pom.parentRelativePath = rel == null ? null : rel.getTextContent().trim();
            }
            pom.parseable = true;
        } catch (Exception e) {
            pom.parseable = false;
        }
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

    private static String text(Element e) {
        return e == null ? null : e.getTextContent().trim();
    }
}
