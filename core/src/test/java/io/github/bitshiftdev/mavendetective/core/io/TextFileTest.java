package io.github.bitshiftdev.mavendetective.core.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextFileTest {

    private static final String POM = "<project><name>Größe</name></project>";
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @TempDir
    Path dir;

    @Test
    void plainUtf8IsReadAndWrittenUnchanged() throws IOException {
        Path file = write(POM.getBytes(StandardCharsets.UTF_8));

        TextFile t = TextFile.read(file);

        assertEquals(StandardCharsets.UTF_8, t.getCharset());
        assertEquals(POM, t.getText());
        assertRoundTrip(file, t);
    }

    @Test
    void utf8WithBomKeepsTheBom() throws IOException {
        Path file = write(concat(UTF8_BOM, POM.getBytes(StandardCharsets.UTF_8)));

        TextFile t = TextFile.read(file);

        assertEquals(POM, t.getText());
        assertRoundTrip(file, t);
    }

    @Test
    void utf16WithAndWithoutBomIsRecognised() throws IOException {
        String xml = "<?xml version=\"1.0\"?>" + POM;
        Path be = write(concat(new byte[] {(byte) 0xFE, (byte) 0xFF}, xml.getBytes(StandardCharsets.UTF_16BE)));
        Path le = write(concat(new byte[] {(byte) 0xFF, (byte) 0xFE}, xml.getBytes(StandardCharsets.UTF_16LE)));
        Path noBom = write(xml.getBytes(StandardCharsets.UTF_16LE));

        assertEquals(xml, TextFile.read(be).getText());
        assertEquals(xml, TextFile.read(le).getText());
        assertEquals(StandardCharsets.UTF_16LE, TextFile.read(noBom).getCharset());
        assertRoundTrip(be, TextFile.read(be));
        assertRoundTrip(noBom, TextFile.read(noBom));
    }

    @Test
    void prologEncodingWins() throws IOException {
        String xml = "<?xml version='1.0' encoding='ISO-8859-1'?>" + POM;
        Path file = write(xml.getBytes(StandardCharsets.ISO_8859_1));

        TextFile t = TextFile.read(file);

        assertEquals(StandardCharsets.ISO_8859_1, t.getCharset());
        assertEquals(xml, t.getText());
        assertRoundTrip(file, t);
    }

    @Test
    void latin1WithoutPrologFallsBackWithoutLosingBytes() throws IOException {
        byte[] bytes = POM.getBytes(StandardCharsets.ISO_8859_1);
        Path file = write(bytes);

        TextFile t = TextFile.read(file);

        assertEquals(StandardCharsets.ISO_8859_1, t.getCharset());
        assertEquals(POM, t.getText());
        assertFalse(t.getText().contains("�"));
        assertRoundTrip(file, t);
    }

    @Test
    void declaredUtf8WithInvalidBytesIsRefused() throws IOException {
        byte[] bytes = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + POM).getBytes(StandardCharsets.ISO_8859_1);
        Path file = write(bytes);

        IOException e = assertThrows(IOException.class, () -> TextFile.read(file));
        assertTrue(e.getMessage().contains("UTF-8"), e.getMessage());
    }

    @Test
    void unknownDeclaredEncodingIsRefused() throws IOException {
        Path file = write("<?xml version=\"1.0\" encoding=\"KLINGONISCH-9\"?><project/>".getBytes(StandardCharsets.US_ASCII));

        IOException e = assertThrows(IOException.class, () -> TextFile.read(file));
        assertTrue(e.getMessage().contains("KLINGONISCH-9"), e.getMessage());
    }

    @Test
    void characterThatTheEncodingCannotExpressLeavesTheFileUntouched() throws IOException {
        byte[] original = ("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + POM).getBytes(StandardCharsets.ISO_8859_1);
        Path file = write(original);
        TextFile t = TextFile.read(file);

        assertThrows(IOException.class, () -> t.write(t.getText().replace("Größe", "Preis €")));

        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void newFileIsWrittenAsUtf8WithoutBomAndCreatesDirectories() throws IOException {
        Path file = dir.resolve(".mvn/jvm.config");

        TextFile.readOrCreate(file).write("-Xmx2g ä");

        assertArrayEquals("-Xmx2g ä".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
        assertEquals("-Xmx2g ä", TextFile.readOrCreate(file).getText());
    }

    @Test
    void emptyFileIsUtf8() throws IOException {
        TextFile t = TextFile.read(write(new byte[0]));

        assertEquals("", t.getText());
        assertEquals(StandardCharsets.UTF_8, t.getCharset());
    }

    @Test
    void prologAfterTheFirst200BytesIsIgnored() throws IOException {
        char[] padding = new char[250];
        Arrays.fill(padding, ' ');
        byte[] bytes = (new String(padding) + "<?xml version='1.0' encoding='ISO-8859-1'?><a>ä</a>").getBytes(StandardCharsets.UTF_8);

        assertEquals(StandardCharsets.UTF_8, TextFile.read(write(bytes)).getCharset());
    }

    // --- Hilfen ----------------------------------------------------------------------------------

    /** Unverändert zurückgeschrieben ergibt sich Byte für Byte die gelesene Datei. */
    private static void assertRoundTrip(Path file, TextFile t) throws IOException {
        byte[] before = Files.readAllBytes(file);
        t.write(t.getText());
        assertArrayEquals(before, Files.readAllBytes(file), "Kodierung " + t.getCharset());
    }

    private Path write(byte[] bytes) throws IOException {
        Path file = Files.createTempFile(dir, "pom", ".xml");
        Files.write(file, bytes);
        return file;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
