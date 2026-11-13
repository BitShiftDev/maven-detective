package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.env.SetupComparison;
import io.github.bitshiftdev.mavendetective.core.report.Reports;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotDiff;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectivePanel;
import io.github.bitshiftdev.mavendetective.idea.ui.FixBar;
import io.github.bitshiftdev.mavendetective.idea.ui.HtmlView;
import io.github.bitshiftdev.mavendetective.idea.ui.Locations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.List;

import static io.github.bitshiftdev.mavendetective.idea.ui.Html.esc;

/**
 * Die Diagnose-Karte: Kategorie und Sicherheit, Ursache in einem Satz, Belege (klickbar, wenn sie
 * auf eine Datei zeigen), was sich seit dem letzten grünen Build geändert hat, Abweichungen zwischen
 * IDE und Kommandozeile und Quick-Fix-Knöpfe (der erste als Hauptaktion).
 */
public final class BuildDoctorPanel extends DetectivePanel implements BuildDoctorService.Listener {

    private static final String LINK_SETUP = "md:setup";
    private static final String LINK_BACK = "md:back";
    private static final int MAX_CHANGES = 8;

    private final BuildDoctorService service;
    private final HtmlView card;
    private final FixBar fixes = new FixBar();
    private BuildDoctorService.Result shown;

    public BuildDoctorPanel(@NotNull Project project) {
        super(project);
        this.service = BuildDoctorService.getInstance(project);

        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(registered("MavenDetective.AnalyzeClipboard"));
        actions.add(registered("MavenDetective.CompareSetup"));
        actions.addSeparator();
        actions.add(registered("MavenDetective.CopyMarkdown"));
        actions.add(registered("MavenDetective.ReportMisdiagnosis"));
        installToolbar(null, actions, null);

        card = new HtmlView(project, target -> {
            if (LINK_SETUP.equals(target)) {
                showSetupView();
            } else if (LINK_BACK.equals(target)) {
                if (shown != null) {
                    resultChanged(shown);
                } else {
                    showEmpty();
                }
            } else {
                return false;
            }
            return true;
        });
        JPanel body = new JPanel(new BorderLayout());
        body.add(card.component(), BorderLayout.CENTER);
        body.add(fixes, BorderLayout.SOUTH);
        setBody(body);

        service.addListener(this, this);
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
            card.show(result.environment() == null
                    ? "<p>In der Zwischenablage steht kein Maven-Log mit Fehlerzeilen (<code>[ERROR]</code>).</p>"
                    : "<p><b>Der Maven-Lauf ist gescheitert, aber im Log steht keine Fehlerzeile von Maven.</b></p>"
                    + "<p>Letzte Zeilen der Ausgabe:</p><pre>" + esc(tail(result.rawLog(), 15)) + "</pre>"
                    + "<p>Bitte mit „Falsch erkannt“ als Testfall speichern.</p>");
        } else {
            Diagnosis d = result.diagnoses().get(0);
            card.show(render(result));
            boolean first = true;
            for (FixAction fix : d.getFixes()) {
                JButton b = new JButton(fix.getLabel());
                boolean supported = FixRunner.isSupported(fix);
                b.setEnabled(supported);
                b.setToolTipText(supported ? null : MavenDetectiveBundle.message("doctor.fix.unsupported"));
                b.addActionListener(e -> FixRunner.run(project, fix, result));
                fixes.add(first && supported ? FixBar.primary(b) : b);
                first = false;
            }
        }
        fixes.setVisible(fixes.getComponentCount() > 0);
        fixes.revalidate();
        fixes.repaint();
    }

    /** Kurze Meldung statt der Karte (z. B. leere Zwischenablage); „Zurück“ führt zur Karte. */
    void showMessage(@NotNull String text) {
        card.show("<p>" + esc(text) + "</p><p><a href=\"" + LINK_BACK + "\">Zurück</a></p>");
    }

    /** Vergleich IDE gegen Kommandozeile anzeigen; ohne fertigen Vergleich wird er jetzt ermittelt. */
    void showSetupView() {
        SetupComparison known = shown == null ? null : shown.setup();
        if (known != null) {
            card.show(renderSetup(known));
            return;
        }
        card.show("<p>" + esc(MavenDetectiveBundle.message("doctor.setup.loading")) + "</p>");
        service.compareSetupsAsync(shown == null ? null : shown.configuration(), c -> card.show(renderSetup(c)));
    }

    private void showEmpty() {
        card.show("<p>Noch keine Diagnose. Scheitert ein Maven-Lauf in der IDE, erscheint sie hier automatisch.</p>"
                + "<p>Für Logs aus CI oder Terminal: Log kopieren und „Log aus Zwischenablage analysieren“ (Toolbar oben).</p>"
                + "<p>Jeder grüne Maven-Lauf aus der IDE wird als Referenz gespeichert. Scheitert ein späterer Lauf, "
                + "zeigt die Karte, was sich seitdem geändert hat.</p>");
        fixes.setVisible(false);
    }

    @Override
    public boolean canCopyMarkdown() {
        return shown != null && !shown.diagnoses().isEmpty();
    }

    @Override
    public @Nullable String markdown() {
        return canCopyMarkdown() ? report(shown) : null;
    }

    @Override
    public @Nullable String copyMarkdownText() {
        return MavenDetectiveBundle.message("doctor.copy.report");
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
                String target = Locations.of(e.getFile(), e.getLine());
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
            // Abweichung nicht nur fett, sondern auch mit Zeichen – für Screenreader und ohne Farbe lesbar
            String open = r.isDifferent() ? "<b>≠ " : "";
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
        int count = 0;
        for (int i = all.length - 1; i >= 0 && count < lines; i--) {
            if (!all[i].isBlank()) {
                sb.insert(0, all[i] + "\n");
                count++;
            }
        }
        return sb.toString();
    }

    private static String shortPath(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
