package sh.pcx.packmerger.datapack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static sh.pcx.packmerger.datapack.Fixtures.DATA_MCMETA;
import static sh.pcx.packmerger.datapack.Fixtures.RP_MCMETA;
import static sh.pcx.packmerger.datapack.Fixtures.entries;

class DatapackAssetExtractorTest {

    @TempDir Path tmp;

    private static final int FORMAT_26_2 = sh.pcx.packmerger.merge.PackFormatRegistry.forMinecraftVersion("26.2");

    private DatapackInfo inspect(Path p) {
        return new DatapackInspector(ZipLimits.DEFAULTS, tmp.resolve("t")).inspect(p, "file/" + p.getFileName(), true);
    }

    private DatapackAssetExtractor extractor() {
        return new DatapackAssetExtractor(ZipLimits.DEFAULTS, tmp.resolve("t"));
    }

    @Test
    void hybrid_outputHasNoData_andSynthesizedMcmeta() throws IOException {
        DatapackInfo info = inspect(Fixtures.zip(tmp.resolve("terralith.zip"), Fixtures.hybrid()));
        Path out = tmp.resolve("stage/dp-terralith.zip");
        DatapackAssetExtractor.Result r = extractor().extract(info, FORMAT_26_2, out);

        Map<String, String> files = Fixtures.read(out);
        assertTrue(files.keySet().stream().noneMatch(n -> n.startsWith("data/")), files.keySet().toString());
        assertTrue(files.containsKey("assets/terra/textures/block/rock.png"));
        assertTrue(files.containsKey("pack.png"));
        assertTrue(r.mcmetaSynthesized());

        JsonObject pack = JsonParser.parseString(files.get("pack.mcmeta")).getAsJsonObject().getAsJsonObject("pack");
        assertEquals(88, FORMAT_26_2);
        assertEquals(88, pack.get("pack_format").getAsInt());
        assertEquals(88, pack.get("min_format").getAsInt());
        assertEquals(88, pack.get("max_format").getAsInt());
        assertEquals("Assets from datapack terralith", pack.get("description").getAsString());
        assertEquals(4, r.files());
    }

    @Test
    void unknownMinecraftVersion_noMcmeta() throws IOException {
        DatapackInfo info = inspect(Fixtures.zip(tmp.resolve("h.zip"), Fixtures.hybrid()));
        Path out = tmp.resolve("o.zip");
        DatapackAssetExtractor.Result r = extractor().extract(info, -1, out);
        assertFalse(Fixtures.read(out).containsKey("pack.mcmeta"));
        assertFalse(r.mcmetaSynthesized());
    }

    @Test
    void subfolder_keepsOwnMcmetaAndOverlays() throws IOException {
        String mcmeta = "{\"pack\":{\"pack_format\":88,\"description\":\"rp\"},"
                + "\"overlays\":{\"entries\":[{\"directory\":\"ov_new\",\"formats\":[88,97]}]}}";
        DatapackInfo info = inspect(Fixtures.zip(tmp.resolve("sub.zip"), entries(
                "pack.mcmeta", DATA_MCMETA,
                "data/x/a.json", "{}",
                "resourcepack/pack.mcmeta", mcmeta,
                "resourcepack/assets/x/a.png", "PNG",
                "resourcepack/ov_new/assets/x/b.png", "PNG2",
                "resourcepack/ov_new/data/x/c.json", "{}",
                "resourcepack/notes.txt", "ignored")));
        Path out = tmp.resolve("o.zip");
        DatapackAssetExtractor.Result r = extractor().extract(info, FORMAT_26_2, out);
        Map<String, String> files = Fixtures.read(out);
        assertEquals(mcmeta, files.get("pack.mcmeta"));
        assertTrue(files.containsKey("assets/x/a.png"));
        assertTrue(files.containsKey("ov_new/assets/x/b.png"));
        assertFalse(files.containsKey("ov_new/data/x/c.json"));
        assertFalse(files.containsKey("notes.txt"));
        assertFalse(r.mcmetaSynthesized());
    }

    @Test
    void nestedZip_keepsItsMcmeta() throws IOException {
        byte[] rp = Fixtures.zipBytes(entries("Wrap/pack.mcmeta", RP_MCMETA, "Wrap/assets/i/a.png", "PNG",
                "Wrap/data/should/not.json", "{}"));
        DatapackInfo info = inspect(Fixtures.zip(tmp.resolve("n.zip"), entries(
                "pack.mcmeta", DATA_MCMETA, "data/i/a.json", "{}", "RP.zip", rp)));
        Path out = tmp.resolve("o.zip");
        extractor().extract(info, FORMAT_26_2, out);
        Map<String, String> files = Fixtures.read(out);
        assertEquals(RP_MCMETA, files.get("pack.mcmeta"));
        assertEquals("PNG", files.get("assets/i/a.png"));
        assertEquals(2, files.size());
    }

    @Test
    void junkFiles_stripped() throws IOException {
        DatapackInfo info = inspect(Fixtures.zip(tmp.resolve("j.zip"), entries(
                "pack.mcmeta", DATA_MCMETA, "data/a/b.json", "{}",
                "assets/a/ok.png", "PNG", "assets/a/.DS_Store", "junk", "assets/__MACOSX/x", "junk",
                "assets/a/Thumbs.db", "junk")));
        Path out = tmp.resolve("o.zip");
        extractor().extract(info, FORMAT_26_2, out);
        Map<String, String> files = Fixtures.read(out);
        assertEquals(java.util.Set.of("assets/a/ok.png", "pack.mcmeta"), files.keySet());
    }

    @Test
    void zipSlipEntries_neverReachOutput() throws IOException {
        DatapackInfo info = inspect(Fixtures.zip(tmp.resolve("slip.zip"), entries(
                "pack.mcmeta", DATA_MCMETA, "data/a/b.json", "{}",
                "assets/a/ok.png", "PNG", "assets/../../../../evil.png", "EVIL", "/assets/abs.png", "EVIL")));
        Path out = tmp.resolve("stage/o.zip");
        extractor().extract(info, FORMAT_26_2, out);
        Map<String, String> files = Fixtures.read(out);
        assertFalse(files.containsValue("EVIL"));
        assertFalse(Files.exists(tmp.resolve("evil.png")));
        assertFalse(Files.exists(tmp.getParent().resolve("evil.png")));
    }

    @Test
    void zipBomb_failsWithoutLeavingFiles() throws IOException {
        DatapackInfo info = inspect(Fixtures.zip(tmp.resolve("bomb.zip"), entries(
                "pack.mcmeta", DATA_MCMETA, "data/a/b.json", "{}",
                "assets/a/zeros.bin", new byte[4 * 1024 * 1024])));
        Path out = tmp.resolve("stage/o.zip");
        assertThrows(SafeZip.ZipLimitException.class, () -> extractor().extract(info, FORMAT_26_2, out));
        assertFalse(Files.exists(out));
        assertFalse(Files.exists(tmp.resolve("stage/o.zip.part")));
    }

    @Test
    void deterministic_twoRunsSameSha1() throws IOException {
        DatapackInfo info = inspect(Fixtures.zip(tmp.resolve("d.zip"), Fixtures.hybrid()));
        DatapackAssetExtractor.Result a = extractor().extract(info, FORMAT_26_2, tmp.resolve("a.zip"));
        DatapackAssetExtractor.Result b = extractor().extract(info, FORMAT_26_2, tmp.resolve("b.zip"));
        assertEquals(a.sha1(), b.sha1());
        assertArrayEquals(Files.readAllBytes(tmp.resolve("a.zip")), Files.readAllBytes(tmp.resolve("b.zip")));
        assertFalse(Files.exists(tmp.resolve("a.zip.part")));
    }

    @Test
    void directoryDatapack_extracted() throws IOException {
        DatapackInfo info = inspect(Fixtures.dir(tmp.resolve("folder"), Fixtures.hybrid()));
        Path out = tmp.resolve("o.zip");
        extractor().extract(info, FORMAT_26_2, out);
        Map<String, String> files = Fixtures.read(out);
        assertTrue(files.containsKey("assets/terra/lang/en_us.json"));
        assertFalse(files.keySet().stream().anyMatch(n -> n.startsWith("data/")));
    }

    @Test
    void dataOnly_isRejected() throws IOException {
        DatapackInfo info = inspect(Fixtures.zip(tmp.resolve("d.zip"),
                entries("pack.mcmeta", DATA_MCMETA, "data/a/b.json", "{}")));
        assertThrows(IOException.class, () -> extractor().extract(info, FORMAT_26_2, tmp.resolve("o.zip")));
    }
}
