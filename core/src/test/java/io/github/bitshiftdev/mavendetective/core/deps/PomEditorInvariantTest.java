package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.deps.PomEditor.Edit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Jede POM-Änderung des {@link PomEditor} auf jeder POM-Form unter {@code resources/poms/}. Geprüft
 * wird nicht der Text, sondern was ein XML-Parser ({@link PomFile}) danach liest:
 * <ol>
 *   <li>Gilt die Änderung für die POM, ändert sich genau das Erwartete – Profile, Properties, andere
 *       Dependencies und Plugins bleiben, wie sie sind. Sonst lehnt der Editor mit
 *       {@link IllegalArgumentException} ab.</li>
 *   <li>Die Datei bleibt gültiges XML.</li>
 *   <li>Ein zweites Anwenden ändert nichts mehr oder wird abgelehnt.</li>
 *   <li>Zeilenende und Einrückungszeichen der Datei bleiben erhalten.</li>
 * </ol>
 * Neue POM-Form: Datei unter {@code resources/poms/} ablegen und in {@link #FORMS} eintragen.
 */
class PomEditorInvariantTest {

    /** {@code pom:project} fehlt bewusst: {@link PomFile} liest Namensraum-Präfixe nicht (siehe PomScanTest). */
    private static final String[] FORMS = {
        "spring-boot.xml", "one-line.xml", "tabs-crlf.xml", "comments.xml", "profiles.xml", "self-closing.xml",
        "entities-cdata.xml", "minimal.xml", "build-only.xml", "classifiers.xml", "managed-property.xml",
        "bom-crlf-no-final-newline.xml",
    };
    private static final Path FILE = Paths.get("pom.xml");
    private static final String LIB = "dep:org.lib:lib:jar:";
    private static final String MANAGED_LIB = "managed:org.lib:lib:jar:";
    private static final String NEW = "org.new:new";
    private static final String NEW_VERSION = "9.9";

    /** Eine Änderung: wann sie gilt, was der Editor tut und wie der Fingerabdruck danach aussieht. */
    private interface Operation {
        boolean appliesTo(Map<String, String> before);

        Edit edit(String xml);

        /** Erwarteter Fingerabdruck nach der Änderung; {@code print} ist eine Kopie und darf geändert werden. */
        void expect(Map<String, String> print);

        /** Ändert ein zweites Anwenden nichts mehr (oder wird abgelehnt)? */
        default boolean isIdempotent() {
            return true;
        }
    }

    private static final Map<String, Operation> OPERATIONS = new LinkedHashMap<String, Operation>();

    static {
        OPERATIONS.put("pin-version (vorhanden)", new Operation() {
            public boolean appliesTo(Map<String, String> before) {
                return true;
            }

            public Edit edit(String xml) {
                return PomEditor.pinManagedVersion(xml, "org.lib", "lib", NEW_VERSION);
            }

            public void expect(Map<String, String> print) {
                String managed = print.get(MANAGED_LIB);
                if (managed == null) {
                    print.put(MANAGED_LIB, dep(NEW_VERSION, null));
                    return;
                }
                String version = field(managed, 0);
                String property = version.startsWith("${") ? "prop:" + version.substring(2, version.length() - 1) : null;
                if (property != null && print.containsKey(property)) {
                    print.put(property, NEW_VERSION);
                } else {
                    print.put(MANAGED_LIB, withField(managed, 0, NEW_VERSION));
                }
            }
        });
        OPERATIONS.put("pin-version (neu)", new Operation() {
            public boolean appliesTo(Map<String, String> before) {
                return true;
            }

            public Edit edit(String xml) {
                return PomEditor.pinManagedVersion(xml, "org.new", "new", "1.0");
            }

            public void expect(Map<String, String> print) {
                print.put("managed:" + NEW + ":jar:", dep("1.0", null));
            }
        });
        OPERATIONS.put("add-dependency", new Operation() {
            public boolean appliesTo(Map<String, String> before) {
                return true;
            }

            public Edit edit(String xml) {
                return PomEditor.addDependency(xml, "org.new", "new", "1.0", "test", null, null);
            }

            public void expect(Map<String, String> print) {
                print.put("dep:" + NEW + ":jar:", dep("1.0", "test"));
            }
        });
        OPERATIONS.put("remove-dependency", new Operation() {
            public boolean appliesTo(Map<String, String> before) {
                return before.containsKey(LIB);
            }

            public Edit edit(String xml) {
                return PomEditor.removeDependency(xml, "org.lib", "lib", null);
            }

            public void expect(Map<String, String> print) {
                print.remove(LIB);
            }

            /** Ohne Klassifikator und Typ nimmt der Editor beim zweiten Mal den nächsten Eintrag (test-jar). */
            public boolean isIdempotent() {
                return false;
            }
        });
        OPERATIONS.put("set-scope", new Operation() {
            public boolean appliesTo(Map<String, String> before) {
                return before.containsKey(LIB);
            }

            public Edit edit(String xml) {
                return PomEditor.setScope(xml, "org.lib", "lib", "runtime", null);
            }

            public void expect(Map<String, String> print) {
                print.put(LIB, withField(print.get(LIB), 1, "runtime"));
            }
        });
        OPERATIONS.put("set-version (dependencies)", new Operation() {
            public boolean appliesTo(Map<String, String> before) {
                return before.containsKey(LIB) && !"null".equals(field(before.get(LIB), 0));
            }

            public Edit edit(String xml) {
                return PomEditor.setVersion(xml, "dependencies", "org.lib", "lib", NEW_VERSION);
            }

            public void expect(Map<String, String> print) {
                print.put(LIB, withField(print.get(LIB), 0, NEW_VERSION));
            }
        });
        OPERATIONS.put("set-version (neue Property)", new Operation() {
            public boolean appliesTo(Map<String, String> before) {
                return true;
            }

            public Edit edit(String xml) {
                return PomEditor.setProperty(xml, "new.prop", "a&b");
            }

            public void expect(Map<String, String> print) {
                print.put("prop:new.prop", "a&b");
            }
        });
        OPERATIONS.put("set-version (Property)", new Operation() {
            public boolean appliesTo(Map<String, String> before) {
                return true;
            }

            public Edit edit(String xml) {
                return PomEditor.setProperty(xml, "lib.version", NEW_VERSION);
            }

            public void expect(Map<String, String> print) {
                print.put("prop:lib.version", NEW_VERSION);
            }
        });
        OPERATIONS.put("add-exclusion", new Operation() {
            public boolean appliesTo(Map<String, String> before) {
                return before.containsKey(LIB);
            }

            public Edit edit(String xml) {
                return PomEditor.addExclusion(xml, "org.lib", "lib", NEW);
            }

            public void expect(Map<String, String> print) {
                List<String> exclusions = exclusions(print.get(LIB));
                exclusions.add(NEW);
                print.put(LIB, withField(print.get(LIB), 3, join(exclusions)));
            }
        });
        OPERATIONS.put("remove-exclusion", new Operation() {
            public boolean appliesTo(Map<String, String> before) {
                return before.containsKey(LIB) && exclusions(before.get(LIB)).contains("org.ex:ex");
            }

            public Edit edit(String xml) {
                return PomEditor.removeExclusion(xml, "org.lib", "lib", "org.ex:ex", false);
            }

            public void expect(Map<String, String> print) {
                List<String> exclusions = exclusions(print.get(LIB));
                exclusions.remove("org.ex:ex");
                print.put(LIB, withField(print.get(LIB), 3, join(exclusions)));
            }
        });
    }

    static List<Arguments> cases() {
        List<Arguments> out = new ArrayList<Arguments>();
        for (String form : FORMS) {
            for (String op : OPERATIONS.keySet()) {
                out.add(Arguments.of(form, op));
            }
        }
        return out;
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("cases")
    void editChangesExactlyWhatItSaysAndKeepsTheFileIntact(String form, String name) throws Exception {
        String xml = resource(form);
        Operation op = OPERATIONS.get(name);
        Map<String, String> before = fingerprint(xml);

        Edit edit;
        try {
            edit = op.edit(xml);
        } catch (IllegalArgumentException refused) {
            assertFalse(op.appliesTo(before), "abgelehnt, obwohl die Änderung gilt: " + refused.getMessage());
            return;
        }
        assertTrue(op.appliesTo(before), "geändert, obwohl die Änderung nicht gilt: " + edit.description);
        String after = edit.apply(xml);

        Map<String, String> expected = new TreeMap<String, String>(before);
        op.expect(expected);
        assertEquals(expected, fingerprint(after), "Fingerabdruck nach der Änderung\n" + after);
        assertLineEndingsKept(xml, after);
        assertIndentCharactersKept(xml, after);
        assertTrue(edit.line >= 1 && edit.line <= after.split("\n", -1).length, "Sprungzeile " + edit.line);
        if (op.isIdempotent()) {
            assertSecondEditChangesNothing(op, after);
        }
    }

    // --- Invarianten -----------------------------------------------------------------------------

    private static void assertLineEndingsKept(String before, String after) {
        boolean crlf = before.contains("\r\n");
        int lf = count(after, "\n");
        int crlfCount = count(after, "\r\n");
        if (crlf) {
            assertEquals(lf, crlfCount, "jede Zeile mit CRLF\n" + after);
        } else {
            assertEquals(0, crlfCount, "kein CRLF in einer LF-Datei\n" + after);
        }
    }

    /** Eine Datei, die nur mit Tabs (bzw. nur mit Leerzeichen) einrückt, tut das auch danach. */
    private static void assertIndentCharactersKept(String before, String after) {
        boolean tabs = hasLineStartingWith(before, '\t');
        boolean spaces = hasLineStartingWith(before, ' ');
        if (tabs && !spaces) {
            assertFalse(hasLineStartingWith(after, ' '), "Leerzeichen-Einrückung in einer Tab-Datei\n" + after);
        }
        if (spaces && !tabs) {
            assertFalse(hasLineStartingWith(after, '\t'), "Tab-Einrückung in einer Leerzeichen-Datei\n" + after);
        }
    }

    private static void assertSecondEditChangesNothing(Operation op, String after) {
        Edit again;
        try {
            again = op.edit(after);
        } catch (IllegalArgumentException refused) {
            return;
        }
        assertEquals(after, again.apply(after), "zweites Anwenden änderte die Datei: " + again.description);
    }

    // --- Fingerabdruck ---------------------------------------------------------------------------

    /**
     * Was ein XML-Parser aus der POM liest, als sortierte Map: Projekt, Parent, Module, Properties,
     * Dependencies und verwaltete Dependencies (Wert = {@code version|scope|optional|exclusions}),
     * dazu dasselbe für jedes Profil.
     */
    private static Map<String, String> fingerprint(String xml) {
        PomFile pom;
        try {
            pom = PomFile.parse(FILE, xml);
        } catch (Exception e) {
            throw new AssertionError("kein gültiges XML mehr: " + e.getMessage() + "\n" + xml, e);
        }
        Map<String, String> print = new TreeMap<String, String>();
        print.put("project", pom.gav() + ":" + pom.getPackaging());
        print.put("parent", pom.hasParent() ? pom.parentGav() : "-");
        print.put("modules", pom.getModules().toString());
        for (PomFile.Property p : pom.getProperties().values()) {
            put(print, "prop:" + p.getName(), p.getValue());
        }
        deps(print, "dep:", pom.getDependencies());
        deps(print, "managed:", pom.getManagedDependencies());
        for (PomFile.Profile profile : pom.getProfiles()) {
            String prefix = "profile:" + profile.getId() + ":";
            for (Map.Entry<String, String> p : profile.getProperties().entrySet()) {
                put(print, prefix + "prop:" + p.getKey(), p.getValue());
            }
            deps(print, prefix + "dep:", profile.getDependencies());
        }
        return print;
    }

    private static void deps(Map<String, String> print, String prefix, List<PomFile.Dep> deps) {
        for (PomFile.Dep d : deps) {
            String key = prefix + d.ga() + ":" + d.getType() + ":" + (d.getClassifier() == null ? "" : d.getClassifier());
            List<String> exclusions = new ArrayList<String>(d.getExclusions());
            put(print, key, d.getVersion() + "|" + d.getScope() + "|" + d.isOptional() + "|" + join(exclusions));
        }
    }

    private static void put(Map<String, String> print, String key, String value) {
        assertNull(print.put(key, value), "doppelter Eintrag " + key);
    }

    private static String dep(String version, String scope) {
        return version + "|" + scope + "|false|";
    }

    private static String field(String value, int index) {
        return value.split("\\|", -1)[index];
    }

    private static String withField(String value, int index, String replacement) {
        String[] parts = value.split("\\|", -1);
        parts[index] = replacement;
        return String.join("|", parts);
    }

    private static List<String> exclusions(String value) {
        String raw = field(value, 3);
        List<String> out = new ArrayList<String>();
        if (!raw.isEmpty()) {
            Collections.addAll(out, raw.split(","));
        }
        return out;
    }

    private static String join(List<String> values) {
        List<String> sorted = new ArrayList<String>(values);
        Collections.sort(sorted);
        return String.join(",", sorted);
    }

    // --- Hilfen ----------------------------------------------------------------------------------

    private static String resource(String name) throws IOException {
        try (InputStream in = PomEditorInvariantTest.class.getResourceAsStream("/poms/" + name)) {
            if (in == null) {
                fail("Test-POM fehlt: " + name);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            n++;
        }
        return n;
    }

    private static boolean hasLineStartingWith(String text, char c) {
        for (String line : text.split("\n")) {
            if (!line.isEmpty() && line.charAt(0) == c) {
                return true;
            }
        }
        return false;
    }
}
