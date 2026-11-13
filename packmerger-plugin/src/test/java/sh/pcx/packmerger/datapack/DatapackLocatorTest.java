package sh.pcx.packmerger.datapack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.pcx.packmerger.datapack.DatapackLocator.Folder;
import sh.pcx.packmerger.datapack.DatapackLocator.WorldDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DatapackLocatorTest {

    @TempDir Path root;

    private Path level(String name) throws IOException {
        Path p = root.resolve(name);
        Files.createDirectories(p);
        Files.writeString(p.resolve("level.dat"), "x");
        return p;
    }

    private Path mkdirs(Path p) throws IOException {
        return Files.createDirectories(p);
    }

    @Test
    void levelRoot_fromLevelFolderItself() throws IOException {
        Path world = level("world");
        assertEquals(world, DatapackLocator.levelRoot(world, root));
    }

    @Test
    void levelRoot_fromDimensionFolder_walksUp() throws IOException {
        Path world = level("world");
        Path nether = mkdirs(world.resolve("dimensions/minecraft/the_nether"));
        assertEquals(world, DatapackLocator.levelRoot(nether, root));
    }

    @Test
    void levelRoot_legacyWorldNether_isItsOwnRoot() throws IOException {
        level("world");
        Path legacy = level("world_nether");
        mkdirs(legacy.resolve("DIM-1"));
        assertEquals(legacy, DatapackLocator.levelRoot(legacy, root));
    }

    @Test
    void levelRoot_withoutLevelDat_returnsTheFolder_neverAboveServerRoot() throws IOException {
        Files.writeString(root.resolve("level.dat"), "should never be reached from a sibling");
        Path inner = mkdirs(root.resolve("server/plugins/x"));
        assertEquals(inner.toAbsolutePath().normalize(), DatapackLocator.levelRoot(inner, root.resolve("server")));
    }

    @Test
    void collect_mainLevelOnly_isLoadedByServer() throws IOException {
        Path world = level("world");
        mkdirs(world.resolve("datapacks"));
        Path nether = mkdirs(world.resolve("dimensions/minecraft/the_nether"));
        Path mv = level("mv_survival2");
        mkdirs(mv.resolve("datapacks"));

        List<Folder> folders = DatapackLocator.collect(root, root, "world", List.of(
                new WorldDir("minecraft:overworld", world),
                new WorldDir("minecraft:the_nether", nether),
                new WorldDir("minecraft:mv_survival2", mv)), List.of());

        assertEquals(2, folders.size(), folders.toString());
        assertEquals("level", folders.get(0).origin());
        assertTrue(folders.get(0).loadedByServer());
        assertEquals(world.resolve("datapacks").toRealPath(), folders.get(0).path());
        assertEquals("world:minecraft:mv_survival2", folders.get(1).origin());
        assertFalse(folders.get(1).loadedByServer());
    }

    @Test
    void collect_sameFolderViaDifferentPaths_isDeduplicated() throws IOException {
        Path world = level("world");
        mkdirs(world.resolve("datapacks"));
        List<Folder> folders = DatapackLocator.collect(root, root, "world",
                List.of(new WorldDir("minecraft:overworld", root.resolve("world/./"))),
                List.of("world/datapacks", root.resolve("world/../world/datapacks").toString()));
        assertEquals(1, folders.size());
    }

    @Test
    void collect_extraFolders_relativeAndAbsolute_missingDropped() throws IOException {
        level("world");
        Path rel = mkdirs(root.resolve("shared/datapacks"));
        Path abs = mkdirs(root.resolve("elsewhere/dps"));
        List<Folder> folders = DatapackLocator.collect(root, root, "world", List.of(),
                List.of("shared/datapacks", abs.toString(), "does/not/exist"));
        assertEquals(List.of(rel.toRealPath(), abs.toRealPath()), folders.stream().map(Folder::path).toList());
        assertTrue(folders.stream().allMatch(f -> f.origin().equals("extra") && !f.loadedByServer()));
    }

    @Test
    void collect_noDatapacksFolder_isEmpty() throws IOException {
        level("world");
        assertTrue(DatapackLocator.collect(root, root, "world", List.of(), List.of()).isEmpty());
    }

    @Test
    void collect_customWorldContainer() throws IOException {
        Path container = mkdirs(root.resolve("worlds"));
        Path world = container.resolve("survival");
        mkdirs(world.resolve("datapacks"));
        List<Folder> folders = DatapackLocator.collect(root, container, "survival", List.of(), List.of());
        assertEquals(1, folders.size());
        assertTrue(folders.get(0).loadedByServer());
    }

    @Test
    void candidates_ignorePartialUploadsAndHiddenFiles() throws IOException {
        Path dps = mkdirs(root.resolve("world/datapacks"));
        for (String n : List.of("a.zip", "b.zip.part", "c.zip.crdownload", ".hidden.zip", "d.zip.tmp",
                "e.filepart", "readme.txt", "f.zip.download", "g.zip.!ut")) {
            Files.writeString(dps.resolve(n), "x");
        }
        mkdirs(dps.resolve("folderpack"));
        mkdirs(dps.resolve(".git"));
        List<String> names = DatapackLocator.candidates(dps).stream().map(p -> p.getFileName().toString()).toList();
        assertEquals(List.of("a.zip", "folderpack"), names);
    }

    @Test
    void stillBeingWritten_onlyWithinTheSettleWindow() throws IOException {
        Path zip = root.resolve("upload.zip");
        Files.writeString(zip, "PK half");
        long modified = 1_000_000_000_000L;
        Files.setLastModifiedTime(zip, java.nio.file.attribute.FileTime.fromMillis(modified));
        assertTrue(DatapackLocator.isStillBeingWritten(zip, 3_000, modified));
        assertTrue(DatapackLocator.isStillBeingWritten(zip, 3_000, modified + 2_900));
        assertFalse(DatapackLocator.isStillBeingWritten(zip, 3_000, modified + 3_000));
        // A timestamp in the future or a missing file never hides a broken datapack.
        assertFalse(DatapackLocator.isStillBeingWritten(zip, 3_000, modified - 10));
        assertFalse(DatapackLocator.isStillBeingWritten(root.resolve("gone.zip"), 3_000, modified));
    }

    @Test
    void packId_and_enabledMatching() {
        Path p = Path.of("world", "datapacks", "terralith.zip");
        assertEquals("file/terralith.zip", DatapackLocator.packId(p));
        assertTrue(DatapackPackManager.isEnabled(Set.of("vanilla", "file/terralith.zip"), p));
        assertTrue(DatapackPackManager.isEnabled(Set.of("world/terralith.zip"), p));
        assertFalse(DatapackPackManager.isEnabled(Set.of("vanilla", "file/other.zip"), p));
    }
}
