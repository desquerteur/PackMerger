package sh.pcx.packmerger.merge;

import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.datapack.DatapackLocator;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** B11e: which changes under packs/ trigger a hot-reload merge. */
class FileWatcherRelevanceTest {

    @Test
    void topLevel_onlyPackShapedEntries() {
        assertTrue(FileWatcher.isRelevant(Path.of("my-pack.zip")));
        assertTrue(FileWatcher.isRelevant(Path.of("my-pack")));
        assertTrue(FileWatcher.isRelevant(Path.of("pack.mcmeta")));
        assertTrue(FileWatcher.isRelevant(Path.of("pack.png")));
        assertFalse(FileWatcher.isRelevant(Path.of("notes.txt")));
        assertFalse(FileWatcher.isRelevant(Path.of(".remote-cache")));
    }

    @Test
    void nested_everyFileInsideAPackFolderCounts() {
        assertTrue(FileWatcher.isRelevant(Path.of("my-pack", "assets", "minecraft", "lang", "en_us.json")));
        assertTrue(FileWatcher.isRelevant(Path.of("my-pack", "pack.mcmeta")));
        assertTrue(FileWatcher.isRelevant(Path.of("my-pack", "assets", "minecraft", "textures", "item", "a.png")));
    }

    @Test
    void dotSegments_areIgnoredAtAnyDepth() {
        assertFalse(FileWatcher.isRelevant(Path.of(".plugin-packs", "oraxen.zip")));
        assertFalse(FileWatcher.isRelevant(Path.of(".remote-cache", "x.zip")));
        assertFalse(FileWatcher.isRelevant(Path.of("my-pack", ".git", "index")));
        assertFalse(FileWatcher.isRelevant(Path.of("my-pack", "assets", ".en_us.json.swp")));
        assertFalse(FileWatcher.isRelevant(Path.of("")));
    }

    @Test
    void datapackPredicate_ignoresUploadsInProgressAndHiddenFiles() {
        assertTrue(DatapackLocator.isRelevantChange(Path.of("terralith.zip")));
        assertTrue(DatapackLocator.isRelevantChange(Path.of("folderpack", "data", "x", "a.json")));
        assertFalse(DatapackLocator.isRelevantChange(Path.of("terralith.zip.part")));
        assertFalse(DatapackLocator.isRelevantChange(Path.of("terralith.zip.crdownload")));
        assertFalse(DatapackLocator.isRelevantChange(Path.of("terralith.zip.filepart")));
        assertFalse(DatapackLocator.isRelevantChange(Path.of("upload.tmp")));
        assertFalse(DatapackLocator.isRelevantChange(Path.of(".terralith.zip.swp")));
        assertFalse(DatapackLocator.isRelevantChange(Path.of("folderpack", ".git", "index")));
        assertFalse(DatapackLocator.isRelevantChange(Path.of("")));
    }
}
