package io.github.bitshiftdev.mavendetective.core.deps;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest die Ausgabe von {@code mvn dependency:tree} (am besten mit {@code -Dverbose}), aus dem
 * Maven-Log mit {@code [INFO]}-Präfix oder aus {@code -DoutputFile}. Bei Multi-Modul-Builds
 * entsteht pro Modul eine Wurzel.
 *
 * <pre>
 * [INFO] com.example:app:jar:1.0
 * [INFO] +- org.springframework:spring-core:jar:6.2.1:compile
 * [INFO] |  \- (org.slf4j:slf4j-api:jar:1.7.36:compile - omitted for conflict with 2.0.16)
 * [INFO] \- com.fasterxml.jackson.core:jackson-databind:jar:2.18.2:compile (version managed from 2.17.0)
 * </pre>
 */
public final class DependencyTreeParser {

    private static final Pattern PREFIX = Pattern.compile("^.*?\\[INFO\\] ?");
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");
    private static final Pattern ROOT = Pattern.compile("^([\\w.\\-]+):([\\w.\\-]+):([\\w.\\-]+):([\\w.\\-${}]+)$");
    private static final Pattern CONFLICT = Pattern.compile("omitted for conflict with ([^\\s;)]+)");
    private static final Pattern MANAGED_VERSION = Pattern.compile("version managed from ([^\\s;)]+)");
    private static final Pattern MANAGED_SCOPE = Pattern.compile("scope (?:managed|updated) from ([^\\s;)]+)");

    private DependencyTreeParser() {
    }

    public static List<DepNode> parse(Reader reader) throws IOException {
        BufferedReader in = reader instanceof BufferedReader ? (BufferedReader) reader : new BufferedReader(reader);
        List<DepNode> roots = new ArrayList<DepNode>();
        List<DepNode> stack = new ArrayList<DepNode>();
        String raw;
        while ((raw = in.readLine()) != null) {
            String line = extendedTokens(ANSI.matcher(raw).replaceAll(""));
            Matcher p = PREFIX.matcher(line);
            if (p.find()) {
                line = line.substring(p.end());
            } else if (line.startsWith("[")) {
                continue; // [WARNING], [ERROR] …
            }
            line = trimRight(line);
            if (line.isEmpty()) {
                continue;
            }
            int indent = treeIndent(line);
            if (indent == 0) {
                Matcher r = ROOT.matcher(line);
                if (r.matches()) {
                    DepNode root = new DepNode(r.group(1), r.group(2), r.group(3), null, r.group(4), null);
                    roots.add(root);
                    stack.clear();
                    stack.add(root);
                } else {
                    stack.clear();
                }
                continue;
            }
            if (stack.isEmpty() || indent % 3 != 0) {
                continue;
            }
            DepNode node = node(line.substring(indent));
            if (node == null) {
                continue;
            }
            int depth = indent / 3;
            while (stack.size() > depth) {
                stack.remove(stack.size() - 1);
            }
            if (stack.size() < depth) {
                continue; // Einrückung passt nicht – Zeile überspringen statt falsch zuordnen
            }
            stack.get(stack.size() - 1).add(node);
            stack.add(node);
        }
        return roots;
    }

    /**
     * {@code -Dtokens=extended} zeichnet den Baum mit Rahmenzeichen ({@code ├─ }, {@code └─ }, {@code │  });
     * je Ebene ebenfalls drei Zeichen, also auf die Standardzeichen abbilden.
     */
    static String extendedTokens(String line) {
        if (line.indexOf('\u2500') < 0 && line.indexOf('\u2502') < 0) {
            return line;
        }
        return line.replace('\u251C', '+').replace('\u2514', '\\').replace('\u2500', '-').replace('\u2502', '|');
    }

    /** Länge des Präfixes aus {@code |}, {@code +-}, {@code \-} und Leerzeichen. */
    private static int treeIndent(String line) {
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '|' || c == '+' || c == '\\' || c == '-' || c == ' ') {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    static DepNode node(String text) {
        String t = text.trim();
        boolean omitted = t.startsWith("(");
        if (omitted) {
            t = t.substring(1);
            if (t.endsWith(")")) {
                t = t.substring(0, t.length() - 1);
            }
        }
        int space = t.indexOf(' ');
        String coords = space < 0 ? t : t.substring(0, space);
        String rest = space < 0 ? "" : t.substring(space);
        String[] p = coords.split(":");
        DepNode node;
        if (p.length == 5) {
            node = new DepNode(p[0], p[1], p[2], null, p[3], p[4]);
        } else if (p.length == 6) {
            node = new DepNode(p[0], p[1], p[2], p[3], p[4], p[5]);
        } else if (p.length == 4) {
            node = new DepNode(p[0], p[1], p[2], null, p[3], null);
        } else {
            return null;
        }
        Matcher m = MANAGED_VERSION.matcher(rest);
        if (m.find()) {
            node.setPremanagedVersion(m.group(1));
        }
        m = MANAGED_SCOPE.matcher(rest);
        if (m.find()) {
            node.setPremanagedScope(m.group(1));
        }
        m = CONFLICT.matcher(rest);
        if (m.find()) {
            node.setState(DepNode.State.CONFLICT).setRelatedVersion(m.group(1));
        } else if (rest.contains("omitted for duplicate")) {
            node.setState(DepNode.State.DUPLICATE);
        } else if (rest.contains("omitted for cycle")) {
            node.setState(DepNode.State.CYCLE);
        } else if (omitted) {
            node.setState(DepNode.State.DUPLICATE);
        }
        return node;
    }

    private static String trimRight(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}
