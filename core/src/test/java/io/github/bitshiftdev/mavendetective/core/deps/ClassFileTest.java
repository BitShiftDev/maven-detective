package io.github.bitshiftdev.mavendetective.core.deps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassFileTest {

    @Test
    void modernMethodMessage() {
        ClassFile.Member m = ClassFile.method("'com.fasterxml.jackson.databind.ObjectMapper com.fasterxml.jackson.databind.ObjectMapper.findAndRegisterModules()'");
        assertEquals("com.fasterxml.jackson.databind.ObjectMapper", m.getOwner());
        assertEquals("findAndRegisterModules", m.getName());
        assertEquals("()Lcom/fasterxml/jackson/databind/ObjectMapper;", m.getDescriptor());
        assertEquals("ObjectMapper.findAndRegisterModules()", m.label());
    }

    @Test
    void modernMethodWithArraysAndPrimitives() {
        ClassFile.Member m = ClassFile.method("'void org.x.Util$Inner.<init>(java.lang.String[], int, long)'");
        assertEquals("org.x.Util$Inner", m.getOwner());
        assertEquals("<init>", m.getName());
        assertEquals("([Ljava/lang/String;IJ)V", m.getDescriptor());
    }

    @Test
    void classicMethodMessage() {
        ClassFile.Member m = ClassFile.method("org.x.Util.newMethod(Ljava/lang/String;)Ljava/util/List;");
        assertEquals("org.x.Util", m.getOwner());
        assertEquals("newMethod", m.getName());
        assertEquals("(Ljava/lang/String;)Ljava/util/List;", m.getDescriptor());
        assertEquals("org.x.Util", ClassFile.method("org/x/Util.old()V").getOwner());
    }

    @Test
    void fieldMessage() {
        ClassFile.Member f = ClassFile.field("Class org.x.Config does not have member field 'int TIMEOUT'");
        assertEquals("org.x.Config", f.getOwner());
        assertEquals("TIMEOUT", f.getName());
        assertEquals("I", f.getDescriptor());
        assertTrue(f.isField());
        assertNull(ClassFile.field("TIMEOUT"));
        assertNull(ClassFile.method("garbage"));
    }

    @Test
    void parsesMembersOfARealClass() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("md-classfile");
        java.util.Map<String, byte[]> classes = io.github.bitshiftdev.mavendetective.core.check.ClasspathChecksTestAccess.compile(dir,
                "package org.x; public class Util { public static final int TIMEOUT = 1; long count; "
                        + "public static java.util.List<String> names(String a, int[] b) { return null; } }");
        ClassFile c = ClassFile.parse(classes.get("org/x/Util.class"));
        assertTrue(c.getMethods().contains("names(Ljava/lang/String;[I)Ljava/util/List;"), c.getMethods().toString());
        assertTrue(c.getFields().contains("count:J"));
        assertTrue(c.has(ClassFile.field("Class org.x.Util does not have member field 'int TIMEOUT'")));
        assertTrue(c.has(ClassFile.method("'java.util.List org.x.Util.names(java.lang.String, int[])'")));
        assertFalse(c.has(ClassFile.method("'java.util.List org.x.Util.names(java.lang.String)'")));
    }
}
