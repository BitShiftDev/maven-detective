package io.github.bitshiftdev.mavendetective.core.deps;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Methoden und Felder einer Klassendatei – gerade genug vom Format, um zu sagen, ob eine Version
 * einer Bibliothek die Methode aus einem {@code NoSuchMethodError} kennt. Liest nur Konstantenpool,
 * Felder und Methoden; Attribute werden übersprungen. Ohne Abhängigkeiten, Java 8.
 */
public final class ClassFile {

    private static final int MAX_CLASS_BYTES = 8 * 1024 * 1024;

    private final Set<String> methods;
    private final Set<String> fields;

    private ClassFile(Set<String> methods, Set<String> fields) {
        this.methods = methods;
        this.fields = fields;
    }

    /** Methoden als {@code name + deskriptor}, z. B. {@code findAndRegisterModules()Lcom/x/ObjectMapper;}. */
    public Set<String> getMethods() { return Collections.unmodifiableSet(methods); }

    /** Felder als {@code name + ":" + deskriptor}. */
    public Set<String> getFields() { return Collections.unmodifiableSet(fields); }

    /** Kennt die Klasse das Mitglied? Ohne Deskriptor zählt nur der Name. */
    public boolean has(Member m) {
        if (m.isField()) {
            for (String f : fields) {
                int colon = f.indexOf(':');
                if (f.substring(0, colon).equals(m.getName()) && (m.getDescriptor() == null || f.substring(colon + 1).equals(m.getDescriptor()))) {
                    return true;
                }
            }
            return false;
        }
        if (m.getDescriptor() != null) {
            return methods.contains(m.getName() + m.getDescriptor());
        }
        for (String s : methods) {
            if (s.startsWith(m.getName() + "(")) {
                return true;
            }
        }
        return false;
    }

    public static ClassFile parse(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("keine Klassendatei");
        }
        in.readUnsignedShort(); // minor
        in.readUnsignedShort(); // major
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1:
                    utf8[i] = in.readUTF();
                    break;
                case 3: case 4:
                    in.skipBytes(4);
                    break;
                case 5: case 6:
                    in.skipBytes(8);
                    i++; // belegt zwei Plätze
                    break;
                case 7: case 8: case 16: case 19: case 20:
                    in.skipBytes(2);
                    break;
                case 9: case 10: case 11: case 12: case 17: case 18:
                    in.skipBytes(4);
                    break;
                case 15:
                    in.skipBytes(3);
                    break;
                default:
                    throw new IOException("unbekannter Eintrag im Konstantenpool: " + tag);
            }
        }
        in.skipBytes(6); // access, this, super
        int interfaces = in.readUnsignedShort();
        in.skipBytes(2 * interfaces);
        Set<String> fields = new HashSet<String>();
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) {
            in.skipBytes(2);
            String name = utf8[in.readUnsignedShort()];
            String desc = utf8[in.readUnsignedShort()];
            fields.add(name + ":" + desc);
            skipAttributes(in);
        }
        Set<String> methods = new HashSet<String>();
        n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) {
            in.skipBytes(2);
            String name = utf8[in.readUnsignedShort()];
            String desc = utf8[in.readUnsignedShort()];
            methods.add(name + desc);
            skipAttributes(in);
        }
        return new ClassFile(methods, fields);
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) {
            in.skipBytes(2);
            long len = in.readInt() & 0xFFFFFFFFL;
            if (in.skipBytes((int) len) != len) {
                throw new IOException("Attribut abgeschnitten");
            }
        }
    }

    /**
     * Klasse aus einem JAR oder Klassenverzeichnis; {@code null}, wenn sie dort nicht liegt.
     *
     * @param className binärer Name, innere Klassen mit {@code $}
     */
    public static ClassFile read(Path jarOrDirectory, String className) throws IOException {
        String entry = className.replace('.', '/') + ".class";
        if (Files.isDirectory(jarOrDirectory)) {
            Path p = jarOrDirectory.resolve(entry);
            return Files.isRegularFile(p) && Files.size(p) <= MAX_CLASS_BYTES ? parse(Files.readAllBytes(p)) : null;
        }
        ZipFile zip = new ZipFile(jarOrDirectory.toFile());
        try {
            ZipEntry z = zip.getEntry(entry);
            if (z == null || z.getSize() > MAX_CLASS_BYTES) {
                return null;
            }
            InputStream is = zip.getInputStream(z);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > MAX_CLASS_BYTES) {
                        return null;   // Größe fehlte im ZIP-Verzeichnis (-1)
                    }
                }
                return parse(out.toByteArray());
            } finally {
                is.close();
            }
        } finally {
            zip.close();
        }
    }

    // ------------------------------------------------------------------ Mitglied aus der Fehlermeldung

    /** Methode oder Feld, das zur Laufzeit fehlte – aus der Meldung eines {@code NoSuchMethodError}/{@code NoSuchFieldError}. */
    public static final class Member {
        private final String owner;
        private final String name;
        private final String descriptor;
        private final boolean field;

        Member(String owner, String name, String descriptor, boolean field) {
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
            this.field = field;
        }

        /** Klasse, die das Mitglied haben sollte (binärer Name). */
        public String getOwner() { return owner; }
        public String getName() { return name; }
        /** JVM-Deskriptor oder {@code null}, wenn die Meldung ihn nicht hergibt. */
        public String getDescriptor() { return descriptor; }
        public boolean isField() { return field; }

        /** Für Texte: {@code ObjectMapper.findAndRegisterModules()} bzw. {@code Feld FOO}. */
        public String label() {
            String simple = owner.substring(owner.lastIndexOf('.') + 1);
            return simple + "." + name + (field ? "" : "()");
        }
    }

    /** JDK 11+: {@code 'void com.x.Y.m(java.lang.String, int[])'}. */
    private static final Pattern MODERN_METHOD = Pattern.compile("'?\\s*([\\w.$\\[\\]]+)\\s+([\\w.$]+)\\.([\\w$<>]+)\\(([^)]*)\\)'?");
    /** JDK 8: {@code com.x.Y.m(Ljava/lang/String;)V}. */
    private static final Pattern CLASSIC_METHOD = Pattern.compile("([\\w.$/]+)\\.([\\w$<>]+)(\\([^)\\s]*\\)\\S+)");
    /** JDK 17+: {@code Class com.x.Y does not have member field 'int FOO'}. */
    private static final Pattern MODERN_FIELD = Pattern.compile("Class ([\\w.$]+) does not have member field '([\\w.$\\[\\]]+) ([\\w$]+)'");

    /** Methode aus dem Text nach {@code NoSuchMethodError:}; {@code null}, wenn unlesbar. */
    public static Member method(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        Matcher m = MODERN_METHOD.matcher(t);
        if (t.startsWith("'") && m.find()) {
            StringBuilder desc = new StringBuilder("(");
            for (String p : m.group(4).split(",")) {
                if (!p.trim().isEmpty()) {
                    desc.append(descriptor(p.trim()));
                }
            }
            desc.append(')').append(descriptor(m.group(1)));
            return new Member(m.group(2), m.group(3), desc.toString(), false);
        }
        m = CLASSIC_METHOD.matcher(t);
        if (m.find()) {
            return new Member(m.group(1).replace('/', '.'), m.group(2), m.group(3), false);
        }
        return null;
    }

    /** Feld aus einer Meldung des JDK 17+; ältere JDKs nennen nur den Feldnamen (dann {@code null}). */
    public static Member field(String text) {
        Matcher m = text == null ? null : MODERN_FIELD.matcher(text);
        if (m == null || !m.find()) {
            return null;
        }
        return new Member(m.group(1), m.group(3), descriptor(m.group(2)), true);
    }

    /** Java-Typ wie in Meldungen ({@code int[]}, {@code java.lang.String}) als JVM-Deskriptor. */
    static String descriptor(String javaType) {
        String t = javaType.trim();
        StringBuilder out = new StringBuilder();
        while (t.endsWith("[]")) {
            out.append('[');
            t = t.substring(0, t.length() - 2).trim();
        }
        if ("void".equals(t)) {
            out.append('V');
        } else if ("boolean".equals(t)) {
            out.append('Z');
        } else if ("byte".equals(t)) {
            out.append('B');
        } else if ("char".equals(t)) {
            out.append('C');
        } else if ("short".equals(t)) {
            out.append('S');
        } else if ("int".equals(t)) {
            out.append('I');
        } else if ("long".equals(t)) {
            out.append('J');
        } else if ("float".equals(t)) {
            out.append('F');
        } else if ("double".equals(t)) {
            out.append('D');
        } else {
            out.append('L').append(t.replace('.', '/')).append(';');
        }
        return out.toString();
    }
}
