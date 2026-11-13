package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Cancellation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest, welche Klassen ein Modul im Quelltext nennt – ohne Compiler und ohne IDE, für die
 * Kommandozeile und als Rückfall für Kotlin/Groovy in der IDE (Modul A5).
 *
 * <p>Gezählt werden Imports (auch statische), {@code import paket.*} samt der einfachen Namen der
 * Datei und voll qualifizierte Namen im Code. Kommentare und Zeichenketten zählen nicht. Was nicht
 * im Classpath liegt (JDK, eigene Klassen, Variablen wie {@code foo.Bar}), sortiert die Analyse aus.
 * Nicht erkannt werden Klassen, die nur über Typinferenz vorkommen, ohne genannt zu werden – das ist
 * Absicht: deklarieren muss man, was man beim Namen nennt.
 */
public final class SourceScanner {

    private static final List<String> LANGUAGES = Arrays.asList("java", "kotlin", "groovy");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([\\w.]+?)(\\.\\*)?(?:\\s+as\\s+\\w+)?\\s*;?\\s*$");
    private static final Pattern QUALIFIED = Pattern.compile("(?<![\\w.$])((?:[a-z_][\\w]*\\.)+[A-Z][\\w]*)");
    private static final Pattern SIMPLE = Pattern.compile("(?<![\\w.$])([A-Z][\\w]*)");
    private static final long MAX_FILE = 2L * 1024 * 1024;

    private SourceScanner() {
    }

    /** Quellverzeichnisse eines Moduls nach Maven-Konvention, je mit Testkennung. */
    public static List<Root> roots(Path moduleDir) {
        List<Root> out = new ArrayList<Root>();
        for (String lang : LANGUAGES) {
            out.add(new Root(moduleDir.resolve("src").resolve("main").resolve(lang), false));
            out.add(new Root(moduleDir.resolve("src").resolve("test").resolve(lang), true));
        }
        // erzeugte Quellen (Annotation-Prozessoren, OpenAPI …) nennen ebenfalls Klassen
        addChildren(out, moduleDir.resolve("target").resolve("generated-sources"), false);
        addChildren(out, moduleDir.resolve("target").resolve("generated-test-sources"), true);
        return out;
    }

    /** Ein Quellverzeichnis. */
    public static final class Root {
        public final Path dir;
        public final boolean test;

        Root(Path dir, boolean test) {
            this.dir = dir;
            this.test = test;
        }
    }

    private static void addChildren(List<Root> out, Path dir, boolean test) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try {
            DirectoryStream<Path> ds = Files.newDirectoryStream(dir);
            try {
                for (Path p : ds) {
                    if (Files.isDirectory(p)) {
                        out.add(new Root(p, test));
                    }
                }
            } finally {
                ds.close();
            }
        } catch (IOException e) {
            // nicht lesbar: dann eben ohne erzeugte Quellen
        }
    }

    /** Alle Quellen unter den konventionellen Verzeichnissen des Moduls lesen. */
    public static ClassUsages scan(Path moduleDir, Cancellation cancellation) {
        ClassUsages usages = new ClassUsages();
        for (Root r : roots(moduleDir)) {
            scanDirectory(r.dir, r.test, usages, cancellation);
        }
        return usages;
    }

    /** Alle {@code .java}, {@code .kt}, {@code .groovy} unter {@code dir}. */
    public static void scanDirectory(Path dir, final boolean test, final ClassUsages into, Cancellation cancellation) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        final Cancellation cancel = cancellation == null ? Cancellation.NONE : cancellation;
        final List<Path> files = new ArrayList<Path>();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isSource(file.getFileName().toString()) && attrs.size() <= MAX_FILE) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return;
        }
        for (Path f : files) {
            cancel.check();
            String text;
            try {
                text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            scanText(text, test, f, into);
        }
    }

    public static boolean isSource(String fileName) {
        return fileName.endsWith(".java") || fileName.endsWith(".kt") || fileName.endsWith(".groovy");
    }

    /** Eine Datei: Imports, Wildcards, voll qualifizierte Namen. */
    public static void scanText(String text, boolean test, Path file, ClassUsages into) {
        into.countFile(test);
        String code = stripCommentsAndStrings(text);
        int[] lineStarts = lineStarts(code);

        Matcher pm = PACKAGE.matcher(code);
        int bodyFrom = 0;
        if (pm.find()) {
            bodyFrom = pm.end();
        }
        List<String[]> wildcards = new ArrayList<String[]>();
        Matcher im = IMPORT.matcher(code);
        while (im.find()) {
            String name = im.group(2);
            int line = line(lineStarts, im.start(2));
            boolean isStatic = im.group(1) != null;
            if (im.group(3) != null && !isStatic) {
                wildcards.add(new String[] {name, String.valueOf(line)});
            } else {
                into.add(name, test, file, line); // statisch: a.B.member oder a.B (bei .*) – die Analyse kürzt
            }
            bodyFrom = Math.max(bodyFrom, im.end());
        }

        String body = code.substring(bodyFrom);
        Matcher qm = QUALIFIED.matcher(body);
        while (qm.find()) {
            into.add(qm.group(1), test, file, line(lineStarts, bodyFrom + qm.start(1)));
        }
        if (!wildcards.isEmpty()) {
            Set<String> simple = new LinkedHashSet<String>();
            Matcher sm = SIMPLE.matcher(body);
            while (sm.find()) {
                simple.add(sm.group(1));
            }
            for (String[] w : wildcards) {
                into.addWildcard(w[0], simple, test, file, Integer.parseInt(w[1]));
            }
        }
    }

    /**
     * Kommentare und Zeichenketten durch Leerzeichen ersetzen, Zeilenumbrüche bleiben (für die
     * Zeilennummern). Kennt {@code //}, {@code /* *&#47;}, {@code "…"}, {@code '…'} und {@code """…"""}.
     */
    static String stripCommentsAndStrings(String s) {
        char[] out = s.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n) {
            char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    blank(out, i++);
                }
                if (i < n) {
                    out[i++] = ' ';
                    out[i++] = ' ';
                }
            } else if (c == '"' && i + 2 < n && out[i + 1] == '"' && out[i + 2] == '"') {
                out[i++] = ' ';
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(out[i] == '"' && i + 2 < n && out[i + 1] == '"' && out[i + 2] == '"')) {
                    blank(out, i++);
                }
                for (int k = 0; k < 3 && i < n; k++) {
                    out[i++] = ' ';
                }
            } else if (c == '"' || c == '\'') {
                char q = c;
                out[i++] = ' ';
                while (i < n && out[i] != q && out[i] != '\n') {
                    if (out[i] == '\\' && i + 1 < n) {
                        out[i++] = ' ';
                    }
                    blank(out, i++);
                }
                if (i < n && out[i] == q) {
                    out[i++] = ' ';
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static void blank(char[] a, int i) {
        if (a[i] != '\n' && a[i] != '\r') {
            a[i] = ' ';
        }
    }

    private static int[] lineStarts(String s) {
        List<Integer> l = new ArrayList<Integer>();
        l.add(0);
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                l.add(i + 1);
            }
        }
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = l.get(i);
        }
        return a;
    }

    private static int line(int[] starts, int offset) {
        int i = Arrays.binarySearch(starts, offset);
        return (i >= 0 ? i : -i - 2) + 1;
    }
}
