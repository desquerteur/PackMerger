package sh.pcx.packmerger.datapack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static sh.pcx.packmerger.datapack.Fixtures.DATA_MCMETA;
import static sh.pcx.packmerger.datapack.Fixtures.RP_MCMETA;
import static sh.pcx.packmerger.datapack.Fixtures.entries;

class DatapackInspectorTest {

    @TempDir Path tmp;

    private DatapackInfo inspect(Path p) {
        return new DatapackInspector(ZipLimits.DEFAULTS, tmp.resolve("t")).inspect(p, "file/" + p.getFileName(), true);
    }

    @Test
    void dataOnlyDatapack_isNone() throws IOException {
        DatapackInfo i = inspect(Fixtures.zip(tmp.resolve("dataonly.zip"),
                entries("pack.mcmeta", DATA_MCMETA, "data/x/function/a.mcfunction", "say hi")));
        assertEquals(DatapackKind.DATAPACK, i.kind());
        assertEquals(AssetLayout.NONE, i.layout());
        assertFalse(i.hasAssets());
        assertNull(i.assetRoot());
    }

    @Test
    void assetsNextToData_isRootAssets() throws IOException {
        DatapackInfo i = inspect(Fixtures.zip(tmp.resolve("hybrid.zip"), Fixtures.hybrid()));
        assertEquals(DatapackKind.DATAPACK, i.kind());
        assertEquals(AssetLayout.ROOT_ASSETS, i.layout());
        assertEquals("", i.assetRoot());
        assertEquals(2, i.assetFiles());
        assertTrue(i.hasAssets());
    }

    @Test
    void resourcepackSubfolder_isSubfolder() throws IOException {
        DatapackInfo i = inspect(Fixtures.zip(tmp.resolve("sub.zip"), entries(
                "pack.mcmeta", DATA_MCMETA,
                "data/x/a.json", "{}",
                "resourcepack/pack.mcmeta", RP_MCMETA,
                "resourcepack/assets/x/textures/a.png", "PNG")));
        assertEquals(AssetLayout.SUBFOLDER, i.layout());
        assertEquals("resourcepack/", i.assetRoot());
        assertEquals(1, i.assetFiles());
    }

    @Test
    void wrappedSingleFolder_isUnwrapped() throws IOException {
        DatapackInfo i = inspect(Fixtures.zip(tmp.resolve("wrapped.zip"), entries(
                "Terralith/pack.mcmeta", DATA_MCMETA,
                "Terralith/data/t/a.json", "{}",
                "Terralith/assets/t/lang/en_us.json", "{}",
                "readme.txt", "hi")));
        assertEquals(AssetLayout.ROOT_ASSETS, i.layout());
        assertEquals("Terralith/", i.assetRoot());
    }

    @Test
    void nestedZipAtDepthOne_isNestedZip() throws IOException {
        byte[] rp = Fixtures.zipBytes(entries("pack.mcmeta", RP_MCMETA, "assets/i/textures/a.png", "PNG"));
        DatapackInfo i = inspect(Fixtures.zip(tmp.resolve("nested1.zip"), entries(
                "pack.mcmeta", DATA_MCMETA, "data/i/a.json", "{}", "Incendium RP.zip", rp)));
        assertEquals(AssetLayout.NESTED_ZIP, i.layout());
        assertEquals("Incendium RP.zip", i.assetRoot());
        assertEquals(1, i.assetFiles());
    }

    @Test
    void nestedZipAtDepthTwo_isNestedZip() throws IOException {
        byte[] rp = Fixtures.zipBytes(entries("Wrap/pack.mcmeta", RP_MCMETA, "Wrap/assets/i/a.png", "PNG"));
        DatapackInfo i = inspect(Fixtures.zip(tmp.resolve("nested2.zip"), entries(
                "pack.mcmeta", DATA_MCMETA, "data/i/a.json", "{}", "extras/rp.zip", rp)));
        assertEquals(AssetLayout.NESTED_ZIP, i.layout());
        assertEquals("extras/rp.zip", i.assetRoot());
    }

    @Test
    void nestedZipTooDeep_isIgnored() throws IOException {
        byte[] rp = Fixtures.zipBytes(entries("assets/i/a.png", "PNG"));
        DatapackInfo i = inspect(Fixtures.zip(tmp.resolve("deep.zip"), entries(
                "pack.mcmeta", DATA_MCMETA, "data/i/a.json", "{}", "a/b/rp.zip", rp)));
        assertEquals(AssetLayout.NONE, i.layout());
    }

    @Test
    void nestedZipWithoutAssets_isIgnored() throws IOException {
        byte[] other = Fixtures.zipBytes(entries("data/x/a.json", "{}"));
        DatapackInfo i = inspect(Fixtures.zip(tmp.resolve("noassets.zip"), entries(
                "pack.mcmeta", DATA_MCMETA, "data/i/a.json", "{}", "addon.zip", other)));
        assertEquals(AssetLayout.NONE, i.layout());
    }

    @Test
    void zipInsideNestedZip_isIgnored() throws IOException {
        byte[] innermost = Fixtures.zipBytes(entries("assets/i/a.png", "PNG"));
        byte[] middle = Fixtures.zipBytes(entries("inner.zip", innermost));
        DatapackInfo i = inspect(Fixtures.zip(tmp.resolve("matryoshka.zip"), entries(
                "pack.mcmeta", DATA_MCMETA, "data/i/a.json", "{}", "middle.zip", middle)));
        assertEquals(AssetLayout.NONE, i.layout());
        assertTrue(i.detail().contains("nested zip inside nested zip ignored"), i.detail());
    }

    @Test
    void resourcePackInDatapacksFolder_isResourcePackOnly() throws IOException {
        DatapackInfo i = inspect(Fixtures.zip(tmp.resolve("rp.zip"), entries(
                "pack.mcmeta", RP_MCMETA, "assets/minecraft/textures/block/stone.png", "PNG")));
        assertEquals(DatapackKind.RESOURCE_PACK_ONLY, i.kind());
        assertEquals(AssetLayout.ROOT_ASSETS, i.layout());
        assertTrue(i.hasAssets());
    }

    @Test
    void neither_isNotAPack() throws IOException {
        DatapackInfo i = inspect(Fixtures.zip(tmp.resolve("random.zip"), entries("readme.txt", "hello")));
        assertEquals(DatapackKind.NOT_A_PACK, i.kind());
        assertFalse(i.hasAssets());
    }

    @Test
    void corruptZip_isUnreadable() throws IOException {
        byte[] full = Fixtures.zipBytes(Fixtures.hybrid());
        Path z = tmp.resolve("broken.zip");
        Files.write(z, Arrays.copyOf(full, full.length - 30));
        DatapackInfo i = inspect(z);
        assertEquals(DatapackKind.UNREADABLE, i.kind());
        assertTrue(i.detail().startsWith("corrupt zip"), i.detail());
        assertNull(i.sha1());
    }

    @Test
    void limitExceeded_isReported() throws IOException {
        ZipLimits tiny = new ZipLimits(ZipLimits.MIB, ZipLimits.MIB, ZipLimits.MIB, 3, 100);
        DatapackInfo i = new DatapackInspector(tiny).inspect(
                Fixtures.zip(tmp.resolve("many.zip"), Fixtures.hybrid()), "file/many.zip", true);
        assertEquals(DatapackKind.LIMIT_EXCEEDED, i.kind());
    }

    @Test
    void extractedDirectoryDatapack_isInspected_withoutHashes() throws IOException {
        Path d = Fixtures.dir(tmp.resolve("folderpack"), Fixtures.hybrid());
        DatapackInfo i = inspect(d);
        assertTrue(i.directory());
        assertEquals(AssetLayout.ROOT_ASSETS, i.layout());
        assertNull(i.sha1());
        assertNull(i.sha512());
        assertNotNull(i.fingerprint());
    }

    @Test
    void zipHashes_computed() throws IOException {
        Path z = Fixtures.zip(tmp.resolve("h.zip"), Fixtures.hybrid());
        DatapackInfo i = inspect(z);
        assertEquals(40, i.sha1().length());
        assertEquals(128, i.sha512().length());
        assertArrayEquals(DatapackInspector.hashes(z), new String[]{i.sha1(), i.sha512()});
    }

    @Test
    void zipSlipEntries_areSkippedAndReported() throws IOException {
        DatapackInfo i = inspect(Fixtures.zip(tmp.resolve("slip.zip"), entries(
                "pack.mcmeta", DATA_MCMETA, "data/a/b.json", "{}",
                "assets/a/ok.png", "PNG", "../../../evil.png", "EVIL")));
        assertEquals(AssetLayout.ROOT_ASSETS, i.layout());
        assertEquals(1, i.assetFiles());
        assertTrue(i.detail().contains("unsafe"), i.detail());
    }
}
