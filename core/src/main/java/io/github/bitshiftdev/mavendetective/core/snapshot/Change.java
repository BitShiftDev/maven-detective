package io.github.bitshiftdev.mavendetective.core.snapshot;

import io.github.bitshiftdev.mavendetective.core.Texts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Eine Änderung zwischen dem letzten grünen Build und jetzt. */
public final class Change {

    /** Bereich der Änderung; die Reihenfolge ist zugleich die Wichtigkeit (oben = zuerst zeigen). */
    public enum Area {
        JDK,
        MAVEN,
        OFFLINE,
        SETTINGS,
        PROFILES,
        MIRRORS,
        PARENT,
        PROPERTY,
        PLUGIN,
        DEPENDENCY,
        MODULES,
        EFFECTIVE_POM;

        public String label() {
            return Texts.t("change.area." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private final Area area;
    private final String subject;
    private final String before;
    private final String after;
    private final List<String> modules = new ArrayList<String>();

    public Change(Area area, String subject, String before, String after) {
        this.area = area;
        this.subject = subject;
        this.before = before;
        this.after = after;
    }

    public Area getArea() { return area; }

    /** Was sich geändert hat, z. B. {@code org.springframework.boot:spring-boot-starter-parent}; bei JDK/Maven leer. */
    public String getSubject() { return subject; }

    /** Wert im letzten grünen Build; {@code null} = gab es nicht. */
    public String getBefore() { return before; }

    /** Wert jetzt; {@code null} = gibt es nicht mehr. */
    public String getAfter() { return after; }

    /** Betroffene Module (groupId:artifactId); leer bei Umgebungsänderungen. */
    public List<String> getModules() { return Collections.unmodifiableList(modules); }

    void addModule(String module) {
        if (module != null && !modules.contains(module)) {
            modules.add(module);
        }
    }

    boolean sameAs(Change o) {
        return area == o.area && eq(subject, o.subject) && eq(before, o.before) && eq(after, o.after);
    }

    /** Eine Zeile Klartext, z. B. „Parent …:spring-boot-starter-parent 3.4.1 → 3.5.0“. */
    public String text() {
        StringBuilder sb = new StringBuilder(area.label());
        String what = subject == null || subject.isEmpty() ? "" : " " + subject;
        if (before == null) {
            sb.append(' ').append(Texts.t("change.added")).append(what).append(' ').append(after);
        } else if (after == null) {
            sb.append(' ').append(Texts.t("change.removed")).append(what).append(" (").append(Texts.t("change.was", before)).append(')');
        } else {
            sb.append(what).append(' ').append(before).append(" → ").append(after);
        }
        if (modules.size() > 1) {
            sb.append(" (").append(Texts.t("change.modules", modules.size())).append(')');
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return text();
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
