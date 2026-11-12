package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.env.SetupComparison;
import io.github.bitshiftdev.mavendetective.core.report.Reports;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotDiff;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.event.HyperlinkEvent;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.List;

/**
 * Die Diagnose-Karte: Kategorie und Sicherheit, Ursache in einem Satz, Belege (klickbar, wenn sie
 * auf eine Datei zeigen), was sich seit dem letzten grünen Build geändert hat, Abweichungen zwischen
 * IDE und Kommandozeile und Quick-Fix-Knöpfe.
 */
public final class BuildDoctorPanel extends JPanel implements Disposable, BuildDoctorService.Listener {

    private static final String LINK_SETUP = "md:setup";
    private static final String LINK_BACK = "md:back";
    private static final int MAX_CHANGES = 8;

    private final Project project;
    private final BuildDoctorService service;
    private final JEditorPane card = new JEditorPane("text/html", "");
    private final JPanel fixes = new JPanel(new FlowLayout(FlowLayout.LEFT));
    private final JButton copyReport = new JButton("Bericht kopieren");
    private final JButton wrong = new JButton("Falsch erkannt");
    private BuildDoctorService.Result shown;

    public BuildDoctorPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.service = BuildDoctorService.getInstance(project);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton fromClipboard = new JButton("Log aus Zwischenablage analysieren");
        fromClipboard.addActionListener(e -> {
            String text = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
            if (text != null && !text.isBlank()) {
                service.analyzeLog(text, null, false);
            }
        });
        JButton compare = new JButton("IDE gegen Kommandozeile");
        compare.setToolTipText("Vergleicht Maven, JDK, settings.xml, Profile und .mvn/-Konfiguration von IDE und ./mvnw");
        compare.addActionListener(e -> showSetup(shown != null && shown.setup() != null ? shown.setup() : null));
        copyReport.addActionListener(e -> {
            if (shown != null) {
                CopyPasteManager.getInstance().setContents(new StringSelection(report(shown)));
            }
        });
        wrong.addActionListener(e -> {
            if (shown != null) {
                FixRunner.reportMisdiagnosis(project, shown);
            }
        });
        toolbar.add(fromClipboard);
        toolbar.add(compare);
        toolbar.add(copyReport);
        toolbar.add(wrong);

        card.setEditable(false);
        card.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        card.addHyperlinkListener(e -> {
            if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED || e.getDescription() == null) {
                return;
            }
            String target = e.getDescription();
            if (LINK_SETUP.equals(target)) {
                showSetup(shown == null ? null : shown.setup());
            } else if (LINK_BACK.equals(target)) {
                if (shown != null) {
                    resultChanged(shown);
                } else {
                    showEmpty();
                }
            } else {
                FixRunner.openLocation(project, target);
            }
        });

        add(toolbar, BorderLayout.NORTH);
        add(new JBScrollPane(card), BorderLayout.CENTER);
        add(fixes, BorderLayout.SOUTH);

        service.addListener(this);
        BuildDoctorService.Result last = service.getLast();
        if (last != null) {
            resultChanged(last);
        } else {
            showEmpty();
        }
    }

    @Override
    public void resultChanged(BuildDoctorService.Result result) {
        shown = result;
        fixes.removeAll();
        if (result.diagnoses().isEmpty()) {
            card.setText(html(result.environment() == null
                    ? "<p>In der Zwischenablage steht kein Maven-Log mit Fehlerzeilen (<code>[ERROR]</code>).</p>"
                    : "<p><b>Der Maven-Lauf ist gescheitert, aber im Log steht keine Fehlerzeile von Maven.</b></p>"
                    + "<p>Letzte Zeilen der Ausgabe:</p><pre>" + esc(tail(result.rawLog(), 15)) + "</pre>"
                    + "<p>Bitte mit „Falsch erkannt“ als Testfall speichern.</p>"));
            wrong.setEnabled(true);
        } else {
            Diagnosis d = result.diagnoses().get(0);
            card.setText(html(render(result)));
            for (FixAction fix : d.getFixes()) {
                JButton b = new JButton(fix.getLabel());
                b.setEnabled(FixRunner.isSupported(fix));
                b.setToolTipText(FixRunner.isSupported(fix) ? null : "Wird in einer späteren Version automatisch ausgeführt");
                b.addActionListener(e -> FixRunner.run(project, fix, result));
                fixes.add(b);
            }
        }
        copyReport.setEnabled(!result.diagnoses().isEmpty());
        wrong.setEnabled(!result.diagnoses().isEmpty() || result.environment() != null);
        fixes.revalidate();
        fixes.repaint();
        card.setCaretPosition(0);
    }

    /** Vergleich IDE gegen Kommandozeile anzeigen; ohne fertigen Vergleich wird er jetzt ermittelt. */
    private void showSetup(SetupComparison known) {
        if (known != null) {
            card.setText(html(renderSetup(known)));
            card.setCaretPosition(0);
            return;
        }
        card.setText(html("<p>Vergleiche IDE und Kommandozeile …</p>"));
        service.compareSetupsAsync(shown == null ? null : shown.configuration(), c -> {
            card.setText(html(renderSetup(c)));
            card.setCaretPosition(0);
        });
    }

    private void showEmpty() {
        copyReport.setEnabled(false);
        wrong.setEnabled(false);
        card.setText(html("<p>Noch keine Diagnose. Scheitert ein Maven-Lauf in der IDE, erscheint sie hier automatisch.</p>"
                + "<p>Für Logs aus CI oder Terminal: Log kopieren und „Log aus Zwischenablage analysieren“.</p>"
                + "<p>Jeder grüne Maven-Lauf aus der IDE wird als Referenz gespeichert. Scheitert ein späterer Lauf, "
                + "zeigt die Karte, was sich seitdem geändert hat.</p>"));
    }

    static String report(BuildDoctorService.Result r) {
        String md = Reports.markdown(r.diagnoses(), r.failure(), r.changes());
        SetupComparison setup = r.setup();
        if (setup != null && !setup.getDifferences().isEmpty()) {
            md = md.replace("\n_Erstellt mit Maven Detective_\n",
                    "\n**IDE gegen Kommandozeile**\n\n" + setup.markdown() + "\n_Erstellt mit Maven Detective_\n");
        }
        return md;
    }

    private static String render(BuildDoctorService.Result result) {
        Diagnosis d = result.diagnoses().get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("<h3>").append(esc(Reports.headline(d))).append("</h3>");
        sb.append("<p><b>").append(esc(d.getCause())).append("</b></p>");
        sb.append("<p>Belege:</p><ul>");
        for (Evidence e : d.getEvidence()) {
            sb.append("<li>");
            if (e.getFile() != null) {
                String target = e.getFile() + (e.getLine() > 0 ? ":" + e.getLine() : "");
                sb.append(esc(e.getText())).append(" – <a href=\"").append(esc(target)).append("\">")
                        .append(esc(shortPath(target))).append("</a>");
            } else if (e.getKind() == Evidence.Kind.LOG_LINE) {
                sb.append("<code>").append(esc(e.getText())).append("</code>");
            } else {
                sb.append(esc(e.getText()));
            }
            sb.append("</li>");
        }
        sb.append("</ul>");

        SnapshotDiff changes = result.changes();
        if (changes != null) {
            sb.append("<p><b>").append(esc(changes.headline())).append(":</b></p>");
            if (changes.isEmpty()) {
                sb.append("<p>Keine Änderung an Maven, JDK, Profilen, Parent, Plugins oder Dependencies. "
                        + "Die Ursache liegt eher im Code, in Testdaten oder außerhalb des Builds (Netz, Repository).</p>");
            } else {
                sb.append("<ul>");
                for (String line : changes.lines(MAX_CHANGES)) {
                    sb.append("<li>").append(esc(line)).append("</li>");
                }
                sb.append("</ul>");
            }
        }

        SetupComparison setup = result.setup();
        if (setup != null && !setup.getDifferences().isEmpty()) {
            sb.append("<p>").append(esc(setup.summary())).append(" – <a href=\"").append(LINK_SETUP)
                    .append("\">Vergleich anzeigen</a></p>");
        }

        if (result.diagnoses().size() > 1) {
            sb.append("<p>Weitere Treffer: ");
            for (int i = 1; i < result.diagnoses().size(); i++) {
                sb.append(i > 1 ? ", " : "").append(esc(result.diagnoses().get(i).getCategory()));
            }
            sb.append("</p>");
        }
        return sb.toString();
    }

    private String renderSetup(SetupComparison c) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h3>IDE gegen Kommandozeile</h3>");
        sb.append("<p><b>").append(esc(c.summary())).append("</b></p>");
        sb.append("<table cellpadding=\"3\"><tr><th></th><th align=\"left\">").append(esc(c.getLeft().getLabel()))
                .append("</th><th align=\"left\">").append(esc(c.getRight().getLabel())).append("</th></tr>");
        for (SetupComparison.Row r : c.getRows()) {
            String open = r.isDifferent() ? "<b>" : "";
            String close = r.isDifferent() ? "</b>" : "";
            sb.append("<tr><td valign=\"top\">").append(open).append(esc(r.getAspect())).append(close)
                    .append("</td><td valign=\"top\">").append(esc(r.getLeft()))
                    .append("</td><td valign=\"top\">").append(esc(r.getRight())).append("</td></tr>");
        }
        sb.append("</table>");
        List<SetupComparison.Row> diffs = c.getDifferences();
        if (!diffs.isEmpty()) {
            sb.append("<ul>");
            for (SetupComparison.Row r : diffs) {
                sb.append("<li>").append(esc(r.getHint())).append("</li>");
            }
            sb.append("</ul>");
        }
        List<String> notes = c.getRight().getNotes();
        if (!notes.isEmpty() || !c.getLeft().getNotes().isEmpty()) {
            sb.append("<p>Hinweise: ");
            for (String n : c.getLeft().getNotes()) {
                sb.append(esc(n)).append(' ');
            }
            for (String n : notes) {
                sb.append(esc(n)).append(' ');
            }
            sb.append("</p>");
        }
        sb.append("<p><small>Die Kommandozeile wird aus <code>.mvn/</code>, <code>mvnw</code> und den Umgebungsvariablen "
                + "der IDE abgeleitet. Eine Shell mit anderem JAVA_HOME oder PATH kann abweichen.</small></p>");
        sb.append("<p><a href=\"").append(LINK_BACK).append("\">Zurück</a></p>");
        return sb.toString();
    }

    private static String tail(String log, int lines) {
        if (log == null || log.isBlank()) {
            return "(keine Ausgabe empfangen)";
        }
        String[] all = log.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "").split("\\R");
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = all.length - 1; i >= 0 && shown < lines; i--) {
            if (!all[i].isBlank()) {
                sb.insert(0, all[i] + "\n");
                shown++;
            }
        }
        return sb.toString();
    }

    private static String shortPath(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String html(String body) {
        return "<html><body>" + body + "</body></html>";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @Override
    public void dispose() {
        service.removeListener(this);
    }
}
