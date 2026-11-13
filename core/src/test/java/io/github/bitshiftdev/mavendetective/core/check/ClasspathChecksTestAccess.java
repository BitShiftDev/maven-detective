package io.github.bitshiftdev.mavendetective.core.check;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Öffnet den Test-Compiler aus {@link ClasspathChecksTest} für Tests anderer Pakete. */
public final class ClasspathChecksTestAccess {

    private ClasspathChecksTestAccess() {
    }

    public static Map<String, byte[]> compile(Path work, String... sources) throws IOException {
        return ClasspathChecksTest.compile(work, sources);
    }
}
