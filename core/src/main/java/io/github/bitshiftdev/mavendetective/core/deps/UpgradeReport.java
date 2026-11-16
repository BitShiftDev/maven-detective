package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.Delta;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.Kind;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.ModuleResult;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.Result;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.Risk;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.Step;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.Target;

import java.util.ArrayList;
import java.util.List;

import static io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.join;

/** Ausgabe eines {@link UpgradeSimulator.Result} für Konsole und Markdown. */
final class UpgradeReport {

    private UpgradeReport() {
    }

    static String headline(Result r) {
        List<String> current = r.getCurrentVersions();
        String from = current.isEmpty() ? "" : join(current, "/") + " → ";
        String what = r.getTarget() == Target.BOM ? "BOM " : r.getTarget() == Target.PARENT ? "Parent " : "";
        return Texts.t("upgrade.headline", what + r.getRequest().label() + " " + from + r.getRequest().getVersion());
    }

    /** Konsolenausgabe; ohne {@code all} Module ohne Änderung nur als Liste. */
    static List<String> lines(Result r, boolean all) {
        List<String> out = new ArrayList<String>();
        out.add(headline(r) + " (" + Texts.t("upgrade.poms.unchanged") + ")");
        out.add("");
        if (r.getSteps().isEmpty()) {
            out.add(Texts.t("upgrade.no.change.possible"));
        } else {
            out.add(Texts.t("upgrade.steps", r.getSteps().size()));
            int i = 1;
            for (Step s : r.getSteps()) {
                out.add("  " + i++ + ". " + s.getDescription() + "  → " + s.getFile() + (s.getLine() > 0 ? ":" + s.getLine() : ""));
                if (s.getReason() != null) {
                    out.add("     " + s.getReason());
                }
            }
        }
        for (String p : r.getProblems()) {
            out.add("! " + p);
        }
        List<String> unchanged = new ArrayList<String>();
        for (ModuleResult m : r.getModules()) {
            if (m.isUnchanged() && !all && m.getNote() == null && m.getDeviation() == null && !m.isIncomplete()) {
                unchanged.add(m.getName());
                continue;
            }
            out.add("");
            out.add(Texts.t("upgrade.module", m.getName()) + ": " + moduleSummary(m));
            if (m.getNote() != null) {
                out.add("  ! " + m.getNote());
            }
            for (Delta d : m.getDeltas()) {
                out.add("  " + symbol(d.getKind()) + " " + d.message());
            }
            for (Risk risk : m.getRisks()) {
                out.add("  ! " + Texts.t("upgrade.conflict") + " " + risk.message());
            }
            if (m.getDeviation() != null) {
                out.add("  (" + m.getDeviation() + ")");
            }
        }
        if (!unchanged.isEmpty()) {
            out.add("");
            out.add(Texts.t("upgrade.unchanged.modules") + " " + join(unchanged, ", "));
        }
        List<String> missing = r.getMissing();
        if (!missing.isEmpty()) {
            out.add("");
            out.add(Texts.t("upgrade.incomplete", missing.size()) + " " + join(first(missing, 8), ", ") + (missing.size() > 8 ? ", …" : ""));
            out.add("  " + Texts.t("upgrade.incomplete.hint"));
        }
        return out;
    }

    /** Markdown für Ticket oder Review. */
    static String markdown(Result r, boolean all) {
        StringBuilder sb = new StringBuilder("### ").append(headline(r)).append("\n\n");
        if (!r.getSteps().isEmpty()) {
            sb.append("**").append(Texts.t("upgrade.md.steps")).append("** (").append(Texts.t("upgrade.md.not.applied")).append(")\n\n");
            for (Step s : r.getSteps()) {
                sb.append("1. ").append(s.getDescription()).append(" — `").append(s.getFile())
                        .append(s.getLine() > 0 ? ":" + s.getLine() : "").append("`\n");
            }
            sb.append('\n');
        }
        for (String p : r.getProblems()) {
            sb.append("> ⚠ ").append(p).append("\n\n");
        }
        for (ModuleResult m : r.getModules()) {
            if (m.isUnchanged() && !all && m.getNote() == null && !m.isIncomplete()) {
                continue;
            }
            sb.append("#### ").append(Texts.t("upgrade.module", "`" + m.getName() + "`")).append(" – ").append(moduleSummary(m)).append("\n\n");
            if (m.getNote() != null) {
                sb.append("> ").append(m.getNote()).append("\n\n");
            }
            if (!m.getDeltas().isEmpty()) {
                sb.append(Texts.t("upgrade.md.table")).append("\n|---|---|---|---|---|\n");
                for (Delta d : m.getDeltas()) {
                    DepNode via = d.getAfter() != null ? d.getAfter() : d.getBefore();
                    sb.append("| ").append(symbol(d.getKind())).append(" | `").append(d.label()).append("` | ")
                            .append(d.getBefore() == null ? "–" : d.getBefore().getVersion() + scopeSuffix(d.getBefore())).append(" | ")
                            .append(d.getAfter() == null ? "–" : d.getAfter().getVersion() + scopeSuffix(d.getAfter())).append(" | ")
                            .append(via.depth() <= 1 ? Texts.t("upgrade.direct") : via.pathText().replace("|", "\\|")).append(" |\n");
                }
                sb.append('\n');
            }
            for (Risk risk : m.getRisks()) {
                sb.append("- ⚠ ").append(risk.message()).append('\n');
            }
            if (!m.getRisks().isEmpty()) {
                sb.append('\n');
            }
        }
        if (r.changed().isEmpty()) {
            sb.append(Texts.t("upgrade.no.module.changes")).append("\n");
        }
        List<String> missing = r.getMissing();
        if (!missing.isEmpty()) {
            sb.append("\n**").append(Texts.t("upgrade.md.incomplete")).append("** ").append(join(first(missing, 12), ", "))
                    .append(missing.size() > 12 ? ", …" : "").append('\n');
        }
        return sb.toString();
    }

    private static String scopeSuffix(DepNode n) {
        return n.getScope() == null || "compile".equals(n.getScope()) ? "" : " (" + n.getScope() + ")";
    }

    private static String moduleSummary(ModuleResult m) {
        String summary = changeSummary(m);
        return m.isIncomplete() ? summary + " – " + Texts.t("upgrade.summary.incomplete", m.getMissing().size()) : summary;
    }

    private static String changeSummary(ModuleResult m) {
        if (m.isUnchanged()) {
            return m.getTargetBefore() != null && m.getTargetBefore().equals(m.getTargetAfter())
                    ? Texts.t("upgrade.summary.stays", m.getTargetAfter()) : Texts.t("upgrade.summary.none");
        }
        List<String> parts = new ArrayList<String>();
        add(parts, m.count(Kind.UPGRADED), Texts.t("upgrade.summary.upgraded"));
        add(parts, m.count(Kind.DOWNGRADED), Texts.t("upgrade.summary.downgraded"));
        add(parts, m.count(Kind.ADDED), Texts.t("upgrade.summary.added"));
        add(parts, m.count(Kind.REMOVED), Texts.t("upgrade.summary.removed"));
        add(parts, m.count(Kind.SCOPE), Texts.t("upgrade.summary.scope"));
        add(parts, m.getRisks().size(), Texts.t("upgrade.summary.risks", m.getRisks().size()));
        return join(parts, ", ");
    }

    private static void add(List<String> parts, int n, String what) {
        if (n > 0) {
            parts.add(n + " " + what);
        }
    }

    private static List<String> first(List<String> list, int n) {
        return list.size() <= n ? list : list.subList(0, n);
    }

    /** Zeichen je Änderung: + − ↑ ↓ ~. */
    static String symbol(Kind k) {
        switch (k) {
            case ADDED:
                return "+";
            case REMOVED:
                return "−";
            case UPGRADED:
                return "↑";
            case DOWNGRADED:
                return "↓";
            default:
                return "~";
        }
    }
}
