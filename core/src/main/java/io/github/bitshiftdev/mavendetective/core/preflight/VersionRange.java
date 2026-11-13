package io.github.bitshiftdev.mavendetective.core.preflight;

import io.github.bitshiftdev.mavendetective.core.deps.MavenVersion;

import java.util.ArrayList;
import java.util.List;

/**
 * Versionsbereiche wie im Maven Enforcer: {@code 17} heißt „mindestens 17“, {@code [1.8,11)},
 * {@code (,21]}, {@code [17]} und Listen {@code [1.8,9),[11,)} (eine passende Teilmenge genügt).
 */
final class VersionRange {

    private VersionRange() {
    }

    /** Ob {@code version} im Bereich liegt; unlesbare Bereiche gelten als erfüllt (kein falscher Alarm). */
    static boolean contains(String range, String version) {
        if (range == null || version == null) {
            return true;
        }
        String r = range.trim();
        if (r.isEmpty()) {
            return true;
        }
        if (r.charAt(0) != '[' && r.charAt(0) != '(') {
            return MavenVersion.compare(version, r) >= 0;
        }
        List<String> sets = split(r);
        if (sets.isEmpty()) {
            return true;
        }
        for (String set : sets) {
            if (inSet(set, version)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> split(String r) {
        List<String> out = new ArrayList<String>();
        int start = -1;
        for (int i = 0; i < r.length(); i++) {
            char c = r.charAt(i);
            if (c == '[' || c == '(') {
                start = i;
            } else if ((c == ']' || c == ')') && start >= 0) {
                out.add(r.substring(start, i + 1));
                start = -1;
            }
        }
        return out;
    }

    private static boolean inSet(String set, String version) {
        boolean lowerInclusive = set.charAt(0) == '[';
        boolean upperInclusive = set.charAt(set.length() - 1) == ']';
        String body = set.substring(1, set.length() - 1);
        int comma = body.indexOf(',');
        if (comma < 0) {
            return MavenVersion.compare(version, body.trim()) == 0;
        }
        String lower = body.substring(0, comma).trim();
        String upper = body.substring(comma + 1).trim();
        if (!lower.isEmpty()) {
            int c = MavenVersion.compare(version, lower);
            if (c < 0 || (c == 0 && !lowerInclusive)) {
                return false;
            }
        }
        if (!upper.isEmpty()) {
            int c = MavenVersion.compare(version, upper);
            if (c > 0 || (c == 0 && !upperInclusive)) {
                return false;
            }
        }
        return true;
    }
}
