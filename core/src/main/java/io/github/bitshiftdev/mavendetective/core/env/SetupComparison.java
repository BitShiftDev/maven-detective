package io.github.bitshiftdev.mavendetective.core.env;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * „Im Terminal geht es, in der IDE nicht“: stellt zwei {@link BuildSetup}s gegenüber und nennt jede
 * Abweichung mit einem Satz, warum sie den Build ändern kann.
 */
public final class SetupComparison {

    /** Eine Zeile der Gegenüberstellung. */
    public static final class Row {
        private final String aspect;
        private final String left;
        private final String right;
        private final boolean different;
        private final String hint;

        Row(String aspect, String left, String right, boolean different, String hint) {
            this.aspect = aspect;
            this.left = left;
            this.right = right;
            this.different = different;
            this.hint = hint;
        }

        public String getAspect() { return aspect; }
        public String getLeft() { return left; }
        public String getRight() { return right; }
        public boolean isDifferent() { return different; }
        /** Warum die Abweichung zählt; {@code null}, wenn gleich. */
        public String getHint() { return hint; }
    }

    private final BuildSetup left;
    private final BuildSetup right;
    private final List<Row> rows;

    private SetupComparison(BuildSetup left, BuildSetup right, List<Row> rows) {
        this.left = left;
        this.right = right;
        this.rows = rows;
    }

    public static SetupComparison compare(BuildSetup left, BuildSetup right) {
        List<Row> rows = new ArrayList<Row>();

        int lj = BuildSetup.javaMajor(left.getJavaVersion());
        int rj = BuildSetup.javaMajor(right.getJavaVersion());
        boolean jdkDiff = lj > 0 && rj > 0 && lj != rj;
        rows.add(new Row("JDK", javaText(left), javaText(right), jdkDiff,
                jdkDiff ? left.getLabel() + " baut mit Java " + lj + ", " + right.getLabel() + " mit Java " + rj
                        + ". Das ändert, welche --release-Werte, Toolchains und Plugin-Versionen funktionieren." : null));

        boolean mvnDiff = known(left.getMavenVersion()) && known(right.getMavenVersion())
                && !left.getMavenVersion().equals(right.getMavenVersion());
        rows.add(new Row("Maven", mavenText(left), mavenText(right), mvnDiff,
                mvnDiff ? "Unterschiedliche Maven-Versionen lösen Plugins und Defaults verschieden auf"
                        + (majorMinor(left.getMavenVersion()).equals(majorMinor(right.getMavenVersion())) ? "." : " – hier sogar über eine Minor-Version hinweg.")
                        : null));

        boolean settingsDiff = !same(left.getUserSettings(), right.getUserSettings());
        rows.add(new Row("settings.xml", orNone(left.getUserSettings()), orNone(right.getUserSettings()), settingsDiff,
                settingsDiff ? "Andere settings.xml heißt andere Mirrors, Zugangsdaten und Repositories." : null));

        boolean repoDiff = known(left.getLocalRepository()) && known(right.getLocalRepository())
                && !same(left.getLocalRepository(), right.getLocalRepository());
        rows.add(new Row("Lokales Repository", orDefault(left.getLocalRepository()), orDefault(right.getLocalRepository()), repoDiff,
                repoDiff ? "Getrennte lokale Repositories: ein Artefakt kann in einem vorhanden und im anderen kaputt oder gesperrt (.lastUpdated) sein." : null));

        String lp = profiles(left);
        String rp = profiles(right);
        boolean profDiff = !lp.equals(rp);
        rows.add(new Row("Profile (explizit)", lp, rp, profDiff,
                profDiff ? "Andere aktive Profile ändern Dependencies, Plugins und Properties." : null));

        boolean configDiff = !same(norm(left.getMavenConfig()), norm(right.getMavenConfig()));
        rows.add(new Row(".mvn/maven.config", configText(left.getMavenConfig()), configText(right.getMavenConfig()), configDiff,
                configDiff ? "Die Argumente aus .mvn/maven.config gehen nur in einen der beiden Läufe ein." : null));

        boolean jvmDiff = !same(norm(left.getJvmConfig()), norm(right.getJvmConfig()));
        rows.add(new Row(".mvn/jvm.config", configText(left.getJvmConfig()), configText(right.getJvmConfig()), jvmDiff,
                jvmDiff ? "JVM-Optionen (Speicher, --add-opens) unterscheiden sich." : null));

        boolean optsDiff = !same(norm(left.getMavenOpts()), norm(right.getMavenOpts()));
        rows.add(new Row("MAVEN_OPTS / VM-Optionen", orNone(left.getMavenOpts()), orNone(right.getMavenOpts()), optsDiff,
                optsDiff ? "Andere JVM-Optionen für Maven, etwa Speichergrenzen oder System-Properties." : null));

        return new SetupComparison(left, right, rows);
    }

    public BuildSetup getLeft() { return left; }

    public BuildSetup getRight() { return right; }

    public List<Row> getRows() { return Collections.unmodifiableList(rows); }

    public List<Row> getDifferences() {
        List<Row> out = new ArrayList<Row>();
        for (Row r : rows) {
            if (r.isDifferent()) {
                out.add(r);
            }
        }
        return out;
    }

    /** Ein Satz für die Diagnose-Karte, z. B. „IDE und Kommandozeile unterscheiden sich bei: JDK, Maven“. */
    public String summary() {
        List<Row> d = getDifferences();
        if (d.isEmpty()) {
            return left.getLabel() + " und " + right.getLabel() + " bauen mit derselben Umgebung.";
        }
        StringBuilder sb = new StringBuilder(left.getLabel()).append(" und ").append(right.getLabel())
                .append(" unterscheiden sich bei: ");
        for (int i = 0; i < d.size(); i++) {
            sb.append(i > 0 ? ", " : "").append(d.get(i).getAspect());
        }
        return sb.toString();
    }

    /** Als Markdown-Tabelle für „Bericht kopieren“. */
    public String markdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("| | ").append(left.getLabel()).append(" | ").append(right.getLabel()).append(" |\n");
        sb.append("| --- | --- | --- |\n");
        for (Row r : rows) {
            sb.append("| ").append(r.isDifferent() ? "**" + r.getAspect() + "**" : r.getAspect())
                    .append(" | ").append(cell(r.getLeft())).append(" | ").append(cell(r.getRight())).append(" |\n");
        }
        for (Row r : getDifferences()) {
            sb.append("\n- ").append(r.getHint());
        }
        return sb.append('\n').toString();
    }

    private static String cell(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    private static String javaText(BuildSetup s) {
        if (s.getJavaVersion() == null && s.getJavaHome() == null) {
            return "unbekannt";
        }
        return (s.getJavaVersion() == null ? "?" : s.getJavaVersion()) + (s.getJavaHome() == null ? "" : " (" + s.getJavaHome() + ")");
    }

    private static String mavenText(BuildSetup s) {
        String v = s.getMavenVersion() == null ? "unbekannt" : s.getMavenVersion();
        return s.getMavenSource() == null ? v : v + " – " + s.getMavenSource();
    }

    private static String profiles(BuildSetup s) {
        List<String> p = new ArrayList<String>(s.getProfiles());
        Collections.sort(p);
        return p.isEmpty() ? "(keine)" : String.join(", ", p);
    }

    private static String configText(String c) {
        return c == null ? "(nicht verwendet)" : c.trim().isEmpty() ? "(leer)" : c.trim().replaceAll("\\s+", " ");
    }

    private static String majorMinor(String v) {
        String[] parts = v.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : v;
    }

    private static String norm(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim().replaceAll("\\s+", " ");
    }

    private static String orNone(String s) {
        return s == null || s.isEmpty() ? "(keine)" : s;
    }

    private static String orDefault(String s) {
        return s == null || s.isEmpty() ? "(Standard)" : s;
    }

    private static boolean known(String s) {
        return s != null && !s.isEmpty();
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
