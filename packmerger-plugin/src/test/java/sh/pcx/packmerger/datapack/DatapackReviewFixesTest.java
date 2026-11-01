package sh.pcx.packmerger.datapack;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static sh.pcx.packmerger.datapack.Fixtures.DATA_MCMETA;
import static sh.pcx.packmerger.datapack.Fixtures.RP_MCMETA;
import static sh.pcx.packmerger.datapack.Fixtures.entries;

/** Regression tests for the code-review and security-review findings on datapack discovery. */
class DatapackReviewFixesTest {

    @TempDir Path tmp;

    private DatapackInfo inspect(Path p) {
        return new DatapackInspector(ZipLimits.DEFAULTS, tmp.resolve("t")).inspect(p, "file/" + p.getFileName(), true);
    }

    // --- code review M3: sub-datapack folders and folders without assets/ ---

    @Test
    void subfolderDetection_skipsBundledSubDatapack() throws IOException {
        DatapackInfo info = inspect(Fixtures.zip(tmp.resolve("bundle.zip"), entries(
                "pack.mcmeta", DATA_MCMETA, "data/a/x.json", "{}",
                "compat/pack.mcmeta", DATA_MCMETA, "compat/data/c/y.json", "{}",
                "resourcepack/pack.mcmeta", RP_MCMETA, "resourcepack/assets/a/t.png", "PNG")));
        assertEquals(AssetLayout.SUBFOLDER, info.layout());
        assertTrue(info.assetRoot().endsWith("resourcepack/"), info.assetRoot());
    }

    @Test
    void subfolderWithOnlyMcmeta_isDataOnly() throws IOException {
        DatapackInfo info = inspect(Fixtures.zip(tmp.resolve("compat.zip"), entries(
                "pack.mcmeta", DATA_MCMETA, "data/a/x.json", "{}",
                "compat/pack.mcmeta", DATA_MCMETA, "compat/data/c/y.json", "{}")));
        assertEquals(DatapackKind.DATAPACK, info.kind());
        assertEquals(AssetLayout.NONE, info.layout());
        assertFalse(info.hasAssets());

        DatapackInfo emptyRp = inspect(Fixtures.zip(tmp.resolve("empty-rp.zip"), entries(
                "pack.mcmeta", DATA_MCMETA, "data/a/x.json", "{}", "extras/pack.mcmeta", RP_MCMETA)));
        assertEquals(AssetLayout.NONE, emptyRp.layout());
    }

    // --- code review M4: the loaded folder's copy keeps the plain alias ---

    @Test
    void aliases_preferTheFolderTheServerLoads() {
        Path loaded = Path.of("world", "datapacks", "terralith.zip");
        Path stray = Path.of("arena", "datapacks", "terralith.zip");
        Map<Path, String> aliases = DatapackStager.assignAliases(List.of(stray, loaded), Set.of(loaded));
        assertEquals("dp-terralith", aliases.get(loaded));
        assertTrue(aliases.get(stray).matches("dp-terralith-[0-9a-f]{6}"), aliases.get(stray));
        // Stable regardless of input order.
        assertEquals(aliases, DatapackStager.assignAliases(List.of(loaded, stray), Set.of(loaded)));
    }

    // --- security L3: NTFS junctions in folder datapacks are not followed ---

    @Test
    void dirTree_doesNotFollowJunctions() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"), "Windows only");
        Path secret = Fixtures.dir(tmp.resolve("server-secrets"), entries("ops.json", "SECRET"));
        Path pack = Fixtures.dir(tmp.resolve("dp"), entries(
                "pack.mcmeta", DATA_MCMETA, "data/a/x.json", "{}", "assets/a/ok.png", "PNG"));
        Path junction = pack.resolve("assets").resolve("leak");
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), secret.toString())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        Assumptions.assumeTrue(p.waitFor() == 0 && Files.isDirectory(junction), "mklink /J not available");
        try (SafeZip.Tree tree = SafeZip.open(pack, ZipLimits.DEFAULTS)) {
            assertTrue(tree.contains("assets/a/ok.png"));
            assertFalse(tree.names().stream().anyMatch(n -> n.contains("ops.json")), tree.names().toString());
            assertTrue(tree.skipped() >= 1);
        } finally {
            Files.deleteIfExists(junction); // removes the junction, not the target
        }
    }

    // --- security L4: leftover nested-zip temp files ---

    @Test
    void cleanTemp_removesOnlyOldNestedZips() throws IOException {
        Path temp = Files.createDirectories(tmp.resolve("tmp"));
        Path old = Files.writeString(temp.resolve("pm-nested-1.zip"), "x");
        Path fresh = Files.writeString(temp.resolve("pm-nested-2.zip"), "x");
        Path other = Files.writeString(temp.resolve("keep.txt"), "x");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minusSeconds(3600)));
        assertEquals(1, DatapackPackManager.cleanTemp(temp, 10 * 60 * 1000L));
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(fresh));
        assertEquals(1, DatapackPackManager.cleanTemp(temp, 0));
        assertFalse(Files.exists(fresh));
        assertTrue(Files.exists(other));
    }

    // --- code review M2: persisted inspections depend on limits and plugin version ---

    private static DatapackInfo info(Path p, DatapackKind kind) {
        return new DatapackInfo(p, DatapackLocator.packId(p), true, false, kind, AssetLayout.NONE, null, 0,
                "123:456", "a".repeat(40), "b".repeat(128), "detail");
    }

    @Test
    void state_roundTrip_withSameKey() {
        Path p = Path.of("world", "datapacks", "a.zip");
        Map<Path, DatapackInfo> in = new LinkedHashMap<>();
        in.put(p, info(p, DatapackKind.DATAPACK));
        String key = DatapackPackManager.stateKey(ZipLimits.DEFAULTS, "1.4.0");
        Map<Path, DatapackInfo> out = DatapackPackManager.parseState(DatapackPackManager.stateJson(in, key), key);
        assertEquals(1, out.size());
        assertEquals(DatapackKind.DATAPACK, out.get(p).kind());
        assertEquals("123:456", out.get(p).fingerprint());
    }

    @Test
    void state_discardedWhenLimitsOrVersionChange() {
        Path p = Path.of("world", "datapacks", "a.zip");
        String json = DatapackPackManager.stateJson(Map.of(p, info(p, DatapackKind.DATAPACK)),
                DatapackPackManager.stateKey(ZipLimits.DEFAULTS, "1.4.0"));
        ZipLimits bigger = ZipLimits.ofMegabytes(300, 512, 64, 50_000, 100);
        assertTrue(DatapackPackManager.parseState(json, DatapackPackManager.stateKey(bigger, "1.4.0")).isEmpty());
        assertTrue(DatapackPackManager.parseState(json, DatapackPackManager.stateKey(ZipLimits.DEFAULTS, "1.4.1")).isEmpty());
        // A state file from before this fix (no key) is not reused either.
        assertTrue(DatapackPackManager.parseState("{\"version\":1,\"datapacks\":{}}", "x").isEmpty());
    }

    @Test
    void state_neverPersistsLimitExceededOrUnreadable() {
        Path a = Path.of("a.zip");
        Path b = Path.of("b.zip");
        Map<Path, DatapackInfo> in = new LinkedHashMap<>();
        in.put(a, info(a, DatapackKind.LIMIT_EXCEEDED));
        in.put(b, info(b, DatapackKind.UNREADABLE));
        String key = DatapackPackManager.stateKey(ZipLimits.DEFAULTS, "1.4.0");
        assertTrue(DatapackPackManager.parseState(DatapackPackManager.stateJson(in, key), key).isEmpty());
    }

    // --- security M2 / L7: trusted projects by ID only, fork User-Agent ---

    @Test
    void trustedProjects_matchExactProjectIdsOnly() {
        Set<String> trusted = Set.of("AANobbMI");
        assertTrue(ExternalPackService.isTrusted(trusted, "AANobbMI"));
        assertFalse(ExternalPackService.isTrusted(trusted, "aanobbmi"), "IDs are case-sensitive");
        assertFalse(ExternalPackService.isTrusted(Set.of("cool-rp"), null));
        assertFalse(ExternalPackService.isTrusted(Set.of("cool-rp"), "XyZ12345"));
        assertTrue(ExternalPackService.looksLikeProjectId("AANobbMI"));
        assertFalse(ExternalPackService.looksLikeProjectId("cool-rp"));
        assertFalse(ExternalPackService.looksLikeProjectId("fresh-animations"));
    }

    @Test
    void userAgent_namesTheFork() {
        String ua = ExternalPackService.userAgent("1.4.0");
        assertTrue(ua.startsWith("desquerteur/PackMerger/1.4.0"), ua);
        assertTrue(ua.contains("https://github.com/desquerteur/PackMerger"), ua);
    }
}
