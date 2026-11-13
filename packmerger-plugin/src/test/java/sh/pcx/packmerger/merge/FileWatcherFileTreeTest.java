package sh.pcx.packmerger.merge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The one-registration watch used on Windows, so watched pack folders can still be renamed. */
class FileWatcherFileTreeTest {

    @TempDir Path root;

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @Test
    void onWindowsAWatchedPackFolderCanBeRenamedAndNestedChangesArrive() throws Exception {
        assumeTrue(windows(), "file-tree watches are a Windows feature");
        Path pack = Files.createDirectories(root.resolve("my-pack/assets/pmtest"));
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            Map<WatchKey, Path> keys = new HashMap<>();
            assertTrue(FileWatcher.registerFileTree(ws, root, keys));
            assertEquals(Map.of(keys.keySet().iterator().next(), root), keys);

            // Was "Permission denied" while every folder had its own registration.
            Files.move(root.resolve("my-pack"), root.resolve("renamed-pack"));

            Files.writeString(root.resolve("renamed-pack/assets/pmtest/new.txt"), "x");
            Set<Path> changed = new HashSet<>();
            long until = System.currentTimeMillis() + 5_000;
            while (!changed.contains(Path.of("renamed-pack", "assets", "pmtest", "new.txt"))
                    && System.currentTimeMillis() < until) {
                WatchKey key = ws.poll(500, TimeUnit.MILLISECONDS);
                if (key == null) continue;
                for (WatchEvent<?> e : key.pollEvents()) {
                    if (e.context() instanceof Path p) changed.add(p);
                }
                key.reset();
            }
            // Contexts are relative to the root, as FileWatcher resolves them.
            assertTrue(changed.contains(Path.of("renamed-pack", "assets", "pmtest", "new.txt")), changed.toString());
            assertFalse(Files.exists(pack));
        }
    }

    @Test
    void elsewhereTheWatcherFallsBackToOneRegistrationPerFolder() throws IOException {
        assumeTrue(!windows(), "Windows supports file-tree watches");
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            Map<WatchKey, Path> keys = new HashMap<>();
            assertFalse(FileWatcher.registerFileTree(ws, root, keys));
            assertTrue(keys.isEmpty());
        }
    }
}
