package io.github.bitshiftdev.mavendetective.core.io;

import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import io.github.bitshiftdev.mavendetective.core.model.ErrorRecord;
import io.github.bitshiftdev.mavendetective.core.model.ExceptionInfo;
import io.github.bitshiftdev.mavendetective.core.model.PomProblem;
import io.github.bitshiftdev.mavendetective.core.model.RepositoryRequest;
import io.github.bitshiftdev.mavendetective.core.model.SessionInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Format der Event-Datei: eine JSON-Zeile pro Event. Die Extension schreibt sie, IDE und CLI lesen sie.
 *
 * <pre>
 * {"type":"session","maven":"3.9.11","java":"21.0.5","javaHome":"…","offline":false,
 *  "userSettings":"…","globalSettings":"…","localRepository":"…","projectDirectory":"…",
 *  "profiles":["ci"],"goals":["verify"]}
 * {"type":"repository","event":"ARTIFACT_DOWNLOADED","artifact":"g:a:jar:1.0",
 *  "repositoryId":"central","repositoryUrl":"https://…","failure":"…"}
 * {"type":"mojoFailed","project":"g:a:1.0","mojo":"g:a:v:goal","execution":"default-compile",
 *  "chain":[{"class":"…","message":"…"}]}
 * {"type":"buildError","project":"…","chain":[…],"problems":[{"source":"…","line":6,"column":17,
 *  "severity":"ERROR","message":"…"}]}
 * </pre>
 *
 * Unbekannte Typen und Felder werden ignoriert, damit ältere Leser neuere Dateien verstehen.
 */
public final class EventLog {

    public static final int FORMAT_VERSION = 1;

    public static final String TYPE_SESSION = "session";
    public static final String TYPE_REPOSITORY = "repository";
    public static final String TYPE_MOJO_FAILED = "mojoFailed";
    public static final String TYPE_PROJECT_FAILED = "projectFailed";
    public static final String TYPE_BUILD_ERROR = "buildError";

    private EventLog() {
    }

    public static BuildFailure read(Path file) throws IOException {
        InputStream in = Files.newInputStream(file);
        try {
            return read(new InputStreamReader(in, StandardCharsets.UTF_8));
        } finally {
            in.close();
        }
    }

    public static BuildFailure read(Reader reader) throws IOException {
        List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();
        BufferedReader br = new BufferedReader(reader);
        String line;
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                events.add(Json.parseObject(line));
            } catch (IllegalArgumentException e) {
                // abgeschnittene letzte Zeile (Build abgebrochen) – Rest trotzdem auswerten
            }
        }
        return fromEvents(events);
    }

    public static BuildFailure fromEvents(List<Map<String, Object>> events) {
        BuildFailure failure = new BuildFailure(BuildFailure.Source.EVENTS);
        for (Map<String, Object> e : events) {
            String type = str(e, "type");
            if (TYPE_SESSION.equals(type)) {
                readSession(e, failure.getSession());
            } else if (TYPE_REPOSITORY.equals(type)) {
                failure.getRepositoryRequests().add(new RepositoryRequest(
                        str(e, "event"), str(e, "artifact"), str(e, "repositoryId"),
                        str(e, "repositoryUrl"), str(e, "failure")));
            } else if (TYPE_MOJO_FAILED.equals(type) || TYPE_PROJECT_FAILED.equals(type)
                    || TYPE_BUILD_ERROR.equals(type)) {
                ErrorRecord r = new ErrorRecord();
                r.setProject(str(e, "project"));
                r.setMojo(str(e, "mojo"));
                r.setExecution(str(e, "execution"));
                r.setText(str(e, "text"));
                for (Map<String, Object> c : maps(e.get("chain"))) {
                    r.getChain().add(new ExceptionInfo(str(c, "class"), str(c, "message")));
                }
                for (Map<String, Object> p : maps(e.get("problems"))) {
                    r.getProblems().add(new PomProblem(str(p, "source"), integer(p, "line"),
                            integer(p, "column"), str(p, "severity"), str(p, "message")));
                }
                if (!isDuplicate(failure.getErrors(), r)) {
                    failure.getErrors().add(r);
                }
            }
        }
        return failure;
    }

    /** MojoFailed und ProjectFailed melden oft dieselbe Exception – nur einmal zählen. */
    private static boolean isDuplicate(List<ErrorRecord> existing, ErrorRecord candidate) {
        for (ErrorRecord r : existing) {
            if (r.lines().equals(candidate.lines())) {
                return true;
            }
        }
        return false;
    }

    private static void readSession(Map<String, Object> e, SessionInfo s) {
        s.setMavenVersion(str(e, "maven"));
        s.setJavaVersion(str(e, "java"));
        s.setJavaHome(str(e, "javaHome"));
        Object offline = e.get("offline");
        s.setOffline(offline instanceof Boolean ? (Boolean) offline : null);
        s.setUserSettings(str(e, "userSettings"));
        s.setGlobalSettings(str(e, "globalSettings"));
        s.setLocalRepository(str(e, "localRepository"));
        s.setProjectDirectory(str(e, "projectDirectory"));
        s.getActiveProfiles().addAll(strings(e.get("profiles")));
        s.getGoals().addAll(strings(e.get("goals")));
    }

    static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static int integer(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).intValue() : -1;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object o) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (o instanceof List) {
            for (Object item : (List<Object>) o) {
                if (item instanceof Map) {
                    out.add((Map<String, Object>) item);
                }
            }
        }
        return out;
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<String>();
        if (o instanceof List) {
            for (Object item : (List<?>) o) {
                if (item != null) {
                    out.add(item.toString());
                }
            }
        }
        return out;
    }
}
