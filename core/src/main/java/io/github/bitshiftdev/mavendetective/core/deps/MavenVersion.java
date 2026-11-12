package io.github.bitshiftdev.mavendetective.core.deps;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Versionsvergleich nach Mavens Regeln (vereinfachte {@code ComparableVersion}): Zahlen numerisch,
 * Qualifier in der Reihenfolge {@code alpha < beta < milestone < rc < snapshot < (final) < sp},
 * unbekannte Qualifier dahinter alphabetisch. {@code 1.0 == 1.0.0 == 1.0-final}.
 *
 * <p>Gebraucht, um bei der Konflikt-Matrix die höchste Version vorzuschlagen – Maven selbst wählt
 * nicht die höchste (nearest wins), der Vorschlag ist nur der übliche Ausweg.
 */
public final class MavenVersion {

    /** Aufsteigend, {@code null} zuerst. */
    public static final Comparator<String> ORDER = new Comparator<String>() {
        @Override
        public int compare(String a, String b) {
            return MavenVersion.compare(a, b);
        }
    };

    private static final String[] QUALIFIERS = {"alpha", "beta", "milestone", "rc", "snapshot", "", "sp"};

    private MavenVersion() {
    }

    public static int compare(String a, String b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        List<Object> x = tokens(a);
        List<Object> y = tokens(b);
        int n = Math.max(x.size(), y.size());
        for (int i = 0; i < n; i++) {
            Object p = i < x.size() ? x.get(i) : null;
            Object q = i < y.size() ? y.get(i) : null;
            int c = compareItem(p, q);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** Die höchste der Versionen oder {@code null} bei leerer Eingabe. */
    public static String max(Iterable<String> versions) {
        String best = null;
        for (String v : versions) {
            if (v != null && (best == null || compare(v, best) > 0)) {
                best = v;
            }
        }
        return best;
    }

    /** Zahl ({@link Long}) oder Qualifier ({@link String}, kleingeschrieben und normalisiert). */
    static List<Object> tokens(String version) {
        List<Object> out = new ArrayList<Object>();
        String v = version.trim().toLowerCase(Locale.ROOT);
        StringBuilder cur = new StringBuilder();
        boolean digits = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '.' || c == '-' || c == '_' || c == '+') {
                flush(out, cur, digits);
                continue;
            }
            boolean d = Character.isDigit(c);
            if (cur.length() > 0 && d != digits) {
                flush(out, cur, digits);
            }
            digits = d;
            cur.append(c);
        }
        flush(out, cur, digits);
        // Nullen und „final“ am Ende zählen nicht: 1.0 == 1 == 1-final
        while (!out.isEmpty()) {
            Object last = out.get(out.size() - 1);
            if (Long.valueOf(0).equals(last) || "".equals(last)) {
                out.remove(out.size() - 1);
            } else {
                break;
            }
        }
        return out;
    }

    private static void flush(List<Object> out, StringBuilder cur, boolean digits) {
        if (cur.length() == 0) {
            return;
        }
        String s = cur.toString();
        cur.setLength(0);
        if (digits) {
            try {
                out.add(Long.valueOf(s.length() > 18 ? s.substring(0, 18) : s));
            } catch (NumberFormatException e) {
                out.add(s);
            }
        } else {
            out.add(normalize(s));
        }
    }

    private static String normalize(String q) {
        if ("a".equals(q)) {
            return "alpha";
        }
        if ("b".equals(q)) {
            return "beta";
        }
        if ("m".equals(q)) {
            return "milestone";
        }
        if ("cr".equals(q)) {
            return "rc";
        }
        if ("ga".equals(q) || "final".equals(q) || "release".equals(q)) {
            return "";
        }
        return q;
    }

    /** {@code null} steht für ein fehlendes Element (wie 0 bzw. Release). */
    private static int compareItem(Object p, Object q) {
        if (p == null && q == null) {
            return 0;
        }
        if (p == null) {
            return -compareItem(q, null);
        }
        if (p instanceof Long) {
            if (q == null) {
                return ((Long) p) == 0L ? 0 : 1;
            }
            if (q instanceof Long) {
                return ((Long) p).compareTo((Long) q);
            }
            return 1; // Zahl schlägt Qualifier: 1.1 > 1-rc
        }
        String s = (String) p;
        if (q instanceof Long) {
            return -1;
        }
        String t = q == null ? "" : (String) q;
        int i = rank(s);
        int j = rank(t);
        if (i != j) {
            return i < j ? -1 : 1;
        }
        return i == QUALIFIERS.length ? s.compareTo(t) : 0;
    }

    private static int rank(String q) {
        for (int i = 0; i < QUALIFIERS.length; i++) {
            if (QUALIFIERS[i].equals(q)) {
                return i;
            }
        }
        return QUALIFIERS.length;
    }
}
