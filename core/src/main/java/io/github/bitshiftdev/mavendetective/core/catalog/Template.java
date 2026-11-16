package io.github.bitshiftdev.mavendetective.core.catalog;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Platzhalter in Katalog-Texten: {@code {artifact}} setzt eine Variable ein,
 * {@code {artifact|ein Artifact}} nimmt den Text nach {@code |}, wenn die Variable fehlt, und
 * {@code {class,member|eine Klasse}} nimmt die erste vorhandene Variable.
 */
public final class Template {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*(?:,[A-Za-z][A-Za-z0-9]*)*)(?:\\|([^{}]*))?\\}");

    private Template() {
    }

    /** Text für Menschen: doppelte Leerzeichen (von leeren Platzhaltern) werden zu einem. */
    public static String render(String template, Map<String, String> vars) {
        String s = substitute(template, vars);
        return s == null ? null : s.replaceAll("  +", " ").trim();
    }

    /** Parameter eines Quick-Fixes: Werte bleiben, wie sie sind (auch Leerzeichen in Pfaden oder Argumenten). */
    public static String renderParameter(String template, Map<String, String> vars) {
        return substitute(template, vars);
    }

    private static String substitute(String template, Map<String, String> vars) {
        if (template == null) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String value = null;
            for (String name : m.group(1).split(",")) {
                String v = vars.get(name);
                if (v != null && !v.isEmpty()) {
                    value = v;
                    break;
                }
            }
            if (value == null) {
                value = m.group(2) == null ? "" : m.group(2);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
