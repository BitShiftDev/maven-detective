package io.github.bitshiftdev.mavendetective.core.io;

import io.github.bitshiftdev.mavendetective.core.Texts;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Eine Textdatei des Nutzers, die in derselben Kodierung zurückgeschrieben wird, in der sie gelesen
 * wurde – samt Byte Order Mark. Grundlage aller Quick-Fixes, die Dateien ändern (POM, settings.xml,
 * {@code .mvn/jvm.config}).
 *
 * <p>Kodierung: BOM vor allem anderen, dann bei XML die Angabe im Prolog
 * ({@code <?xml … encoding="ISO-8859-1"?>}), sonst UTF-8. Ohne BOM und Prolog wird nur dann UTF-8
 * angenommen, wenn die Bytes gültiges UTF-8 sind; andernfalls ISO-8859-1, das jedes Byte unverändert
 * durchreicht – so geht auch in einer Datei unbekannter Kodierung kein Zeichen verloren.
 *
 * <p>Wird eine Kodierung ausdrücklich genannt (BOM, Prolog) und passen die Bytes nicht dazu, bricht
 * {@link #read} ab, statt Ersatzzeichen zu erzeugen: Was nicht gelesen werden kann, wird auch nicht
 * geschrieben.
 */
public final class TextFile {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] UTF16BE_BOM = {(byte) 0xFE, (byte) 0xFF};
    private static final byte[] UTF16LE_BOM = {(byte) 0xFF, (byte) 0xFE};
    private static final Pattern PROLOG_ENCODING =
            Pattern.compile("^<\\?xml[^>]*?\\sencoding\\s*=\\s*[\"']([A-Za-z0-9._:-]+)[\"']");

    private final Path path;
    private final Charset charset;
    private final byte[] bom;
    private final String text;

    private TextFile(Path path, Charset charset, byte[] bom, String text) {
        this.path = path;
        this.charset = charset;
        this.bom = bom;
        this.text = text;
    }

    /** Liest die Datei; Kodierung wie in der Klassenbeschreibung. */
    public static TextFile read(Path path) throws IOException {
        return decode(path, Files.readAllBytes(path));
    }

    /** Eine neue, noch leere Datei – geschrieben wird sie als UTF-8 ohne BOM. */
    public static TextFile create(Path path) {
        return new TextFile(path, StandardCharsets.UTF_8, new byte[0], "");
    }

    /** Liest die Datei, wenn es sie gibt; sonst wie {@link #create}. */
    public static TextFile readOrCreate(Path path) throws IOException {
        return Files.isRegularFile(path) ? read(path) : create(path);
    }

    static TextFile decode(Path path, byte[] bytes) throws IOException {
        if (startsWith(bytes, UTF8_BOM)) {
            return strict(path, bytes, UTF8_BOM, StandardCharsets.UTF_8);
        }
        if (startsWith(bytes, UTF16BE_BOM)) {
            return strict(path, bytes, UTF16BE_BOM, StandardCharsets.UTF_16BE);
        }
        if (startsWith(bytes, UTF16LE_BOM)) {
            return strict(path, bytes, UTF16LE_BOM, StandardCharsets.UTF_16LE);
        }
        // UTF-16 ohne BOM: "<?" als 16-Bit-Zeichen
        if (startsWith(bytes, new byte[] {0, '<', 0, '?'})) {
            return strict(path, bytes, new byte[0], StandardCharsets.UTF_16BE);
        }
        if (startsWith(bytes, new byte[] {'<', 0, '?', 0})) {
            return strict(path, bytes, new byte[0], StandardCharsets.UTF_16LE);
        }
        String declared = declaredEncoding(bytes);
        if (declared != null) {
            Charset cs;
            try {
                cs = Charset.forName(declared);
            } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                throw new IOException(Texts.t("file.encoding.unknown", path, declared), e);
            }
            return strict(path, bytes, new byte[0], cs);
        }
        try {
            return new TextFile(path, StandardCharsets.UTF_8, new byte[0], decoder(StandardCharsets.UTF_8).decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException e) {
            return new TextFile(path, StandardCharsets.ISO_8859_1, new byte[0], new String(bytes, StandardCharsets.ISO_8859_1));
        }
    }

    /** Kodierung aus dem XML-Prolog; nur in ASCII-kompatiblen Dateien lesbar, sonst {@code null}. */
    private static String declaredEncoding(byte[] bytes) {
        int n = Math.min(bytes.length, 200);
        StringBuilder head = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            int b = bytes[i] & 0xFF;
            if (b == '>' || b >= 0x80) {
                break;
            }
            head.append((char) b);
        }
        Matcher m = PROLOG_ENCODING.matcher(head.append('>'));
        return m.find() ? m.group(1) : null;
    }

    private static TextFile strict(Path path, byte[] bytes, byte[] bom, Charset cs) throws IOException {
        try {
            String text = decoder(cs).decode(ByteBuffer.wrap(bytes, bom.length, bytes.length - bom.length)).toString();
            return new TextFile(path, cs, bom, text);
        } catch (CharacterCodingException e) {
            throw new IOException(Texts.t("file.encoding.invalid", path, cs.name()), e);
        }
    }

    private static CharsetDecoder decoder(Charset cs) {
        return cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(Arrays.copyOf(bytes, prefix.length), prefix);
    }

    public Path getPath() {
        return path;
    }

    /** Inhalt ohne BOM. */
    public String getText() {
        return text;
    }

    public Charset getCharset() {
        return charset;
    }

    /**
     * Schreibt {@code content} in der gelesenen Kodierung (mit derselben BOM). Lässt sich ein Zeichen
     * darin nicht ausdrücken, bleibt die Datei unverändert.
     */
    public void write(String content) throws IOException {
        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer encoded;
        try {
            encoded = encoder.encode(CharBuffer.wrap(content));
        } catch (CharacterCodingException e) {
            throw new IOException(Texts.t("file.encoding.unwritable", path, charset.name()), e);
        }
        byte[] out = new byte[bom.length + encoded.remaining()];
        System.arraycopy(bom, 0, out, 0, bom.length);
        encoded.get(out, bom.length, encoded.remaining());
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, out);
    }
}
