package io.github.bitshiftdev.mavendetective.core.diagnosis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ein Quick-Fix als Daten. Ausgeführt wird er von der IDE oder vom {@link io.github.bitshiftdev.mavendetective.core.fix.FixExecutor}.
 *
 * <table>
 * <caption>Typen und Parameter</caption>
 * <tr><td>{@code rebuild}</td><td>{@code args} (z. B. "-U", "-am"), {@code removeArgs} (z. B. "-o")</td></tr>
 * <tr><td>{@code delete-files}</td><td>{@code files} (zeilengetrennt), danach {@code args} für den Neubau</td></tr>
 * <tr><td>{@code open-file}</td><td>{@code file}, {@code line}</td></tr>
 * <tr><td>{@code replace-in-file}</td><td>{@code file}, {@code search}, {@code replace} – in XML-Dateien (settings.xml, POM)
 *     nur ganze Repository-{@code <url>}-Werte, sonst jedes Vorkommen</td></tr>
 * <tr><td>{@code replace-url}</td><td>{@code file} (POM), {@code search}, {@code replace} – ändert eine Repository-Adresse
 *     genau in den {@code <url>}-Werten, die sie ganz enthalten (z. B. http → https); läuft in der IDE mit Vorschau und Undo</td></tr>
 * <tr><td>{@code switch-jdk}</td><td>{@code required} (Java-Feature-Version)</td></tr>
 * <tr><td>{@code set-jvm-option}</td><td>{@code file} (.mvn/jvm.config), {@code option}</td></tr>
 * <tr><td>{@code insert-plugin-version}</td><td>{@code file}, {@code line}, {@code plugin}</td></tr>
 * <tr><td>{@code show-dependency}</td><td>{@code artifact} (Modul A)</td></tr>
 * <tr><td>{@code pin-version}</td><td>{@code file} (POM, meist der gemeinsame Parent), {@code artifact} (groupId:artifactId),
 *     {@code version} – setzt die Version unter {@code dependencyManagement} (Modul A2)</td></tr>
 * <tr><td>{@code remove-exclusion}</td><td>{@code file} (POM), {@code artifact} (Dependency, groupId:artifactId),
 *     {@code exclusion} (groupId:artifactId, auch mit *), {@code section} ({@code dependencies} oder
 *     {@code dependencyManagement}) – entfernt eine veraltete Exclusion (Modul A4)</td></tr>
 * <tr><td>{@code add-exclusion}</td><td>{@code file} (POM), {@code artifact} (direkte Dependency unter {@code dependencies},
 *     groupId:artifactId), {@code exclusion} (groupId:artifactId) – nimmt ein transitives JAR aus dem Classpath,
 *     z. B. wegen doppelter Klassen (Modul A3)</td></tr>
 * <tr><td>{@code add-dependency}</td><td>{@code file} (POM des Moduls), {@code artifact} (groupId:artifactId),
 *     {@code version} (fehlt, wenn verwaltet), {@code scope}, {@code type}, {@code classifier} (nur wenn nicht Standard) –
 *     deklariert eine bisher nur transitiv vorhandene Dependency (Modul A5)</td></tr>
 * <tr><td>{@code remove-dependency}</td><td>{@code file} (POM), {@code artifact} (groupId:artifactId), {@code classifier} –
 *     entfernt eine ungenutzte Dependency aus {@code dependencies} (Modul A5)</td></tr>
 * <tr><td>{@code set-scope}</td><td>{@code file} (POM), {@code artifact} (groupId:artifactId), {@code scope},
 *     {@code classifier} – z. B. auf {@code test}, wenn nur Tests sie nennen (Modul A5)</td></tr>
 * <tr><td>{@code set-version}</td><td>{@code file} (POM), {@code version} und entweder {@code property} (Name; fehlt sie,
 *     wird sie unter {@code properties} angelegt) oder {@code artifact} (groupId:artifactId) mit {@code section}
 *     ({@code parent}, {@code dependencies} oder {@code dependencyManagement}) – hebt eine Version genau dort, wo sie
 *     heute steht (Upgrade-Simulator, Modul A6)</td></tr>
 * <tr><td>{@code manual}</td><td>nur Beschreibung im Label</td></tr>
 * </table>
 */
public final class FixAction {

    public static final String REBUILD = "rebuild";
    public static final String DELETE_FILES = "delete-files";
    public static final String OPEN_FILE = "open-file";
    public static final String REPLACE_IN_FILE = "replace-in-file";
    public static final String REPLACE_URL = "replace-url";
    public static final String SWITCH_JDK = "switch-jdk";
    public static final String SET_JVM_OPTION = "set-jvm-option";
    public static final String INSERT_PLUGIN_VERSION = "insert-plugin-version";
    public static final String SHOW_DEPENDENCY = "show-dependency";
    public static final String PIN_VERSION = "pin-version";
    public static final String REMOVE_EXCLUSION = "remove-exclusion";
    public static final String ADD_EXCLUSION = "add-exclusion";
    public static final String ADD_DEPENDENCY = "add-dependency";
    public static final String REMOVE_DEPENDENCY = "remove-dependency";
    public static final String SET_SCOPE = "set-scope";
    public static final String SET_VERSION = "set-version";
    public static final String MANUAL = "manual";

    private final String type;
    private final String label;
    private final Map<String, String> params;

    public FixAction(String type, String label, Map<String, String> params) {
        this.type = type;
        this.label = label;
        this.params = Collections.unmodifiableMap(new LinkedHashMap<String, String>(params));
    }

    public static FixAction of(String type, String label, String... keyValues) {
        Map<String, String> p = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                p.put(keyValues[i], keyValues[i + 1]);
            }
        }
        return new FixAction(type, label, p);
    }

    public String getType() { return type; }

    public String getLabel() { return label; }

    public Map<String, String> getParams() { return params; }

    public String param(String key) { return params.get(key); }

    @Override
    public String toString() {
        return label + " [" + type + (params.isEmpty() ? "" : " " + params) + "]";
    }
}
