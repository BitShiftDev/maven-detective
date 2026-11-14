package io.github.bitshiftdev.mavendetective.core.deps;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;

/**
 * Versionsvergleich wie Mavens {@code ComparableVersion} (maven-artifact 3.9): {@code .} trennt Elemente,
 * {@code -} und der Wechsel zwischen Ziffern und Buchstaben öffnen eine Unterliste, Zahlen werden beliebig groß
 * numerisch verglichen, Qualifier in der Reihenfolge {@code alpha < beta < milestone < rc < snapshot < (final) < sp},
 * unbekannte dahinter alphabetisch. Nullen und „final“ am Ende jeder (Unter-)Liste zählen nicht:
 * {@code 1.0 == 1 == 1-final}, {@code 1.0.0-rc1 == 1-rc1}; {@code 1.0-1 < 1.0.1}.
 *
 * <p>Gebraucht, um bei der Konflikt-Matrix die höchste Version vorzuschlagen und im Upgrade-Simulator Auf- von
 * Abwärts zu unterscheiden – Maven selbst wählt nicht die höchste (nearest wins).
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
    private static final String RELEASE = "5";

    /** Unterliste; leer zählt wie ein fehlendes Element. */
    @SuppressWarnings("serial")
    private static final class Items extends ArrayList<Object> {
    }

    private MavenVersion() {
    }

    public static int compare(String a, String b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        return signum(compareItem(parse(a), parse(b)));
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

    private static int signum(int c) {
        return c < 0 ? -1 : c > 0 ? 1 : 0;
    }

    // ------------------------------------------------------------------ Zerlegen

    /** Elemente: {@link BigInteger} (Zahl), {@link String} (Qualifier, normalisiert), {@link Items} (Unterliste). */
    static Items parse(String version) {
        String v = version.trim().toLowerCase(Locale.ENGLISH);
        Items root = new Items();
        Items list = root;
        Deque<Items> stack = new ArrayDeque<Items>();
        stack.push(list);
        boolean digit = false;
        int start = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '.' || c == '-') {
                list.add(i == start ? BigInteger.ZERO : item(digit, v.substring(start, i)));
                start = i + 1;
                if (c == '-') {
                    Items sub = new Items();
                    list.add(sub);
                    list = sub;
                    stack.push(list);
                }
            } else if (Character.isDigit(c)) {
                if (!digit && i > start) {
                    // Buchstaben direkt vor Ziffern: "rc1" → [rc, [1]]
                    if (!list.isEmpty()) {
                        Items sub = new Items();
                        list.add(sub);
                        list = sub;
                        stack.push(list);
                    }
                    list.add(qualifier(v.substring(start, i), true));
                    start = i;
                    Items sub = new Items();
                    list.add(sub);
                    list = sub;
                    stack.push(list);
                }
                digit = true;
            } else {
                if (digit && i > start) {
                    // Ziffern direkt vor Buchstaben: "1jre" → [1, [jre]]
                    list.add(item(true, v.substring(start, i)));
                    start = i;
                    Items sub = new Items();
                    list.add(sub);
                    list = sub;
                    stack.push(list);
                }
                digit = false;
            }
        }
        if (v.length() > start) {
            if (!digit && !list.isEmpty()) {
                Items sub = new Items();
                list.add(sub);
                list = sub;
                stack.push(list);
            }
            list.add(item(digit, v.substring(start)));
        }
        while (!stack.isEmpty()) {
            normalize(stack.pop());
        }
        return root;
    }

    private static Object item(boolean digit, String text) {
        return digit ? new BigInteger(text) : qualifier(text, false);
    }

    /** Kurzformen {@code a/b/m} nur direkt vor einer Ziffer ({@code 1.0-a1}); Aliase wie Maven. */
    private static String qualifier(String q, boolean followedByDigit) {
        if (followedByDigit && q.length() == 1) {
            switch (q.charAt(0)) {
                case 'a': return "alpha";
                case 'b': return "beta";
                case 'm': return "milestone";
                default: break;
            }
        }
        if ("ga".equals(q) || "final".equals(q) || "release".equals(q)) {
            return "";
        }
        return "cr".equals(q) ? "rc" : q;
    }

    /** Nullen, „final“ und leere Listen am Ende entfernen; hört beim ersten echten Wert auf, der keine Liste ist. */
    private static void normalize(Items list) {
        for (int i = list.size() - 1; i >= 0; i--) {
            Object last = list.get(i);
            if (isNull(last)) {
                list.remove(i);
            } else if (!(last instanceof Items)) {
                break;
            }
        }
    }

    private static boolean isNull(Object item) {
        if (item instanceof BigInteger) {
            return ((BigInteger) item).signum() == 0;
        }
        if (item instanceof String) {
            return RELEASE.equals(comparable((String) item));
        }
        return ((Items) item).isEmpty();
    }

    // ------------------------------------------------------------------ Vergleichen

    /** {@code q == null} steht für ein fehlendes Element. */
    private static int compareItem(Object p, Object q) {
        if (p instanceof BigInteger) {
            BigInteger n = (BigInteger) p;
            if (q == null) {
                return n.signum() == 0 ? 0 : 1;
            }
            return q instanceof BigInteger ? n.compareTo((BigInteger) q) : 1;   // 1.1 > 1-sp, 1.1 > 1-1
        }
        if (p instanceof String) {
            String s = comparable((String) p);
            if (q == null) {
                return s.compareTo(RELEASE);
            }
            return q instanceof String ? s.compareTo(comparable((String) q)) : -1;   // 1.any < 1.1, 1.any < 1-1
        }
        Items list = (Items) p;
        if (q == null) {
            for (Object item : list) {
                int c = compareItem(item, null);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        }
        if (q instanceof BigInteger) {
            return -1;   // 1-1 < 1.0.x
        }
        if (q instanceof String) {
            return 1;    // 1-1 > 1-sp
        }
        Iterator<Object> left = list.iterator();
        Iterator<Object> right = ((Items) q).iterator();
        while (left.hasNext() || right.hasNext()) {
            Object l = left.hasNext() ? left.next() : null;
            Object r = right.hasNext() ? right.next() : null;
            int c = l == null ? (r == null ? 0 : -compareItem(r, null)) : compareItem(l, r);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** Bekannte Qualifier als Rang ("0"…"6"), unbekannte als "7-name" – verglichen als Text wie in Maven. */
    private static String comparable(String qualifier) {
        for (int i = 0; i < QUALIFIERS.length; i++) {
            if (QUALIFIERS[i].equals(qualifier)) {
                return String.valueOf(i);
            }
        }
        return QUALIFIERS.length + "-" + qualifier;
    }
}
