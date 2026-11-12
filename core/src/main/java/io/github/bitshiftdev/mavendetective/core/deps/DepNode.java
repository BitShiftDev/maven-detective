package io.github.bitshiftdev.mavendetective.core.deps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ein Knoten im aufgelösten Dependency-Baum eines Moduls – aus {@code mvn dependency:tree -Dverbose}
 * ({@link DependencyTreeParser}) oder aus IntelliJs Maven-Modell. Die Wurzel ist das Modul selbst.
 */
public final class DepNode {

    /** Wie Maven den Knoten behandelt hat. */
    public enum State {
        /** Im Classpath. */
        INCLUDED,
        /** Verdrängt, weil eine andere Version gewonnen hat (nearest wins). */
        CONFLICT,
        /** Gleiche Version woanders schon im Baum. */
        DUPLICATE,
        /** Zyklus. */
        CYCLE,
        /** Durch eine Exclusion entfernt. */
        EXCLUDED
    }

    private final String groupId;
    private final String artifactId;
    private final String type;
    private final String classifier;
    private final String version;
    private final String scope;
    private State state = State.INCLUDED;
    private String relatedVersion;
    private String premanagedVersion;
    private String premanagedScope;
    private DepNode parent;
    private final List<DepNode> children = new ArrayList<DepNode>();

    public DepNode(String groupId, String artifactId, String type, String classifier, String version, String scope) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.type = type == null || type.isEmpty() ? "jar" : type;
        this.classifier = classifier == null || classifier.isEmpty() ? null : classifier;
        this.version = version;
        this.scope = scope;
    }

    public DepNode add(DepNode child) {
        child.parent = this;
        children.add(child);
        return child;
    }

    public DepNode setState(State state) { this.state = state; return this; }
    /** Bei {@link State#CONFLICT}: die Version, die gewonnen hat. */
    public DepNode setRelatedVersion(String v) { this.relatedVersion = v; return this; }
    /** Version vor dem Eingriff von {@code dependencyManagement} („version managed from …“). */
    public DepNode setPremanagedVersion(String v) { this.premanagedVersion = v; return this; }
    public DepNode setPremanagedScope(String s) { this.premanagedScope = s; return this; }

    public String getGroupId() { return groupId; }
    public String getArtifactId() { return artifactId; }
    public String getType() { return type; }
    public String getClassifier() { return classifier; }
    public String getVersion() { return version; }
    public String getScope() { return scope; }
    public State getState() { return state; }
    public String getRelatedVersion() { return relatedVersion; }
    public String getPremanagedVersion() { return premanagedVersion; }
    public String getPremanagedScope() { return premanagedScope; }
    public DepNode getParent() { return parent; }
    public List<DepNode> getChildren() { return Collections.unmodifiableList(children); }

    public String ga() {
        return groupId + ":" + artifactId;
    }

    public String gav() {
        return groupId + ":" + artifactId + ":" + version;
    }

    /** Tiefe im Baum; direkte Dependencies haben 1. */
    public int depth() {
        int d = 0;
        for (DepNode n = parent; n != null; n = n.parent) {
            d++;
        }
        return d;
    }

    /** Pfad von der Wurzel (exklusive) bis zu diesem Knoten (inklusive). */
    public List<DepNode> path() {
        List<DepNode> out = new ArrayList<DepNode>();
        for (DepNode n = this; n != null && n.parent != null; n = n.parent) {
            out.add(0, n);
        }
        return out;
    }

    /** Pfad als Text „a:b:1 → c:d:2 → …“. */
    public String pathText() {
        StringBuilder sb = new StringBuilder();
        for (DepNode n : path()) {
            if (sb.length() > 0) {
                sb.append(" → ");
            }
            sb.append(n.gav());
        }
        return sb.toString();
    }

    /** Alle Knoten mit diesen Koordinaten in Breitensuche (der nächste zuerst). */
    public List<DepNode> findAll(String groupId, String artifactId) {
        List<DepNode> out = new ArrayList<DepNode>();
        List<DepNode> level = new ArrayList<DepNode>(children);
        int guard = 0;
        while (!level.isEmpty() && guard++ < 100) {
            List<DepNode> next = new ArrayList<DepNode>();
            for (DepNode n : level) {
                if (groupId.equals(n.groupId) && artifactId.equals(n.artifactId)) {
                    out.add(n);
                }
                next.addAll(n.children);
            }
            level = next;
        }
        return out;
    }

    /** Der Knoten, der im Classpath gelandet ist, oder {@code null}. */
    public DepNode findIncluded(String groupId, String artifactId) {
        for (DepNode n : findAll(groupId, artifactId)) {
            if (n.state == State.INCLUDED) {
                return n;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return gav() + (scope == null ? "" : " (" + scope + ")") + (state == State.INCLUDED ? "" : " [" + state + "]");
    }
}
