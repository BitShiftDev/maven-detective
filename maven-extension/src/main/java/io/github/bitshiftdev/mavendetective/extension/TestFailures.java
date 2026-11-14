package io.github.bitshiftdev.mavendetective.extension;

import io.github.bitshiftdev.mavendetective.core.io.EventLog;
import io.github.bitshiftdev.mavendetective.core.io.TestReports;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scheitert Surefire oder Failsafe, meldet Maven nur „There are test failures“. Die Ausnahme des Tests
 * ({@code NoSuchMethodError}, {@code NoClassDefFoundError} …) steht in den XML-Berichten; dieses Event
 * trägt sie nach. Liest nur Dateien dieses Laufs und schreibt nichts.
 */
final class TestFailures {

    /** Zeitstempel von Dateisystemen sind teils nur sekundengenau. */
    private static final long CLOCK_TOLERANCE = 2000;

    private TestFailures() {
    }

    /** Event {@code testFailures} zu einem {@code MojoFailed} von Surefire/Failsafe, sonst {@code null}. Wirft nie. */
    static Map<String, Object> event(ExecutionEvent ee, long startedAt, Logger log) {
        try {
            if (ee.getType() != ExecutionEvent.Type.MojoFailed) {
                return null;
            }
            MojoExecution m = ee.getMojoExecution();
            MavenProject p = ee.getProject();
            if (m == null || p == null) {
                return null;
            }
            String folder = defaultFolder(m.getArtifactId());
            if (folder == null) {
                return null;
            }
            Path reports = reportsDirectory(m, p, folder);
            if (reports == null) {
                return null;
            }
            List<String> failures = TestReports.failures(reports, startedAt - CLOCK_TOLERANCE);
            if (failures.isEmpty()) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            for (String f : failures) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(f);
            }
            Map<String, Object> e = new LinkedHashMap<String, Object>();
            e.put("type", EventLog.TYPE_TEST_FAILURES);
            e.put("project", p.getGroupId() + ":" + p.getArtifactId() + ":" + p.getVersion());
            e.put("mojo", m.getGroupId() + ":" + m.getArtifactId() + ":" + m.getVersion() + ":" + m.getGoal());
            e.put("execution", m.getExecutionId());
            e.put("reports", reports.toString());
            e.put("text", text.toString());
            return e;
        } catch (Throwable t) {
            log.debug("Maven Detective: Testberichte übersprungen", t);
            return null;
        }
    }

    static String defaultFolder(String pluginArtifactId) {
        if ("maven-surefire-plugin".equals(pluginArtifactId)) {
            return "surefire-reports";
        }
        if ("maven-failsafe-plugin".equals(pluginArtifactId)) {
            return "failsafe-reports";
        }
        return null;
    }

    /** {@code reportsDirectory} aus der Konfiguration, soweit ohne Maven-Auswertung lesbar, sonst der Standard. */
    private static Path reportsDirectory(MojoExecution m, MavenProject p, String folder) {
        File base = p.getBasedir();
        String target = p.getBuild() == null ? null : p.getBuild().getDirectory();
        String configured = null;
        Xpp3Dom config = m.getConfiguration();
        Xpp3Dom dir = config == null ? null : config.getChild("reportsDirectory");
        if (dir != null) {
            configured = dir.getValue() != null && !dir.getValue().trim().isEmpty() ? dir.getValue().trim()
                    : dir.getAttribute("default-value");
        }
        String path = resolve(configured, target, base);
        if (path == null) {
            if (target == null) {
                return null;
            }
            path = new File(target, folder).getPath();
        }
        File f = new File(path);
        if (!f.isAbsolute() && base != null) {
            f = new File(base, path);
        }
        return f.toPath();
    }

    /** Ersetzt die üblichen Ausdrücke; bleibt einer übrig ({@code ${reportsDirectory}} …), gilt der Standard. */
    static String resolve(String value, String target, File base) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String v = value;
        if (target != null) {
            v = v.replace("${project.build.directory}", target);
        }
        if (base != null) {
            v = v.replace("${project.basedir}", base.getPath()).replace("${basedir}", base.getPath());
        }
        return v.contains("${") ? null : v;
    }
}
