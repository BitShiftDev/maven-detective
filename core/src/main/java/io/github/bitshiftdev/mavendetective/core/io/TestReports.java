package io.github.bitshiftdev.mavendetective.core.io;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Liest die XML-Berichte von Surefire/Failsafe ({@code TEST-*.xml}) eines Moduls. Die Extension sieht von
 * einem gescheiterten Test nur „There are test failures“; die eigentliche Ausnahme ({@code NoSuchMethodError}
 * …) steht nur in der Konsole und in diesen Berichten.
 *
 * <p>Je gescheitertem Test entsteht ein Block wie in der Konsole: Kopfzeile {@code Klasse.methode <<< ERROR!},
 * Ausnahme, die ersten Stack-Frames und jede {@code Caused by:}-Zeile. Nur lesend, wirft nie.
 */
public final class TestReports {

    /** Höchstens so viele gescheiterte Tests (je Modul). */
    static final int MAX_FAILURES = 20;
    /** Stack-Frames nach jeder Ausnahmezeile. */
    static final int MAX_FRAMES = 6;
    /** Zeilen je gescheitertem Test. */
    static final int MAX_LINES = 40;
    /** Größere Berichte (riesige system-out-Abschnitte) werden übersprungen. */
    static final long MAX_FILE_SIZE = 16L * 1024 * 1024;
    private static final int MAX_FILES = 2000;

    private TestReports() {
    }

    /**
     * Gescheiterte Tests aus {@code reportsDirectory}, je Test ein Textblock.
     *
     * @param notBefore ältere Berichte (Epoch-Millisekunden) stammen aus einem früheren Lauf und zählen nicht;
     *                  {@code 0} = alle
     */
    public static List<String> failures(Path reportsDirectory, long notBefore) {
        List<String> out = new ArrayList<String>();
        if (reportsDirectory == null || !Files.isDirectory(reportsDirectory)) {
            return out;
        }
        List<Path> files = new ArrayList<Path>();
        try {
            DirectoryStream<Path> ds = Files.newDirectoryStream(reportsDirectory, "TEST-*.xml");
            try {
                for (Path p : ds) {
                    if (files.size() >= MAX_FILES) {
                        break;
                    }
                    if (Files.isRegularFile(p) && Files.size(p) <= MAX_FILE_SIZE
                            && Files.getLastModifiedTime(p).toMillis() >= notBefore) {
                        files.add(p);
                    }
                }
            } finally {
                ds.close();
            }
        } catch (IOException | RuntimeException e) {
            return out;
        }
        Collections.sort(files);
        for (Path p : files) {
            if (out.size() >= MAX_FAILURES) {
                break;
            }
            try {
                read(p.toFile(), out);
            } catch (Exception e) {
                // unvollständiger Bericht (Test-JVM abgestürzt) – nächster
            }
        }
        return out.size() > MAX_FAILURES ? new ArrayList<String>(out.subList(0, MAX_FAILURES)) : out;
    }

    private static void read(File file, final List<String> out) throws Exception {
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setNamespaceAware(false);
        SAXParser parser = f.newSAXParser();
        parser.parse(file, new DefaultHandler() {
            private String testClass;
            private String testName;
            private String kind;
            private String type;
            private String message;
            private StringBuilder text;

            @Override
            public void startElement(String uri, String local, String qName, Attributes atts) {
                if ("testcase".equals(qName)) {
                    testClass = atts.getValue("classname");
                    testName = atts.getValue("name");
                } else if (("failure".equals(qName) || "error".equals(qName)) && testName != null) {
                    kind = "failure".equals(qName) ? "FAILURE" : "ERROR";
                    type = atts.getValue("type");
                    message = atts.getValue("message");
                    text = new StringBuilder();
                }
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                if (text != null && text.length() < 200000) {
                    text.append(ch, start, length);
                }
            }

            @Override
            public void endElement(String uri, String local, String qName) {
                if (text != null && ("failure".equals(qName) || "error".equals(qName))) {
                    if (out.size() < MAX_FAILURES) {
                        out.add(block(testClass, testName, kind, type, message, text.toString()));
                    }
                    text = null;
                } else if ("testcase".equals(qName)) {
                    testClass = null;
                    testName = null;
                }
            }
        });
    }

    static String block(String testClass, String testName, String kind, String type, String message, String trace) {
        StringBuilder sb = new StringBuilder();
        sb.append(testClass == null || testClass.isEmpty() ? "" : testClass + ".")
                .append(testName).append(" <<< ").append(kind).append('!');
        int lines = 1;
        int frames = 0;
        boolean any = false;
        for (String raw : trace.split("\\r?\\n")) {
            if (lines >= MAX_LINES) {
                break;
            }
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("... ")) {
                continue;
            }
            if (line.startsWith("at ")) {
                if (!any || frames >= MAX_FRAMES) {
                    continue;
                }
                frames++;
                sb.append("\n\t").append(line);
            } else {
                frames = 0;
                any = true;
                sb.append('\n').append(line);
            }
            lines++;
        }
        if (!any) {
            // Bericht ohne Stacktrace (trimStackTrace, eigene Reporter): Typ und Meldung aus den Attributen
            String head = type == null ? "" : type;
            if (message != null && !message.isEmpty()) {
                head = head.isEmpty() ? message : head + ": " + message;
            }
            if (!head.isEmpty()) {
                sb.append('\n').append(head);
            }
        }
        return sb.toString();
    }
}
