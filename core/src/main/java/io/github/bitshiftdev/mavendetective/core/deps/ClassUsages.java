package io.github.bitshiftdev.mavendetective.core.deps;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Welche Klassen der Quelltext eines Moduls nennt – getrennt nach Haupt- und Testquellen, mit der
 * ersten Fundstelle als Beleg. Grundlage für Modul A5 (ungenutzte und undeklarierte Dependencies).
 *
 * <p>Gefüllt wird es von der IDE (aufgelöste Referenzen über den Code-Index) oder vom
 * {@link SourceScanner} (Imports und voll qualifizierte Namen im Text). Namen dürfen ungenau sein:
 * innere Klassen ({@code a.B.C}), statische Member ({@code a.B.method}) und Namen, die gar nicht im
 * Classpath liegen (JDK, eigene Klassen), sortiert erst die Analyse aus.
 */
public final class ClassUsages {

    /** Erste Fundstelle einer Klasse. */
    public static final class Use {
        private final String className;
        private final Path file;
        private final int line;

        Use(String className, Path file, int line) {
            this.className = className;
            this.file = file;
            this.line = line;
        }

        /** Name wie gefunden (kann eine innere Klasse oder ein Member sein). */
        public String getClassName() { return className; }
        public Path getFile() { return file; }
        /** Zeile ab 1; 0, wenn unbekannt. */
        public int getLine() { return line; }

        /** {@code datei:zeile} zum Hinspringen. */
        public String location() {
            return file == null ? "" : file + (line > 0 ? ":" + line : "");
        }

        @Override
        public String toString() {
            return className + (file == null ? "" : " (" + file.getFileName() + (line > 0 ? ":" + line : "") + ")");
        }
    }

    /** Ein {@code import paket.*}: welche einfachen Namen die Datei sonst benutzt. */
    static final class Wildcard {
        final String pkg;
        final Set<String> simpleNames;
        final Path file;
        final int line;

        Wildcard(String pkg, Set<String> simpleNames, Path file, int line) {
            this.pkg = pkg;
            this.simpleNames = simpleNames;
            this.file = file;
            this.line = line;
        }
    }

    private final Map<String, Use> main = new LinkedHashMap<String, Use>();
    private final Map<String, Use> test = new LinkedHashMap<String, Use>();
    private final List<Wildcard> mainWildcards = new ArrayList<Wildcard>();
    private final List<Wildcard> testWildcards = new ArrayList<Wildcard>();
    private int mainFiles;
    private int testFiles;

    /** Eine genannte Klasse; nur die erste Fundstelle je Klasse wird behalten. */
    public synchronized void add(String className, boolean inTest, Path file, int line) {
        if (className == null || className.isEmpty()) {
            return;
        }
        Map<String, Use> m = inTest ? test : main;
        if (!m.containsKey(className)) {
            m.put(className, new Use(className, file, line));
        }
    }

    /** {@code import pkg.*} – aufgelöst wird später gegen die Klassen im Classpath. */
    public synchronized void addWildcard(String pkg, Collection<String> simpleNames, boolean inTest, Path file, int line) {
        (inTest ? testWildcards : mainWildcards).add(new Wildcard(pkg, new LinkedHashSet<String>(simpleNames), file, line));
    }

    /** Alles aus {@code other} übernehmen (erste Fundstelle gewinnt) – z. B. Ergebnis einer einzelnen Datei. */
    public void addAll(ClassUsages other) {
        if (other == this) {
            return;
        }
        Map<String, Use> otherMain;
        Map<String, Use> otherTest;
        List<Wildcard> otherMainWildcards;
        List<Wildcard> otherTestWildcards;
        int om;
        int ot;
        synchronized (other) {
            otherMain = new LinkedHashMap<String, Use>(other.main);
            otherTest = new LinkedHashMap<String, Use>(other.test);
            otherMainWildcards = new ArrayList<Wildcard>(other.mainWildcards);
            otherTestWildcards = new ArrayList<Wildcard>(other.testWildcards);
            om = other.mainFiles;
            ot = other.testFiles;
        }
        synchronized (this) {
            for (Map.Entry<String, Use> e : otherMain.entrySet()) {
                if (!main.containsKey(e.getKey())) {
                    main.put(e.getKey(), e.getValue());
                }
            }
            for (Map.Entry<String, Use> e : otherTest.entrySet()) {
                if (!test.containsKey(e.getKey())) {
                    test.put(e.getKey(), e.getValue());
                }
            }
            mainWildcards.addAll(otherMainWildcards);
            testWildcards.addAll(otherTestWildcards);
            mainFiles += om;
            testFiles += ot;
        }
    }

    /** Eine Quelldatei wurde gelesen (zählt für „Modul hat Quellen“). */
    public synchronized void countFile(boolean inTest) {
        if (inTest) {
            testFiles++;
        } else {
            mainFiles++;
        }
    }

    public synchronized int getMainFiles() { return mainFiles; }
    public synchronized int getTestFiles() { return testFiles; }

    synchronized Collection<Use> uses(boolean inTest) {
        return Collections.unmodifiableCollection(new ArrayList<Use>((inTest ? test : main).values()));
    }

    synchronized List<Wildcard> wildcards(boolean inTest) {
        return new ArrayList<Wildcard>(inTest ? testWildcards : mainWildcards);
    }

    /** Für Tests und Ausgabe: alle genannten Namen. */
    public synchronized Set<String> names(boolean inTest) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>((inTest ? test : main).keySet()));
    }
}
