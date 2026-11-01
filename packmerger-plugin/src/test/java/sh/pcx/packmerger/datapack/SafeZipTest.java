package sh.pcx.packmerger.datapack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static sh.pcx.packmerger.datapack.Fixtures.entries;

class SafeZipTest {

    @TempDir Path tmp;

    private static final ZipLimits SMALL = new ZipLimits(10 * ZipLimits.MIB, 3 * ZipLimits.MIB,
            2 * ZipLimits.MIB, 100, 100);

    @Test
    void safeName_rejectsTraversalAndAbsolutePaths() {
        assertNull(SafeZip.safeName("../evil.txt"));
        assertNull(SafeZip.safeName("/abs/file"));
        assertNull(SafeZip.safeName("C:\\x"));
        assertNull(SafeZip.safeName("C:/x"));
        assertNull(SafeZip.safeName("a/../../b"));
        assertNull(SafeZip.safeName("a\\..\\..\\b"));
        assertNull(SafeZip.safeName("assets/x\0.png"));
        assertNull(SafeZip.safeName("\\\\server\\share"));
        assertNull(SafeZip.safeName(""));
        assertNull(SafeZip.safeName("./"));
        assertNull(SafeZip.safeName(null));
    }

    @Test
    void safeName_normalisesHarmlessNames() {
        assertEquals("assets/a/b.png", SafeZip.safeName("assets\\a\\b.png"));
        assertEquals("assets/a/b.png", SafeZip.safeName("./assets//a/./b.png"));
        assertEquals("pack.mcmeta", SafeZip.safeName("pack.mcmeta"));
        assertEquals("a..b/c", SafeZip.safeName("a..b/c"));
    }

    @Test
    void zipTree_skipsUnsafeEntries() throws IOException {
        Path z = Fixtures.zip(tmp.resolve("slip.zip"), entries(
                "../../evil.txt", "x", "/etc/passwd", "x", "assets/ok/a.txt", "fine"));
        try (SafeZip.Tree t = SafeZip.open(z, ZipLimits.DEFAULTS)) {
            assertEquals(List.of("assets/ok/a.txt"), t.names());
            assertEquals(2, t.skipped());
        }
    }

    @Test
    void zipTree_skipsSymlinkEntries() throws IOException {
        Path z = Fixtures.zipWithSymlink(tmp.resolve("link.zip"), "assets/x/secret.txt", "/etc/shadow",
                entries("assets/x/ok.txt", "ok"));
        try (SafeZip.Tree t = SafeZip.open(z, ZipLimits.DEFAULTS)) {
            assertEquals(List.of("assets/x/ok.txt"), t.names());
            assertEquals(1, t.skipped());
        }
    }

    @Test
    void entryOverLimit_rejectedWhileReading() throws IOException {
        Path z = Fixtures.zip(tmp.resolve("big.zip"), entries("assets/big.bin", randomBytes(3 * 1024 * 1024)));
        assertThrows(SafeZip.ZipLimitException.class, () -> readAll(z, SMALL));
    }

    @Test
    void lyingHeader_cannotBypassEntryLimit() throws IOException {
        Path z = Fixtures.zip(tmp.resolve("liar.zip"), entries("assets/big.bin", randomBytes(3 * 1024 * 1024)));
        patchUncompressedSizes(z, 10);
        // The headers claim 10 bytes; the bytes actually inflated are counted, so the read fails.
        assertThrows(IOException.class, () -> readAll(z, SMALL));
    }

    @Test
    void totalOverLimit_rejected() throws IOException {
        Path z = Fixtures.zip(tmp.resolve("total.zip"), entries(
                "assets/a.bin", randomBytes(1500 * 1024),
                "assets/b.bin", randomBytes(1500 * 1024),
                "assets/c.bin", randomBytes(1500 * 1024)));
        SafeZip.ZipLimitException e = assertThrows(SafeZip.ZipLimitException.class, () -> readAll(z, SMALL));
        assertTrue(e.getMessage().contains("uncompressed"), e.getMessage());
    }

    @Test
    void highCompressionRatio_rejectedAsZipBomb() throws IOException {
        // 2 MiB of zeros deflates to a few KiB (about 1000:1).
        ZipLimits limits = new ZipLimits(ZipLimits.MIB * 10, ZipLimits.MIB * 100, ZipLimits.MIB * 100, 100, 100);
        Path z = Fixtures.zip(tmp.resolve("bomb.zip"), entries("assets/zeros.bin", new byte[2 * 1024 * 1024]));
        SafeZip.ZipLimitException e = assertThrows(SafeZip.ZipLimitException.class, () -> readAll(z, limits));
        assertTrue(e.getMessage().contains("zip bomb"), e.getMessage());
    }

    @Test
    void smallHighlyCompressibleEntry_isFine() throws IOException {
        Path z = Fixtures.zip(tmp.resolve("ok.zip"), entries("assets/zeros.bin", new byte[512 * 1024]));
        assertEquals(1, readAll(z, ZipLimits.DEFAULTS));
    }

    @Test
    void tooManyEntries_rejectedOnOpen() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < 101; i++) m.put("assets/f" + i + ".txt", "x");
        Path z = Fixtures.zip(tmp.resolve("many.zip"), m);
        assertThrows(SafeZip.ZipLimitException.class, () -> SafeZip.open(z, SMALL).close());
    }

    @Test
    void fileOverLimit_rejectedOnOpen() throws IOException {
        ZipLimits tiny = new ZipLimits(100, ZipLimits.MIB, ZipLimits.MIB, 100, 100);
        Path z = Fixtures.zip(tmp.resolve("f.zip"), entries("assets/a.bin", randomBytes(4096)));
        assertThrows(SafeZip.ZipLimitException.class, () -> SafeZip.open(z, tiny).close());
    }

    @Test
    void truncatedZip_throwsIOException() throws IOException {
        byte[] full = Fixtures.zipBytes(entries("assets/a.txt", "hello", "data/x.json", "{}"));
        Path z = tmp.resolve("half.zip");
        Files.write(z, Arrays.copyOf(full, full.length / 2));
        assertThrows(IOException.class, () -> SafeZip.open(z, ZipLimits.DEFAULTS).close());
    }

    @Test
    void dirTree_skipsSymlinks() throws IOException {
        Path d = Fixtures.dir(tmp.resolve("pack"), entries("assets/a.txt", "a", "pack.mcmeta", "{}"));
        Path outside = Files.writeString(tmp.resolve("secret.txt"), "secret");
        boolean linked;
        try {
            Files.createSymbolicLink(d.resolve("assets/link.txt"), outside);
            linked = true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            linked = false; // Windows without developer mode cannot create symlinks
        }
        try (SafeZip.Tree t = SafeZip.open(d, ZipLimits.DEFAULTS)) {
            assertEquals(List.of("assets/a.txt", "pack.mcmeta"), t.names());
            assertEquals(linked ? 1 : 0, t.skipped());
        }
    }

    @Test
    void dirTree_tooManyFiles_rejected() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < 120; i++) m.put("assets/f" + i + ".txt", "x");
        Path d = Fixtures.dir(tmp.resolve("many"), m);
        assertThrows(SafeZip.ZipLimitException.class, () -> SafeZip.open(d, SMALL).close());
    }

    @Test
    void forEach_stripsPrefix() throws IOException {
        Path z = Fixtures.zip(tmp.resolve("p.zip"),
                entries("Wrap/assets/a.txt", "a", "Wrap/pack.mcmeta", "{}", "x.txt", "y"));
        List<String> seen = new ArrayList<>();
        SafeZip.forEach(z, "Wrap/", ZipLimits.DEFAULTS, (name, in) -> seen.add(name));
        assertEquals(List.of("assets/a.txt", "pack.mcmeta"), seen);
    }

    @Test
    void wrapperPrefix_unwrapsSingleFolderOnly() {
        List<String> markers = DatapackInspector.PACK_MARKERS;
        assertEquals("W/", SafeZip.wrapperPrefix(List.of("W/data/a", "W/pack.mcmeta", "readme.txt"), markers));
        assertEquals("", SafeZip.wrapperPrefix(List.of("W/data/a", "V/data/b"), markers));
        assertEquals("", SafeZip.wrapperPrefix(List.of("pack.mcmeta", "W/data/a"), markers));
        assertEquals("W/", SafeZip.wrapperPrefix(List.of("W/assets/a", "__MACOSX/W/._a"), markers));
    }

    // ---------------------------------------------------------------- helpers

    private static int readAll(Path z, ZipLimits limits) throws IOException {
        int n = 0;
        try (SafeZip.Tree t = SafeZip.open(z, limits)) {
            for (String name : t.names()) {
                try (InputStream in = t.open(name)) {
                    in.transferTo(OutputStream.nullOutputStream());
                }
                n++;
            }
        }
        return n;
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new Random(42).nextBytes(b);
        return b;
    }

    /** Rewrites the uncompressed-size field of every local header, central header and data descriptor. */
    private static void patchUncompressedSizes(Path zip, int size) throws IOException {
        byte[] b = Files.readAllBytes(zip);
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i + 30 < b.length; i++) {
            int sig = buf.getInt(i);
            if (sig == 0x04034b50) {
                buf.putInt(i + 22, size);
            } else if (sig == 0x02014b50) {
                buf.putInt(i + 24, size);
            } else if (sig == 0x08074b50) {
                buf.putInt(i + 12, size);
            }
        }
        Files.write(zip, b);
    }
}
