package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.execution.filters.Filter;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BuildFailureConsoleFilterTest {

    @Test
    void mavenFehlerzeileWirdErkannt() {
        assertArrayEquals(new int[] {7, 20}, BuildFailureConsoleFilter.failureRange("[INFO] BUILD FAILURE"));
        assertArrayEquals(new int[] {7, 20}, BuildFailureConsoleFilter.failureRange("[INFO] BUILD FAILURE\n"));
        assertArrayEquals(new int[] {8, 21}, BuildFailureConsoleFilter.failureRange("[ERROR] BUILD FAILURE  \r\n"));
    }

    @Test
    void andereZeilenBleibenUnberuehrt() {
        assertNull(BuildFailureConsoleFilter.failureRange("[INFO] BUILD SUCCESS"));
        assertNull(BuildFailureConsoleFilter.failureRange("BUILD FAILURE"));
        assertNull(BuildFailureConsoleFilter.failureRange("> Task :compileJava BUILD FAILURE in 2s"));
        assertNull(BuildFailureConsoleFilter.failureRange("[INFO] ------------------------------------------------------------------------"));
        assertNull(BuildFailureConsoleFilter.failureRange("[ERROR] Failed to execute goal … BUILD FAILURE"));
    }

    @Test
    void linkLiegtAufDemTextImGesamtdokument() {
        Project project = (Project) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Project.class},
                (p, m, a) -> null);
        String line = "[INFO] BUILD FAILURE\n";
        Filter.Result r = new BuildFailureConsoleFilter(project).applyFilter(line, 500);
        assertNotNull(r);
        Filter.ResultItem item = r.getResultItems().get(0);
        assertEquals(500 - line.length() + 7, item.getHighlightStartOffset());
        assertEquals(500 - line.length() + 20, item.getHighlightEndOffset());
        assertNotNull(item.getHyperlinkInfo());
        assertNull(new BuildFailureConsoleFilter(project).applyFilter("[INFO] BUILD SUCCESS\n", 50));
    }
}
