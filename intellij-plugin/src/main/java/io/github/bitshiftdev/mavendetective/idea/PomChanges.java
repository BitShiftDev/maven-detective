package io.github.bitshiftdev.mavendetective.idea;

import io.github.bitshiftdev.mavendetective.core.deps.PomEditor;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * POM-Quick-Fixes im Speicher anwenden, ohne etwas zu schreiben – Grundlage für die Vorschau als Diff
 * („Erst zeigen, dann ändern“, UI-Plan U5) und für das Anwenden danach: Die Änderungen je Datei
 * werden in derselben Reihenfolge nacheinander zerlegt, wie sie später ins Dokument gehen.
 */
final class PomChanges {

    /**
     * Eine Textänderung: an {@code offset} werden {@code length} Zeichen durch {@code text} ersetzt;
     * {@code line} (ab 1) ist die Zeile im geänderten Text – zum Hinspringen.
     */
    record Edit(int offset, int length, @NotNull String text, int line) {

        static @NotNull Edit of(@NotNull PomEditor.Edit e) {
            return new Edit(e.offset, e.length, e.text, e.line);
        }

        @NotNull String apply(@NotNull String content) {
            return content.substring(0, offset) + text + content.substring(offset + length);
        }
    }

    /**
     * Änderungen an einer Datei.
     *
     * @param edits        in dieser Reihenfolge auf {@code before} anzuwenden (jede auf das Ergebnis der vorigen)
     * @param descriptions je gelungener Änderung ein Satz
     */
    record FileChange(@NotNull Path file, @NotNull String before, @NotNull String after, @NotNull List<Edit> edits,
                      @NotNull List<String> descriptions) {

        /** Eine einzelne Änderung an {@code before}. */
        static @NotNull FileChange single(@NotNull Path file, @NotNull String before, @NotNull Edit edit, @NotNull String description) {
            return new FileChange(file, before, edit.apply(before), List.of(edit), List.of(description));
        }

        /** Zeile (ab 1) der ersten Änderung im geänderten Text – zum Hinspringen. */
        int firstLine() {
            return edits.isEmpty() ? 0 : edits.get(0).line();
        }
    }

    /**
     * Geplante Änderungen.
     *
     * @param problems Fixes, die sich nicht anwenden lassen, je ein Satz mit Dateiname
     * @param failed   Datei des ersten gescheiterten Fixes (zum Öffnen), sonst {@code null}
     */
    record Plan(@NotNull List<FileChange> files, @NotNull List<String> problems, @Nullable Path failed) {

        boolean isEmpty() {
            return files.isEmpty();
        }

        int editCount() {
            int n = 0;
            for (FileChange f : files) {
                n += f.edits().size();
            }
            return n;
        }
    }

    private PomChanges() {
    }

    /**
     * Fixes je Datei nacheinander anwenden. Fixes ohne {@code file} oder anderer Art werden übergangen;
     * Dateien, deren Text {@code text} nicht liefert ({@code null}), landen bei den Problemen.
     *
     * @param text liefert den heutigen Inhalt (in der IDE: das Dokument, also auch Ungespeichertes)
     */
    static @NotNull Plan plan(@NotNull List<FixAction> fixes, @NotNull Function<Path, @Nullable String> text) {
        Map<Path, String> before = new LinkedHashMap<>();
        Map<Path, String> current = new LinkedHashMap<>();
        Map<Path, List<Edit>> edits = new LinkedHashMap<>();
        Map<Path, List<String>> descriptions = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        Path failed = null;
        for (FixAction fix : fixes) {
            String file = fix.param("file");
            if (file == null || !PomEditor.isPomFix(fix.getType())) {
                continue;
            }
            Path path = Paths.get(file);
            if (!current.containsKey(path)) {
                String t = text.apply(path);
                if (t == null) {
                    problems.add(MavenDetectiveBundle.message("preview.file.missing", file));
                    failed = failed == null ? path : failed;
                    current.put(path, null);
                    continue;
                }
                before.put(path, t);
                current.put(path, t);
            }
            String now = current.get(path);
            if (now == null) {
                continue;
            }
            try {
                PomEditor.Edit edit = PomEditor.forFix(now, fix);
                current.put(path, edit.apply(now));
                edits.computeIfAbsent(path, p -> new ArrayList<>()).add(Edit.of(edit));
                descriptions.computeIfAbsent(path, p -> new ArrayList<>()).add(edit.description);
            } catch (IllegalArgumentException e) {
                problems.add(path.getFileName() + ": " + e.getMessage());
                failed = failed == null ? path : failed;
            }
        }
        List<FileChange> files = new ArrayList<>();
        for (Map.Entry<Path, List<Edit>> e : edits.entrySet()) {
            Path p = e.getKey();
            files.add(new FileChange(p, before.get(p), current.get(p), e.getValue(), descriptions.get(p)));
        }
        return new Plan(files, problems, failed);
    }

    /** Vorschau vor jeder POM-Änderung (Standard: an; {@link DetectiveSettings}). */
    static boolean isPreviewEnabled() {
        return DetectiveSettings.getInstance().isPreviewPomFixes();
    }

    static void setPreviewEnabled(boolean enabled) {
        DetectiveSettings.getInstance().setPreviewPomFixes(enabled);
    }
}
