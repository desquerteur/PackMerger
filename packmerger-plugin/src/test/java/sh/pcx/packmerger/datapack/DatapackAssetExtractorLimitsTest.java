package sh.pcx.packmerger.datapack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static sh.pcx.packmerger.datapack.Fixtures.DATA_MCMETA;
import static sh.pcx.packmerger.datapack.Fixtures.entries;

/** Memory bounds and mcmeta sanitising of {@link DatapackAssetExtractor} (security review H1, M3). */
class DatapackAssetExtractorLimitsTest {

    @TempDir Path tmp;

    private static final int FORMAT = sh.pcx.packmerger.merge.PackFormatRegistry.forMinecraftVersion("26.2");

    private DatapackInfo inspect(Path p) {
        return new DatapackInspector(ZipLimits.DEFAULTS, tmp.resolve("t")).inspect(p, "file/" + p.getFileName(), true);
    }

    private DatapackAssetExtractor extractor() {
        return new DatapackAssetExtractor(ZipLimits.DEFAULTS, tmp.resolve("t"));
    }

    /** A JSON string of {@code size} bytes that does not compress well (stays under the ratio limit). */
    private static String bigJson(int size) {
        Random r = new Random(42);
        StringBuilder sb = new StringBuilder(size + 16).append("{\"k\":\"");
        while (sb.length() < size) sb.append((char) ('a' + r.nextInt(26)));
        return sb.append("\"}").toString();
    }

    @Test
    void oversizedJson_isSkipped_restIsStaged() throws IOException {
        String big = bigJson((int) DatapackAssetExtractor.MAX_JSON_FILE_BYTES + 1024);
        DatapackInfo info = inspect(Fixtures.zip(tmp.resolve("big.zip"), entries(
                "pack.mcmeta", DATA_MCMETA, "data/a/b.json", "{}",
                "assets/minecraft/lang/en_us.json", big,
                "assets/minecraft/lang/de_de.json", "{\"a\":\"b\"}",
                "assets/minecraft/textures/x.png", "PNG")));
        Path out = tmp.resolve("o.zip");
        DatapackAssetExtractor.Result r = extractor().extract(info, FORMAT, out);
        Map<String, String> files = Fixtures.read(out);
        assertFalse(files.containsKey("assets/minecraft/lang/en_us.json"));
        assertTrue(files.containsKey("assets/minecraft/lang/de_de.json"));
        assertTrue(files.containsKey("assets/minecraft/textures/x.png"));
        assertEquals(1, r.notes().size());
        assertTrue(r.notes().get(0).startsWith(DatapackAssetExtractor.NOTE_PREFIX + "skipped assets/minecraft/lang/en_us.json"),
                r.notes().toString());
        assertTrue(r.jsonBytes() < DatapackAssetExtractor.MAX_JSON_FILE_BYTES);
    }

    @Test
    void budget_capsJsonOverSeveralPacks() throws IOException {
        // Each pack holds 3 MiB of JSON (under the per-file cap); the pass budget allows 64 MiB.
        String json = bigJson(3 * 1024 * 1024);
        DatapackAssetExtractor.Budget budget = new DatapackAssetExtractor.Budget(Long.MAX_VALUE, 7L * 1024 * 1024);
        List<Exception> failures = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            DatapackInfo info = inspect(Fixtures.zip(tmp.resolve("p" + i + ".zip"), entries(
                    "pack.mcmeta", DATA_MCMETA, "data/a/b.json", "{}", "assets/x/lang/en_us.json", json)));
            try {
                extractor().extract(info, FORMAT, tmp.resolve("stage/p" + i + ".zip"), budget);
            } catch (SafeZip.ZipLimitException e) {
                failures.add(e);
                assertFalse(Files.exists(tmp.resolve("stage/p" + i + ".zip")), "nothing written when over budget");
            }
        }
        assertEquals(1, failures.size(), "the third pack does not fit into 7 MiB of JSON");
        assertTrue(failures.get(0).getMessage().contains("JSON"), failures.get(0).getMessage());
        assertTrue(budget.jsonLeft() < 2L * 1024 * 1024 && budget.jsonLeft() > 0, "only successful packs take budget");
    }

    @Test
    void budget_capsTotalBytes() throws IOException {
        DatapackAssetExtractor.Budget budget = new DatapackAssetExtractor.Budget(100, Long.MAX_VALUE);
        DatapackInfo info = inspect(Fixtures.zip(tmp.resolve("b.zip"), entries(
                "pack.mcmeta", DATA_MCMETA, "data/a/b.json", "{}", "assets/x/a.png", "x".repeat(500))));
        SafeZip.ZipLimitException e = assertThrows(SafeZip.ZipLimitException.class,
                () -> extractor().extract(info, FORMAT, tmp.resolve("o.zip"), budget));
        assertTrue(e.getMessage().contains("max-uncompressed-mb"), e.getMessage());
        assertEquals(100, budget.bytesLeft());
    }

    @Test
    void mcmetaFilter_isRemoved_overlaysAndPackKept() throws IOException {
        String mcmeta = "{\"pack\":{\"pack_format\":88,\"description\":\"rp\"},"
                + "\"filter\":{\"block\":[{\"namespace\":\"minecraft\"}]},"
                + "\"overlays\":{\"entries\":[{\"directory\":\"ov\",\"formats\":[88,97]}]},"
                + "\"language\":{\"xx_xx\":{\"name\":\"X\",\"region\":\"X\",\"bidirectional\":false}}}";
        DatapackInfo info = inspect(Fixtures.zip(tmp.resolve("f.zip"), entries(
                "pack.mcmeta", DATA_MCMETA, "data/x/a.json", "{}",
                "resourcepack/pack.mcmeta", mcmeta,
                "resourcepack/assets/x/a.png", "PNG",
                "resourcepack/ov/assets/x/b.png", "PNG2")));
        Path out = tmp.resolve("o.zip");
        DatapackAssetExtractor.Result r = extractor().extract(info, FORMAT, out);
        Map<String, String> files = Fixtures.read(out);
        JsonObject staged = JsonParser.parseString(files.get("pack.mcmeta")).getAsJsonObject();
        assertFalse(staged.has("filter"), staged.toString());
        assertEquals(88, staged.getAsJsonObject("pack").get("pack_format").getAsInt());
        assertTrue(staged.has("overlays"));
        assertTrue(staged.has("language"));
        assertTrue(files.containsKey("ov/assets/x/b.png"));
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("filter")), r.notes().toString());
        assertFalse(r.mcmetaSynthesized());
    }

    @Test
    void resourcePackOnly_filterRemovedToo() throws IOException {
        String mcmeta = "{\"pack\":{\"pack_format\":88,\"description\":\"rp\"},\"filter\":{\"block\":[{}]}}";
        DatapackInfo info = inspect(Fixtures.zip(tmp.resolve("rp.zip"), entries(
                "pack.mcmeta", mcmeta, "assets/x/a.png", "PNG")));
        assertEquals(DatapackKind.RESOURCE_PACK_ONLY, info.kind());
        Path out = tmp.resolve("o.zip");
        extractor().extract(info, FORMAT, out);
        assertFalse(JsonParser.parseString(Fixtures.read(out).get("pack.mcmeta")).getAsJsonObject().has("filter"));
    }

    @Test
    void oversizedMcmeta_isReplacedByAGeneratedOne() throws IOException {
        String huge = "{\"pack\":{\"pack_format\":88,\"description\":\"" + "d".repeat(300 * 1024) + "\"}}";
        DatapackInfo info = inspect(Fixtures.zip(tmp.resolve("m.zip"), entries(
                "pack.mcmeta", DATA_MCMETA, "data/x/a.json", "{}",
                "resourcepack/pack.mcmeta", huge, "resourcepack/assets/x/a.png", "PNG")));
        Path out = tmp.resolve("o.zip");
        DatapackAssetExtractor.Result r = extractor().extract(info, FORMAT, out);
        assertTrue(r.mcmetaSynthesized());
        assertTrue(Fixtures.read(out).get("pack.mcmeta").length() < 1024);
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("pack.mcmeta is larger")), r.notes().toString());
    }

    @Test
    void sanitize_leavesCleanMcmetaUntouched() {
        byte[] raw = "{\"pack\":{\"pack_format\":88}}".getBytes();
        List<String> notes = new ArrayList<>();
        assertSame(raw, DatapackAssetExtractor.sanitizeMcmeta(raw, notes));
        assertTrue(notes.isEmpty());
        byte[] broken = "{not json".getBytes();
        assertSame(broken, DatapackAssetExtractor.sanitizeMcmeta(broken, notes));
    }

    @Test
    void stager_countsKeptPacksAgainstTheBudget() throws IOException {
        // Limits whose total (also the pass budget) fits one pack but not two.
        ZipLimits small = new ZipLimits(ZipLimits.DEFAULTS.maxFileBytes(), 1500, ZipLimits.DEFAULTS.maxEntryBytes(),
                1000, 100);
        DatapackAssetExtractor ex = new DatapackAssetExtractor(small, tmp.resolve("t"));
        DatapackStager stager = new DatapackStager(tmp.resolve("stage"), ex, 1, 0, ms -> { });
        DatapackInspector insp = new DatapackInspector(small, tmp.resolve("t"));
        Path a = Fixtures.zip(tmp.resolve("a.zip"), entries("pack.mcmeta", DATA_MCMETA, "data/a/b.json", "{}",
                "assets/a/x.png", "A".repeat(1000)));
        Path b = Fixtures.zip(tmp.resolve("b.zip"), entries("pack.mcmeta", DATA_MCMETA, "data/b/b.json", "{}",
                "assets/b/x.png", "B".repeat(1000)));
        DatapackInfo ia = insp.inspect(a, "file/a.zip", true);
        DatapackStager.Report first = stager.run(List.of(new DatapackStager.StageInput("dp-a", ia, "a", false)), FORMAT, true);
        assertEquals(DatapackStager.Status.STAGED, first.outcome("dp-a").status());

        DatapackInfo ib = insp.inspect(b, "file/b.zip", true);
        DatapackStager.Report second = stager.run(List.of(
                new DatapackStager.StageInput("dp-a", ia, "a", false),
                new DatapackStager.StageInput("dp-b", ib, "b", false)), FORMAT, true);
        assertEquals(DatapackStager.Status.UNCHANGED, second.outcome("dp-a").status());
        assertEquals(DatapackStager.Status.LIMIT_EXCEEDED, second.outcome("dp-b").status());
        assertEquals(List.of("dp-a"), second.mergedAliases());
    }
}
