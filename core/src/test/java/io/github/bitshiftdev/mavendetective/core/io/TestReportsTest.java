package io.github.bitshiftdev.mavendetective.core.io;

import io.github.bitshiftdev.mavendetective.core.BuildDoctor;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.env.Environment;
import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Surefire-/Failsafe-Berichte als Quelle für die Extension (Testausgabe steht sonst nur in der Konsole). */
class TestReportsTest {

    private static final String NSME = "java.lang.NoSuchMethodError: 'com.fasterxml.jackson.databind.node.ObjectNode "
            + "com.fasterxml.jackson.databind.node.ObjectNode.withObjectProperty(java.lang.String)'";

    private static String report(String testClass, String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<testsuite name=\"" + testClass + "\" tests=\"2\" errors=\"1\" failures=\"0\">\n"
                + "  <properties><property name=\"java.version\" value=\"21\"/></properties>\n"
                + "  <testcase name=\"passes\" classname=\"" + testClass + "\" time=\"0.001\"/>\n"
                + body
                + "</testsuite>\n";
    }

    private static final String ERROR_CASE =
            "  <testcase name=\"routes\" classname=\"com.acme.GatewayTest\" time=\"0.081\">\n"
            + "    <error message=\"'com.fasterxml...'\" type=\"java.lang.NoSuchMethodError\"><![CDATA[" + NSME + "\n"
            + "\tat com.acme.OrderJson.newOrder(OrderJson.java:22)\n"
            + "\tat com.acme.Gateway.accept(Gateway.java:19)\n"
            + "\tat f3(X.java:1)\n\tat f4(X.java:1)\n\tat f5(X.java:1)\n\tat f6(X.java:1)\n\tat f7(X.java:1)\n\tat f8(X.java:1)\n"
            + "Caused by: java.lang.IllegalStateException: inner\n"
            + "\tat g1(Y.java:1)\n"
            + "\t... 12 more\n"
            + "]]></error>\n"
            + "    <system-out><![CDATA[noise]]></system-out>\n"
            + "  </testcase>\n";

    @Test
    void readsFailedTestsWithExceptionFramesAndCauses(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("TEST-com.acme.GatewayTest.xml"), report("com.acme.GatewayTest", ERROR_CASE).getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("com.acme.GatewayTest.txt"), "ignored".getBytes(StandardCharsets.UTF_8));

        List<String> failures = TestReports.failures(dir, 0);

        assertEquals(1, failures.size());
        String[] lines = failures.get(0).split("\n");
        assertEquals("com.acme.GatewayTest.routes <<< ERROR!", lines[0]);
        assertEquals(NSME, lines[1]);
        assertEquals("\tat com.acme.OrderJson.newOrder(OrderJson.java:22)", lines[2]);
        assertEquals(1 + 1 + TestReports.MAX_FRAMES + 1 + 1, lines.length, failures.get(0));
        assertEquals("Caused by: java.lang.IllegalStateException: inner", lines[lines.length - 2]);
        assertFalse(failures.get(0).contains("noise"));
        assertFalse(failures.get(0).contains("more"));
    }

    @Test
    void ignoresReportsOfEarlierRunsAndBrokenFiles(@TempDir Path dir) throws Exception {
        Path old = dir.resolve("TEST-a.OldTest.xml");
        Files.write(old, report("a.OldTest", ERROR_CASE).getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000L));
        Files.write(dir.resolve("TEST-b.Broken.xml"), "<testsuite><testcase name=\"x\"><error>".getBytes(StandardCharsets.UTF_8));

        assertTrue(TestReports.failures(dir, System.currentTimeMillis() - 60_000L).isEmpty());
        assertEquals(1, TestReports.failures(dir, 0).size());
        assertTrue(TestReports.failures(dir.resolve("missing"), 0).isEmpty());
    }

    @Test
    void withoutStackTraceTypeAndMessageAreUsed() {
        String block = TestReports.block("a.T", "m", "FAILURE", "org.opentest4j.AssertionFailedError", "expected: <1> but was: <2>", "  ");
        assertEquals("a.T.m <<< FAILURE!\norg.opentest4j.AssertionFailedError: expected: <1> but was: <2>", block);
    }

    /** Ohne das Event sah die Extension nur „There are test failures“ und meldete einen unbekannten Fehler. */
    @Test
    void testFailuresEventLetsTheRuntimeConflictRuleMatch(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("TEST-com.acme.GatewayTest.xml"), report("com.acme.GatewayTest", ERROR_CASE).getBytes(StandardCharsets.UTF_8));
        String text = TestReports.failures(dir, 0).get(0);
        Map<String, Object> mojo = new java.util.LinkedHashMap<String, Object>();
        mojo.put("type", EventLog.TYPE_MOJO_FAILED);
        mojo.put("project", "com.acme:gateway:1.0");
        mojo.put("mojo", "org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test");
        mojo.put("chain", java.util.Collections.singletonList(java.util.Collections.singletonMap("message",
                "Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test (default-test) on project gateway: "
                        + "There are test failures.")));
        Map<String, Object> tests = new java.util.LinkedHashMap<String, Object>();
        tests.put("type", EventLog.TYPE_TEST_FAILURES);
        tests.put("project", "com.acme:gateway:1.0");
        tests.put("text", text);

        BuildFailure f = EventLog.read(new StringReader(Json.write(mojo) + "\n" + Json.write(tests) + "\n"));
        assertEquals(2, f.getErrors().size());
        assertFalse(f.getErrors().get(0).isTestOutput());
        assertTrue(f.getErrors().get(1).isTestOutput());

        List<Diagnosis> d = BuildDoctor.withDefaults().diagnose(f, Environment.forBuild(f, null));
        assertEquals("runtime-class-conflict", d.get(0).getRuleId());
    }
}
