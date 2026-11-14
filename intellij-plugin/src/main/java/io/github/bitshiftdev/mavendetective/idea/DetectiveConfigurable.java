package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.ide.DataManager;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.options.ex.Settings;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import io.github.bitshiftdev.mavendetective.core.preflight.Preflight;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.event.ActionListener;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Einstellungsseite <i>Settings | Tools | Maven Detective</i> (UI-Plan U6). Was die IDE schon selbst
 * einstellt (Inlay-Hinweise, Code Vision, Inspections), steht hier nur als Link – zwei Schalter für
 * dieselbe Sache widersprächen sich.
 */
public final class DetectiveConfigurable implements SearchableConfigurable {

    static final String ID = "io.github.bitshiftdev.mavendetective.settings";
    /** IDs der Seiten der IDE, auf die verlinkt wird. */
    private static final String INLAY_HINTS_ID = "inlay.hints";
    private static final String INSPECTIONS_ID = "Errors";

    private ComboBox<MavenDetectiveBundle.Language> language;
    private JBCheckBox notifyRedBuild;
    private JBCheckBox rebuildAfterFix;
    private JBCheckBox previewPomFixes;
    private JBCheckBox snapshots;
    private TextFieldWithBrowseButton snapshotDir;
    private JBCheckBox usageViaStarters;
    private JBCheckBox foldDownloads;
    private JBCheckBox preflight;
    private JBTextField preflightNexus;
    private JBLabel firstStepsDone;
    private JPanel panel;

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @Nls String getDisplayName() {
        return MavenDetectiveBundle.message("settings.title");
    }

    @Override
    public @Nullable JComponent createComponent() {
        language = new ComboBox<>(MavenDetectiveBundle.Language.values());
        language.setRenderer(SimpleListCellRenderer.create("", DetectiveConfigurable::languageName));
        language.getAccessibleContext().setAccessibleName(MavenDetectiveBundle.message("settings.language"));
        notifyRedBuild = new JBCheckBox(MavenDetectiveBundle.message("settings.notify"));
        rebuildAfterFix = new JBCheckBox(MavenDetectiveBundle.message("settings.rebuild"));
        previewPomFixes = new JBCheckBox(MavenDetectiveBundle.message("settings.preview"));
        foldDownloads = new JBCheckBox(MavenDetectiveBundle.message("settings.fold.downloads"));
        snapshots = new JBCheckBox(MavenDetectiveBundle.message("settings.snapshots"));
        snapshotDir = new TextFieldWithBrowseButton();
        snapshotDir.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle(MavenDetectiveBundle.message("settings.snapshots.dir.choose")));
        if (snapshotDir.getTextField() instanceof JBTextField f) {
            f.getEmptyText().setText(defaultSnapshotRoot());
        }
        snapshotDir.getAccessibleContext().setAccessibleName(MavenDetectiveBundle.message("settings.snapshots.dir"));
        snapshots.addItemListener(e -> snapshotDir.setEnabled(snapshots.isSelected()));
        usageViaStarters = new JBCheckBox(MavenDetectiveBundle.message("settings.starters"));
        preflight = new JBCheckBox(MavenDetectiveBundle.message("settings.preflight"));
        preflightNexus = new JBTextField();
        preflightNexus.getEmptyText().setText(MavenDetectiveBundle.message("settings.preflight.nexus.empty"));
        preflightNexus.getAccessibleContext().setAccessibleName(MavenDetectiveBundle.message("settings.preflight.nexus"));
        preflight.addItemListener(e -> preflightNexus.setEnabled(preflight.isSelected()));
        firstStepsDone = new JBLabel();
        firstStepsDone.setForeground(UIUtil.getContextHelpForeground());

        ActionLink inlays = new ActionLink(MavenDetectiveBundle.message("settings.editor.inlays"),
                (ActionListener) e -> select(INLAY_HINTS_ID));
        ActionLink inspections = new ActionLink(MavenDetectiveBundle.message("settings.editor.inspections"),
                (ActionListener) e -> select(INSPECTIONS_ID));
        ActionLink resetTips = new ActionLink(MavenDetectiveBundle.message("settings.tips.reset"), (ActionListener) e -> {
            FirstSteps.reset();
            firstStepsDone.setText(MavenDetectiveBundle.message("settings.tips.reset.done"));
        });

        panel = FormBuilder.createFormBuilder()
                .addComponent(new TitledSeparator(MavenDetectiveBundle.message("settings.group.ui")))
                .addLabeledComponent(indent(new JBLabel(MavenDetectiveBundle.message("settings.language"))), indent(language))
                .addComponent(comment(MavenDetectiveBundle.message("settings.language.comment")))
                .addComponent(new TitledSeparator(MavenDetectiveBundle.message("settings.group.doctor")))
                .addComponent(indent(notifyRedBuild))
                .addComponent(comment(MavenDetectiveBundle.message("settings.notify.comment")))
                .addComponent(indent(rebuildAfterFix))
                .addComponent(comment(MavenDetectiveBundle.message("settings.rebuild.comment")))
                .addComponent(indent(foldDownloads))
                .addComponent(comment(MavenDetectiveBundle.message("settings.fold.downloads.comment")))
                .addComponent(new TitledSeparator(MavenDetectiveBundle.message("settings.group.preflight")))
                .addComponent(indent(preflight))
                .addComponent(comment(MavenDetectiveBundle.message("settings.preflight.comment")))
                .addLabeledComponent(indent(new JBLabel(MavenDetectiveBundle.message("settings.preflight.nexus"))), preflightNexus)
                .addComponent(comment(MavenDetectiveBundle.message("settings.preflight.nexus.comment")))
                .addComponent(new TitledSeparator(MavenDetectiveBundle.message("settings.group.snapshots")))
                .addComponent(indent(snapshots))
                .addLabeledComponent(indent(new JBLabel(MavenDetectiveBundle.message("settings.snapshots.dir"))), snapshotDir)
                .addComponent(comment(MavenDetectiveBundle.message("settings.snapshots.comment")))
                .addComponent(new TitledSeparator(MavenDetectiveBundle.message("settings.group.pom")))
                .addComponent(indent(previewPomFixes))
                .addComponent(comment(MavenDetectiveBundle.message("settings.preview.comment")))
                .addComponent(indent(usageViaStarters))
                .addComponent(comment(MavenDetectiveBundle.message("settings.starters.comment")))
                .addComponent(new TitledSeparator(MavenDetectiveBundle.message("settings.group.editor")))
                .addComponent(comment(MavenDetectiveBundle.message("settings.editor.comment")))
                .addComponent(indent(inlays))
                .addComponent(indent(inspections))
                .addComponent(new TitledSeparator(MavenDetectiveBundle.message("settings.group.tips")))
                .addComponent(comment(MavenDetectiveBundle.message("settings.tips.comment")))
                .addComponent(indent(resetTips))
                .addComponent(indent(firstStepsDone))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        return panel;
    }

    @Override
    public boolean isModified() {
        DetectiveSettings s = DetectiveSettings.getInstance();
        return language.getItem() != s.getLanguage()
                || notifyRedBuild.isSelected() != s.isNotifyRedBuild()
                || rebuildAfterFix.isSelected() != s.isRebuildAfterFix()
                || previewPomFixes.isSelected() != s.isPreviewPomFixes()
                || snapshots.isSelected() != s.isSnapshots()
                || !Objects.equals(snapshotDir.getText().trim(), s.getSnapshotDir())
                || usageViaStarters.isSelected() != s.isUsageViaStarters()
                || preflight.isSelected() != s.isPreflight()
                || !Objects.equals(preflightNexus.getText().trim(), s.getPreflightNexus())
                || foldDownloads.isSelected() != s.isFoldDownloads();
    }

    @Override
    public void apply() throws ConfigurationException {
        String dir = snapshotDir.getText().trim();
        if (!dir.isEmpty() && DetectiveSettings.parseDir(dir) == null) {
            throw new ConfigurationException(MavenDetectiveBundle.message("settings.snapshots.dir.invalid", dir));
        }
        String nexus = preflightNexus.getText().trim();
        if (!nexus.isEmpty() && !Preflight.isRepositoryUrl(nexus)) {
            throw new ConfigurationException(MavenDetectiveBundle.message("settings.preflight.nexus.invalid", nexus));
        }
        DetectiveSettings s = DetectiveSettings.getInstance();
        if (language.getItem() != null && language.getItem() != s.getLanguage()) {
            s.setLanguage(language.getItem());
            MavenDetectiveBundle.reload();
        }
        s.setNotifyRedBuild(notifyRedBuild.isSelected());
        s.setRebuildAfterFix(rebuildAfterFix.isSelected());
        s.setPreviewPomFixes(previewPomFixes.isSelected());
        s.setSnapshots(snapshots.isSelected());
        s.setSnapshotDir(dir);
        s.setUsageViaStarters(usageViaStarters.isSelected());
        s.setFoldDownloads(foldDownloads.isSelected());
        s.setPreflight(preflight.isSelected());
        s.setPreflightNexus(nexus);
        DetectiveSettings.fireChanged();
    }

    @Override
    public void reset() {
        DetectiveSettings s = DetectiveSettings.getInstance();
        language.setItem(s.getLanguage());
        notifyRedBuild.setSelected(s.isNotifyRedBuild());
        rebuildAfterFix.setSelected(s.isRebuildAfterFix());
        previewPomFixes.setSelected(s.isPreviewPomFixes());
        snapshots.setSelected(s.isSnapshots());
        snapshotDir.setText(s.getSnapshotDir());
        snapshotDir.setEnabled(s.isSnapshots());
        usageViaStarters.setSelected(s.isUsageViaStarters());
        foldDownloads.setSelected(s.isFoldDownloads());
        preflight.setSelected(s.isPreflight());
        preflightNexus.setText(s.getPreflightNexus());
        preflightNexus.setEnabled(s.isPreflight());
        firstStepsDone.setText("");
    }

    @Override
    public void disposeUIResources() {
        panel = null;
    }

    /** Andere Seite im selben Einstellungsdialog zeigen. */
    private void select(String id) {
        Settings settings = panel == null ? null : Settings.KEY.getData(DataManager.getInstance().getDataContext(panel));
        Configurable target = settings == null ? null : settings.find(id);
        if (target != null) {
            settings.select(target);
        }
    }

    /** „English“ und „Deutsch“ stehen in ihrer eigenen Sprache, damit man sie auch in der falschen findet. */
    private static String languageName(MavenDetectiveBundle.Language l) {
        return switch (l) {
            case AUTO -> MavenDetectiveBundle.message("settings.language.auto");
            case ENGLISH -> "English";
            case GERMAN -> "Deutsch";
        };
    }

    private static JComponent indent(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(JBUI.Borders.emptyLeft(4));
        p.add(c, BorderLayout.WEST);
        return p;
    }

    /** Kleiner grauer Erklärtext unter einer Option. */
    private static JComponent comment(@Nls String text) {
        JBLabel l = new JBLabel(text);
        l.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        l.setFontColor(UIUtil.FontColor.BRIGHTER);
        l.setCopyable(true);
        l.setAllowAutoWrapping(true);
        l.setBorder(JBUI.Borders.empty(0, 28, 6, 0));
        return l;
    }

    private static String defaultSnapshotRoot() {
        try {
            return io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotStore.defaultStore().getRoot().getParent().toString();
        } catch (RuntimeException e) {
            return Paths.get(System.getProperty("user.home"), ".maven-detective").toString();
        }
    }
}
