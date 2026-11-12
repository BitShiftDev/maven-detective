package io.github.bitshiftdev.mavendetective.core.check;

import java.nio.file.Path;

/** Maven-Koordinaten in den Schreibweisen g:a:v, g:a:ext:v und g:a:ext:classifier:v. */
public final class Coordinates {

    public final String groupId;
    public final String artifactId;
    public final String extension;
    public final String classifier;
    public final String version;

    private Coordinates(String groupId, String artifactId, String extension, String classifier, String version) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.extension = extension;
        this.classifier = classifier;
        this.version = version;
    }

    /** {@code null}, wenn der Text keine Koordinaten sind. */
    public static Coordinates parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        // Anhängsel wie "(absent)", Kommas oder Punkte am Satzende entfernen
        t = t.replaceAll("[,;.)]+$", "");
        String[] p = t.split(":");
        for (String part : p) {
            if (part.isEmpty() || !part.matches("[A-Za-z0-9_.\\-${}]+")) {
                return null;
            }
        }
        switch (p.length) {
            case 3: return new Coordinates(p[0], p[1], "jar", null, p[2]);
            case 4: return new Coordinates(p[0], p[1], p[2], null, p[3]);
            case 5: return new Coordinates(p[0], p[1], p[2], p[3], p[4]);
            default: return null;
        }
    }

    /** Verzeichnis im lokalen Repository: g/r/o/u/p/artifactId/version. */
    public Path directoryIn(Path localRepository) {
        Path dir = localRepository;
        for (String segment : groupId.split("\\.")) {
            dir = dir.resolve(segment);
        }
        return dir.resolve(artifactId).resolve(version);
    }

    /** Dateiname des Artifacts, z. B. {@code foo-1.0-tests.jar}. */
    public String fileName() {
        return artifactId + "-" + version + (classifier == null ? "" : "-" + classifier) + "." + extension;
    }

    public String ga() {
        return groupId + ":" + artifactId;
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
