package sh.pcx.packmerger.datapack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.pcx.packmerger.datapack.DatapackStager.Report;
import sh.pcx.packmerger.datapack.DatapackStager.StageInput;
import sh.pcx.packmerger.datapack.DatapackStager.Status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static sh.pcx.packmerger.datapack.Fixtures.DATA_MCMETA;
import static sh.pcx.packmerger.datapack.Fixtures.entries;

class DatapackStagerTest {

    @TempDir Path tmp;

    private static final int FORMAT = 88;

    private Path stage() {
        return tmp.resolve("packs/.datapack-packs");
    }

    private DatapackStager stager(int polls, sh.pcx.packmerger.discovery.PluginPackStager.Sleeper sleeper, AtomicLong clock) {
        return new DatapackStager(stage(), new DatapackAssetExtractor(ZipLimits.DEFAULTS, tmp.resolve("t")),
                polls, 0, sleeper, clock::get);
    }

    private DatapackStager stager() {
        return stager(1, ms -> { }, new AtomicLong(System.currentTimeMillis()));
    }

    private StageInput input(Path p, boolean enabled) {
        DatapackInfo info = new DatapackInspector(ZipLimits.DEFAULTS, tmp.resolve("t"))
                .inspect(p, DatapackLocator.packId(p), enabled);
        return new StageInput(DatapackStager.aliasFor(p.getFileName().toString()), info, p.toString(), false);
    }

    private Path hybrid(String name) throws IOException {
        return Fixtures.zip(tmp.resolve("world/datapacks/" + name), Fixtures.hybrid());
    }

    @Test
    void add_isStaged() throws IOException {
        Report r = stager().run(List.of(input(hybrid("terralith.zip"), true)), FORMAT, true);
        assertEquals(Status.STAGED, r.outcome("dp-terralith").status());
        assertTrue(r.outcome("dp-terralith").merged());
        Map<String, String> files = Fixtures.read(stage().resolve("dp-terralith.zip"));
        assertTrue(files.keySet().stream().noneMatch(n -> n.startsWith("data/")));
        assertEquals(List.of("dp-terralith"), r.mergedAliases());
    }

    @Test
    void unchanged_isNotRewritten() throws IOException {
        DatapackStager s = stager();
        Path dp = hybrid("terralith.zip");
        s.run(List.of(input(dp, true)), FORMAT, true);
        Path staged = stage().resolve("dp-terralith.zip");
        FileTime before = FileTime.fromMillis(1_000_000_000L);
        Files.setLastModifiedTime(staged, before);

        Report r = s.run(List.of(input(dp, true)), FORMAT, true);
        assertEquals(Status.UNCHANGED, r.outcome("dp-terralith").status());
        assertEquals(before, Files.getLastModifiedTime(staged));
    }

    @Test
    void update_isRestaged() throws IOException {
        DatapackStager s = stager();
        Path dp = hybrid("terralith.zip");
        s.run(List.of(input(dp, true)), FORMAT, true);

        Map<String, Object> v2 = Fixtures.hybrid();
        v2.put("assets/terra/textures/block/new.png", "NEW");
        Fixtures.zip(dp, v2);
        Files.setLastModifiedTime(dp, FileTime.fromMillis(System.currentTimeMillis() + 5000));

        Report r = s.run(List.of(input(dp, true)), FORMAT, true);
        assertEquals(Status.STAGED, r.outcome("dp-terralith").status());
        assertEquals("NEW", Fixtures.read(stage().resolve("dp-terralith.zip")).get("assets/terra/textures/block/new.png"));
    }

    @Test
    void remove_isPruned() throws IOException {
        DatapackStager s = stager();
        s.run(List.of(input(hybrid("terralith.zip"), true)), FORMAT, true);
        Report r = s.run(List.of(), FORMAT, true);
        assertEquals(List.of("dp-terralith"), r.pruned());
        assertFalse(Files.exists(stage().resolve("dp-terralith.zip")));
    }

    @Test
    void becomingDataOnly_isPruned() throws IOException {
        DatapackStager s = stager();
        Path dp = hybrid("terralith.zip");
        s.run(List.of(input(dp, true)), FORMAT, true);
        Fixtures.zip(dp, entries("pack.mcmeta", DATA_MCMETA, "data/a/b.json", "{}"));
        Files.setLastModifiedTime(dp, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        Report r = s.run(List.of(input(dp, true)), FORMAT, true);
        assertEquals(Status.DATA_ONLY, r.outcome("dp-terralith").status());
        assertEquals(List.of("dp-terralith"), r.pruned());
    }

    @Test
    void dataOnly_isReportedNotStaged() throws IOException {
        Path dp = Fixtures.zip(tmp.resolve("world/datapacks/plain.zip"),
                entries("pack.mcmeta", DATA_MCMETA, "data/a/b.json", "{}"));
        Report r = stager().run(List.of(input(dp, true)), FORMAT, true);
        assertEquals(Status.DATA_ONLY, r.outcome("dp-plain").status());
        assertFalse(r.outcome("dp-plain").merged());
        assertFalse(Files.exists(stage().resolve("dp-plain.zip")));
    }

    @Test
    void sizeChangingBetweenPolls_isDeferred_previousCopyKept() throws IOException {
        Path dp = hybrid("terralith.zip");
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        stager(1, ms -> { }, clock).run(List.of(input(dp, true)), FORMAT, true);
        byte[] previous = Files.readAllBytes(stage().resolve("dp-terralith.zip"));

        // New upload in progress: the file grows while the stager waits between polls.
        Map<String, Object> v2 = Fixtures.hybrid();
        v2.put("assets/terra/textures/block/new.png", "NEW");
        Fixtures.zip(dp, v2);
        Files.setLastModifiedTime(dp, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        StageInput changed = input(dp, true);
        DatapackStager polling = stager(3, ms -> {
            try {
                Files.write(dp, new byte[]{1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, clock);
        // The polling stager never staged this alias, so give it the previous copy on disk.
        Report r = polling.run(List.of(changed), FORMAT, true);
        assertEquals(Status.DEFERRED, r.outcome("dp-terralith").status());
        assertTrue(r.outcome("dp-terralith").merged(), "previous copy stays in the merge");
        assertArrayEquals(previous, Files.readAllBytes(stage().resolve("dp-terralith.zip")));
    }

    @Test
    void stableAfterPolls_isStaged() throws IOException {
        Path dp = hybrid("terralith.zip");
        List<Long> sleeps = new ArrayList<>();
        Report r = stager(3, sleeps::add, new AtomicLong(System.currentTimeMillis()))
                .run(List.of(input(dp, true)), FORMAT, true);
        assertEquals(Status.STAGED, r.outcome("dp-terralith").status());
        assertEquals(2, sleeps.size());
    }

    @Test
    void youngUnreadableZip_isDeferred_oldIsInvalid() throws IOException {
        byte[] full = Fixtures.zipBytes(Fixtures.hybrid());
        Path dp = tmp.resolve("world/datapacks/upload.zip");
        Files.createDirectories(dp.getParent());
        Files.write(dp, Arrays.copyOf(full, full.length / 2));
        StageInput in = input(dp, true);
        assertEquals(DatapackKind.UNREADABLE, in.info().kind());

        long mtime = Files.getLastModifiedTime(dp).toMillis();
        Report young = stager(1, ms -> { }, new AtomicLong(mtime + 5_000)).run(List.of(in), FORMAT, true);
        assertEquals(Status.DEFERRED, young.outcome("dp-upload").status());

        Report old = stager(1, ms -> { }, new AtomicLong(mtime + 120_000)).run(List.of(in), FORMAT, true);
        assertEquals(Status.INVALID, old.outcome("dp-upload").status());
        assertTrue(old.outcome("dp-upload").detail().contains("corrupt zip"));
    }

    @Test
    void sameDatapackInTwoFolders_isStagedOnce() throws IOException {
        Path a = Fixtures.zip(tmp.resolve("world/datapacks/terralith.zip"), Fixtures.hybrid());
        Path b = tmp.resolve("lobby/datapacks/terralith-copy.zip");
        Files.createDirectories(b.getParent());
        Files.copy(a, b);
        Report r = stager().run(List.of(input(a, true), input(b, true)), FORMAT, true);
        assertEquals(1, r.mergedAliases().size());
        assertEquals(1, r.count(Status.DUPLICATE));
        try (var s = Files.list(stage())) {
            assertEquals(1, s.filter(p -> p.toString().endsWith(".zip")).count());
        }
    }

    @Test
    void notEnabledWithOnlyEnabled_isSkipped() throws IOException {
        Path dp = hybrid("terralith.zip");
        Report r = stager().run(List.of(input(dp, false)), FORMAT, true);
        assertEquals(Status.SKIPPED_NOT_ENABLED, r.outcome("dp-terralith").status());
        assertFalse(Files.exists(stage().resolve("dp-terralith.zip")));

        Report all = stager().run(List.of(input(dp, false)), FORMAT, false);
        assertEquals(Status.STAGED, all.outcome("dp-terralith").status());
    }

    @Test
    void zipBomb_isLimitExceeded() throws IOException {
        Path dp = Fixtures.zip(tmp.resolve("world/datapacks/bomb.zip"), entries(
                "pack.mcmeta", DATA_MCMETA, "data/a/b.json", "{}", "assets/a/zeros.bin", new byte[4 * 1024 * 1024]));
        Report r = stager().run(List.of(input(dp, true)), FORMAT, true);
        assertEquals(Status.LIMIT_EXCEEDED, r.outcome("dp-bomb").status());
        assertFalse(Files.exists(stage().resolve("dp-bomb.zip")));
    }

    @Test
    void formatChange_restages() throws IOException {
        DatapackStager s = stager();
        Path dp = hybrid("terralith.zip");
        s.run(List.of(input(dp, true)), 84, true);
        Report r = s.run(List.of(input(dp, true)), 88, true);
        assertEquals(Status.STAGED, r.outcome("dp-terralith").status());
    }

    @Test
    void aliases_areStableAndCollisionSafe() {
        assertEquals("dp-terralith", DatapackStager.aliasFor("Terralith.zip"));
        assertEquals("dp-dungeons-and-taverns-v4.4", DatapackStager.aliasFor("Dungeons and Taverns v4.4.zip"));
        assertEquals("dp-x-rp", DatapackStager.externalAliasFor("x.zip"));
        assertEquals(51, DatapackStager.aliasFor("a".repeat(80) + ".zip").length());

        Path a = Path.of("world", "datapacks", "pack.zip");
        Path b = Path.of("lobby", "datapacks", "pack.zip");
        Map<Path, String> first = DatapackStager.assignAliases(List.of(a, b));
        Map<Path, String> again = DatapackStager.assignAliases(List.of(b, a));
        assertEquals(first, again);
        assertNotEquals(first.get(a), first.get(b));
        assertEquals("dp-pack", first.get(b)); // "lobby/…" sorts first
        assertTrue(first.get(a).matches("dp-pack-[0-9a-f]{6}"));
    }
}
