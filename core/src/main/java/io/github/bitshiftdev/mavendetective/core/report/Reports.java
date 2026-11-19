package io.github.bitshiftdev.mavendetective.core.report;

import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import io.github.bitshiftdev.mavendetective.core.model.SessionInfo;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotDiff;

import java.util.ArrayList;
import java.util.List;

/** Darstellung von Diagnosen: als Konsolen-Karte (CLI, Extension) und als Markdown („Bericht kopieren“). */
public final class Reports {

    private static final int MAX_EVIDENCE = 6;
    private static final int MAX_CHANGES = 6;

    private Reports() {
    }

    /** Karte für die Konsole, eine Zeile pro Eintrag – ohne Farben, damit CI-Logs lesbar bleiben. */
    public static List<String> consoleCard(List<Diagnosis> diagnoses) {
        return consoleCard(diagnoses, null);
    }

    /** Wie {@link #consoleCard(List)}, plus „Seit dem letzten grünen Build“, wenn ein Vergleich vorliegt. */
    public static List<String> consoleCard(List<Diagnosis> diagnoses, SnapshotDiff changes) {
        List<String> out = new ArrayList<String>();
        if (diagnoses.isEmpty()) {
            return out;
        }
        Diagnosis d = diagnoses.get(0);
        out.add("+-- Maven Detective · Build Doctor ----------------------------");
        out.add("| " + headline(d));
        out.add("| " + Texts.t("card.cause") + " " + d.getCause());
        int shown = 0;
        for (Evidence e : d.getEvidence()) {
            if (shown++ >= MAX_EVIDENCE) {
                break;
            }
            out.add("|   - " + evidence(e));
        }
        if (!d.getFixes().isEmpty()) {
            out.add("| " + Texts.t("card.fix") + " " + fixLine(d.getFixes().get(0)));
            for (int i = 1; i < d.getFixes().size() && i < 3; i++) {
                out.add("|   " + Texts.t("card.or") + " " + fixLine(d.getFixes().get(i)));
            }
        }
        if (diagnoses.size() > 1) {
            StringBuilder more = new StringBuilder("| " + Texts.t("card.more") + " ");
            for (int i = 1; i < diagnoses.size(); i++) {
                more.append(i > 1 ? ", " : "").append(diagnoses.get(i).getCategory());
            }
            out.add(more.toString());
        }
        if (changes != null && !changes.isEmpty()) {
            out.add("| " + changes.headline() + ":");
            for (String line : changes.lines(MAX_CHANGES)) {
                out.add("|   - " + line);
            }
            if (changes.limitationText() != null) {
                out.add("|   " + changes.limitationText());
            }
        }
        out.add("+--------------------------------------------------------------");
        return out;
    }

    /**
     * Ersetzt Zeichen, die eine Konsole ohne Unicode nicht darstellen kann (Umlaute → ae/oe/ue …),
     * damit im CI-Log kein "?" steht.
     */
    public static String transliterate(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case 'ä': sb.append("ae"); break;
                case 'ö': sb.append("oe"); break;
                case 'ü': sb.append("ue"); break;
                case 'Ä': sb.append("Ae"); break;
                case 'Ö': sb.append("Oe"); break;
                case 'Ü': sb.append("Ue"); break;
                case 'ß': sb.append("ss"); break;
                case '·': case '–': case '—': sb.append('-'); break;
                case '…': sb.append("..."); break;
                case '→': sb.append("->"); break;
                case '„': case '“': case '”': sb.append('"'); break;
                default:
                    if (c < 128) {
                        sb.append(c);
                    } else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        sb.append('?'); // ein Zeichen außerhalb der BMP (Emoji) ist ein Zeichen, nicht zwei
                        i++;
                    } else {
                        sb.append(withoutAccent(c));
                    }
            }
        }
        return sb.toString();
    }

    /** {@code é} → {@code e}, {@code Å} → {@code A}; was sich nicht zerlegen lässt, wird {@code ?}. */
    private static char withoutAccent(char c) {
        String decomposed = java.text.Normalizer.normalize(String.valueOf(c), java.text.Normalizer.Form.NFD);
        char base = decomposed.charAt(0);
        return decomposed.length() > 1 && base < 128 ? base : '?';
    }

    /** {@code true}, wenn die Konsole Umlaute und Sonderzeichen der Karte darstellen kann. */
    public static boolean consoleSupportsUnicode() {
        String enc = System.getProperty("stdout.encoding");
        if (enc == null) {
            enc = System.getProperty("sun.stdout.encoding");
        }
        try {
            java.nio.charset.Charset cs = enc != null ? java.nio.charset.Charset.forName(enc) : java.nio.charset.Charset.defaultCharset();
            return cs.newEncoder().canEncode("äöüß·…→");
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static String headline(Diagnosis d) {
        return (d.getNumber() > 0 ? "#" + d.getNumber() + " " : "") + d.getCategory() + " · " + d.getConfidence().label();
    }

    public static String evidence(Evidence e) {
        String text = shorten(e.getText(), 220);
        if (e.getFile() == null) {
            return text;
        }
        return text + " -> " + e.getFile() + (e.getLine() > 0 ? ":" + e.getLine() : "");
    }

    /** Für Konsole: Label plus, wenn sinnvoll, der konkrete Befehl. */
    public static String fixLine(FixAction f) {
        String label = f.getLabel();
        if (FixAction.REBUILD.equals(f.getType()) && f.param("args") != null && !f.param("args").isEmpty()) {
            return label + "  (mvn … " + f.param("args") + ")";
        }
        if (FixAction.DELETE_FILES.equals(f.getType()) && f.param("files") != null) {
            String[] files = f.param("files").split("\n");
            return label + "  (" + (files.length == 1 ? Texts.t("card.files.one", files[0]) : Texts.t("card.files.many", files.length)) + ")";
        }
        if (FixAction.OPEN_FILE.equals(f.getType()) && f.param("file") != null) {
            return label + "  (" + f.param("file") + (f.param("line") != null && !"-1".equals(f.param("line")) ? ":" + f.param("line") : "") + ")";
        }
        return label;
    }

    /** Markdown für Ticket oder Chat: Fehler, Ursache, Belege, Umgebung. */
    public static String markdown(List<Diagnosis> diagnoses, BuildFailure failure) {
        return markdown(diagnoses, failure, null);
    }

    /** Markdown mit Abschnitt „Was hat sich geändert?“, wenn ein Vergleich vorliegt. */
    public static String markdown(List<Diagnosis> diagnoses, BuildFailure failure, SnapshotDiff changes) {
        StringBuilder sb = new StringBuilder();
        if (diagnoses.isEmpty()) {
            return Texts.t("report.no.errors") + "\n";
        }
        Diagnosis d = diagnoses.get(0);
        sb.append("### ").append(Texts.t("report.failed", d.getCategory())).append('\n').append('\n');
        sb.append("**").append(Texts.t("report.cause")).append("** (").append(d.getConfidence().label()).append("): ").append(d.getCause()).append("\n\n");
        sb.append("**").append(Texts.t("report.evidence")).append("**\n\n");
        for (Evidence e : d.getEvidence()) {
            if (e.getKind() == Evidence.Kind.LOG_LINE) {
                sb.append("- `").append(shorten(e.getText(), 300).replace("`", "'")).append("`\n");
            } else {
                sb.append("- ").append(evidence(e)).append('\n');
            }
        }
        if (!d.getFixes().isEmpty()) {
            sb.append("\n**").append(Texts.t("report.fix")).append("** ").append(fixLine(d.getFixes().get(0))).append('\n');
        }
        if (changes != null && !changes.isEmpty()) {
            sb.append("\n**").append(changes.headline()).append("**\n\n");
            for (String line : changes.lines(MAX_CHANGES * 2)) {
                sb.append("- ").append(line).append('\n');
            }
            if (changes.limitationText() != null) {
                sb.append("\n*").append(changes.limitationText()).append("*\n");
            }
        }
        sb.append("\n**").append(Texts.t("report.environment")).append("**\n\n");
        SessionInfo s = failure.getSession();
        appendIf(sb, "Maven", s.getMavenVersion());
        appendIf(sb, "JDK", s.getJavaVersion() == null ? null
                : s.getJavaVersion() + (s.getJavaHome() == null ? "" : " (" + s.getJavaHome() + ")"));
        appendIf(sb, Texts.t("report.offline"), s.getOffline() == null ? null : String.valueOf(s.getOffline()));
        appendIf(sb, "settings.xml", s.getUserSettings());
        appendIf(sb, Texts.t("report.profiles"), s.getActiveProfiles().isEmpty() ? null : String.join(", ", s.getActiveProfiles()));
        appendIf(sb, Texts.t("report.goals"), s.getGoals().isEmpty() ? null : String.join(" ", s.getGoals()));
        appendIf(sb, "OS", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        sb.append(footer());
        return sb.toString();
    }

    /** Schlusszeile des Markdown-Berichts (das Plugin hängt davor den Umgebungsvergleich ein). */
    public static String footer() {
        return "\n_" + Texts.t("report.footer") + "_\n";
    }

    private static void appendIf(StringBuilder sb, String key, String value) {
        if (value != null && !value.isEmpty()) {
            sb.append("- ").append(key).append(": ").append(value).append('\n');
        }
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        int end = max - 1;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--; // kein halbes Emoji vor dem „…“
        }
        return s.substring(0, end) + "…";
    }
}
