package io.github.bitshiftdev.mavendetective.core.testkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Baut den Text einer {@code pom.xml} für Tests, eingerückt mit vier Leerzeichen. Abschnitte
 * erscheinen in Mavens üblicher Reihenfolge; was nicht gesetzt ist, fehlt.
 *
 * <pre>
 * String xml = TestPom.of("org.app", "app", "1.0")
 *         .parent("org.app", "parent", "1.0")
 *         .property("lib.version", "2.0")
 *         .dependency("org.lib", "lib", "${lib.version}")
 *         .managed("org.lib", "other", "3.0")
 *         .xml();
 * </pre>
 */
public final class TestPom {

    private static final String UNIT = "    ";

    private final String groupId;
    private final String artifactId;
    private final String version;
    private String packaging;
    private String[] parent;
    private final Map<String, String> properties = new LinkedHashMap<String, String>();
    private final List<String> managed = new ArrayList<String>();
    private final List<String> dependencies = new ArrayList<String>();
    private final List<String> profiles = new ArrayList<String>();
    private final List<String> modules = new ArrayList<String>();

    private TestPom(String groupId, String artifactId, String version) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
    }

    /** {@code groupId} oder {@code version} {@code null}: wird nicht geschrieben (kommt dann vom Parent). */
    public static TestPom of(String groupId, String artifactId, String version) {
        return new TestPom(groupId, artifactId, version);
    }

    public String groupId() { return groupId != null ? groupId : parent == null ? null : parent[0]; }

    public String artifactId() { return artifactId; }

    public String version() { return version != null ? version : parent == null ? null : parent[2]; }

    /** {@code <exclusions>} für {@code extra} von {@link #dependency}/{@link #managed}; je Eintrag {@code "g:a"}. */
    public static String exclusions(String... gas) {
        StringBuilder sb = new StringBuilder("<exclusions>");
        for (String ga : gas) {
            String[] p = ga.split(":", -1);
            sb.append("<exclusion><groupId>").append(p[0]).append("</groupId><artifactId>").append(p[1])
                    .append("</artifactId></exclusion>");
        }
        return sb.append("</exclusions>").toString();
    }

    public TestPom packaging(String value) {
        this.packaging = value;
        return this;
    }

    public TestPom parent(String g, String a, String v) {
        this.parent = new String[] {g, a, v};
        return this;
    }

    public TestPom property(String name, String value) {
        properties.put(name, value);
        return this;
    }

    public TestPom module(String name) {
        modules.add(name);
        return this;
    }

    /** Dependency unter {@code dependencies}; {@code extra} = weitere Kind-Elemente als XML, z. B. {@code <scope>test</scope>}. */
    public TestPom dependency(String g, String a, String v, String... extra) {
        dependencies.add(dependencyXml(g, a, v, extra, UNIT + UNIT));
        return this;
    }

    /** Eintrag unter {@code dependencyManagement/dependencies}. */
    public TestPom managed(String g, String a, String v, String... extra) {
        managed.add(dependencyXml(g, a, v, extra, UNIT + UNIT + UNIT));
        return this;
    }

    /** BOM-Import unter {@code dependencyManagement}. */
    public TestPom bom(String g, String a, String v) {
        return managed(g, a, v, "<type>pom</type>", "<scope>import</scope>");
    }

    /** Profil mit eigener Dependency – Editor und Scanner sollen es nie anfassen. */
    public TestPom profileDependency(String id, String g, String a, String v) {
        String inner = UNIT + UNIT;
        profiles.add(inner + "<profile>\n"
                + inner + UNIT + "<id>" + id + "</id>\n"
                + inner + UNIT + "<dependencies>\n"
                + dependencyXml(g, a, v, new String[0], inner + UNIT + UNIT)
                + inner + UNIT + "</dependencies>\n"
                + inner + "</profile>\n");
        return this;
    }

    public String xml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n");
        sb.append(UNIT).append("<modelVersion>4.0.0</modelVersion>\n");
        if (parent != null) {
            sb.append(UNIT).append("<parent>\n");
            element(sb, UNIT + UNIT, "groupId", parent[0]);
            element(sb, UNIT + UNIT, "artifactId", parent[1]);
            element(sb, UNIT + UNIT, "version", parent[2]);
            sb.append(UNIT).append("</parent>\n");
        }
        element(sb, UNIT, "groupId", groupId);
        element(sb, UNIT, "artifactId", artifactId);
        element(sb, UNIT, "version", version);
        element(sb, UNIT, "packaging", packaging);
        if (!modules.isEmpty()) {
            sb.append(UNIT).append("<modules>\n");
            for (String m : modules) {
                element(sb, UNIT + UNIT, "module", m);
            }
            sb.append(UNIT).append("</modules>\n");
        }
        if (!properties.isEmpty()) {
            sb.append(UNIT).append("<properties>\n");
            for (Map.Entry<String, String> p : properties.entrySet()) {
                element(sb, UNIT + UNIT, p.getKey(), p.getValue());
            }
            sb.append(UNIT).append("</properties>\n");
        }
        if (!managed.isEmpty()) {
            sb.append(UNIT).append("<dependencyManagement>\n");
            sb.append(UNIT + UNIT).append("<dependencies>\n");
            for (String d : managed) {
                sb.append(d);
            }
            sb.append(UNIT + UNIT).append("</dependencies>\n");
            sb.append(UNIT).append("</dependencyManagement>\n");
        }
        if (!dependencies.isEmpty()) {
            sb.append(UNIT).append("<dependencies>\n");
            for (String d : dependencies) {
                sb.append(d);
            }
            sb.append(UNIT).append("</dependencies>\n");
        }
        if (!profiles.isEmpty()) {
            sb.append(UNIT).append("<profiles>\n");
            for (String p : profiles) {
                sb.append(p);
            }
            sb.append(UNIT).append("</profiles>\n");
        }
        return sb.append("</project>\n").toString();
    }

    private static String dependencyXml(String g, String a, String v, String[] extra, String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("<dependency>\n");
        element(sb, indent + UNIT, "groupId", g);
        element(sb, indent + UNIT, "artifactId", a);
        element(sb, indent + UNIT, "version", v);
        for (String e : extra) {
            sb.append(indent).append(UNIT).append(e).append('\n');
        }
        return sb.append(indent).append("</dependency>\n").toString();
    }

    private static void element(StringBuilder sb, String indent, String name, String value) {
        if (value != null) {
            sb.append(indent).append('<').append(name).append('>').append(value).append("</").append(name).append(">\n");
        }
    }
}
