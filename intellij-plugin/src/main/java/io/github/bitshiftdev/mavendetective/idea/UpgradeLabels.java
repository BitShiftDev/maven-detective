package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.ui.SimpleTextAttributes;
import io.github.bitshiftdev.mavendetective.core.deps.MavenVersion;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectiveColors;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.EnumSet;

/**
 * Kennzeichen im Upgrade-Simulator (UI-Plan U5): Marker für Versionsvorschläge (heute, neuer, älter,
 * Major-Sprung) und Zeichen für Änderungen im Baum (↑ ↓ + − ~) mit Farbe. Farbe ist nie das einzige
 * Merkmal – Zeichen und Wort sagen dasselbe.
 */
final class UpgradeLabels {

    /** Einordnung einer vorgeschlagenen Version gegenüber der heutigen. */
    enum Marker { CURRENT, NEWER, OLDER, MAJOR }

    private UpgradeLabels() {
    }

    /** Marker für {@code candidate}; ohne heutige Version keine. */
    static @NotNull EnumSet<Marker> markers(@NotNull String candidate, @Nullable String current) {
        EnumSet<Marker> out = EnumSet.noneOf(Marker.class);
        if (current == null || current.isEmpty()) {
            return out;
        }
        int c = MavenVersion.compare(candidate, current);
        out.add(c == 0 ? Marker.CURRENT : c > 0 ? Marker.NEWER : Marker.OLDER);
        String a = major(candidate);
        String b = major(current);
        if (c != 0 && a != null && b != null && !a.equals(b)) {
            out.add(Marker.MAJOR);
        }
        return out;
    }

    /** Führende Zahl ({@code 2} aus {@code 2.17.1}, {@code 31} aus {@code 31.1-jre}); {@code null}, wenn keine. */
    static @Nullable String major(@NotNull String version) {
        int i = 0;
        while (i < version.length() && Character.isDigit(version.charAt(i))) {
            i++;
        }
        return i == 0 ? null : version.substring(0, i).replaceFirst("^0+(?=\\d)", "");
    }

    static @Nls @NotNull String markerText(@NotNull Marker m) {
        return MavenDetectiveBundle.message(switch (m) {
            case CURRENT -> "upgrade.marker.current";
            case NEWER -> "upgrade.marker.newer";
            case OLDER -> "upgrade.marker.older";
            case MAJOR -> "upgrade.marker.major";
        });
    }

    /** Zeichen für die Art der Änderung. */
    static @NotNull String symbol(@NotNull UpgradeSimulator.Kind kind) {
        return switch (kind) {
            case ADDED -> "+";
            case REMOVED -> "−";
            case UPGRADED -> "↑";
            case DOWNGRADED -> "↓";
            case SCOPE -> "~";
        };
    }

    static @Nls @NotNull String word(@NotNull UpgradeSimulator.Kind kind) {
        return MavenDetectiveBundle.message(switch (kind) {
            case ADDED -> "upgrade.kind.added";
            case REMOVED -> "upgrade.kind.removed";
            case UPGRADED -> "upgrade.kind.upgraded";
            case DOWNGRADED -> "upgrade.kind.downgraded";
            case SCOPE -> "upgrade.kind.scope";
        });
    }

    static @NotNull Color color(@NotNull UpgradeSimulator.Kind kind) {
        return switch (kind) {
            case ADDED -> DetectiveColors.DELTA_ADDED;
            case REMOVED -> DetectiveColors.DELTA_REMOVED;
            case UPGRADED -> DetectiveColors.DELTA_UP;
            case DOWNGRADED -> DetectiveColors.DELTA_DOWN;
            case SCOPE -> DetectiveColors.DELTA_SCOPE;
        };
    }

    /** Fettes, farbiges Zeichen. */
    static @NotNull SimpleTextAttributes symbolAttributes(@NotNull UpgradeSimulator.Kind kind) {
        return new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color(kind));
    }

    /** Neuer Konflikt (verdrängte höhere Version): rotes „!“. */
    static @NotNull SimpleTextAttributes riskAttributes() {
        return new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, DetectiveColors.DELTA_DOWN);
    }
}
