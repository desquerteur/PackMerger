package sh.pcx.packmerger.datapack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.pcx.packmerger.datapack.ExternalPackStore.Entry;
import sh.pcx.packmerger.datapack.ExternalPackStore.State;
import sh.pcx.packmerger.datapack.modrinth.ModrinthResolver;
import sh.pcx.packmerger.datapack.modrinth.ModrinthVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExternalPackStoreTest {

    @TempDir Path dir;

    private Path file() {
        return dir.resolve("external-sources.json");
    }

    private ExternalPackStore store() {
        ExternalPackStore s = new ExternalPackStore(file(), () -> Instant.parse("2026-11-01T10:00:00Z"));
        s.load();
        return s;
    }

    private static ModrinthResolver.Candidate candidate(String sha512, String slug) {
        return new ModrinthResolver.Candidate("dependency required", "proj-" + slug, slug, slug, "V1", "4.4.1",
                new ModrinthVersion.VersionFile("https://cdn.modrinth.com/data/x/rp.zip", "rp.zip", true, 2483112,
                        "b".repeat(40), sha512, null));
    }

    private static ModrinthResolver.Resolution resolution(ModrinthResolver.Candidate c) {
        return new ModrinthResolver.Resolution("5c1d" + "0".repeat(36), "dpproj", "DPV", List.of(c), List.of());
    }

    @Test
    void upsert_isPending_withShortId() {
        ExternalPackStore s = store();
        ModrinthResolver.Candidate c = candidate("3f9a1c22" + "e".repeat(120), "dnt-rp");
        Entry e = s.upsert(resolution(c), c, "world/datapacks/dnt.zip", false);
        assertEquals("mr-3f9a1c22", e.id());
        assertEquals(State.PENDING, e.state());
        assertNull(e.decidedBy());
        assertEquals("dnt-rp", e.projectName());
    }

    @Test
    void trustedOrAutoApprove_isApprovedByAutoApprove() {
        ExternalPackStore s = store();
        ModrinthResolver.Candidate c = candidate("1".repeat(128), "trusted");
        Entry e = s.upsert(resolution(c), c, "world/datapacks/a.zip", true);
        assertEquals(State.APPROVED, e.state());
        assertEquals("auto-approve", e.decidedBy());
    }

    @Test
    void approveAndReject_persistAndReload() {
        ExternalPackStore s = store();
        ModrinthResolver.Candidate a = candidate("a".repeat(128), "a");
        ModrinthResolver.Candidate b = candidate("b".repeat(128), "b");
        Entry ea = s.upsert(resolution(a), a, "dp/a.zip", false);
        Entry eb = s.upsert(resolution(b), b, "dp/b.zip", false);
        assertTrue(s.approve(ea.id(), "Admin"));
        assertTrue(s.reject(eb.id(), "Admin"));
        assertFalse(s.approve("mr-unknown", "Admin"));

        ExternalPackStore reloaded = store();
        assertEquals(State.APPROVED, reloaded.get(ea.id()).orElseThrow().state());
        assertEquals("Admin", reloaded.get(ea.id()).orElseThrow().decidedBy());
        assertEquals(State.REJECTED, reloaded.get(eb.id()).orElseThrow().state());
        assertEquals(2, reloaded.list().size());
        assertEquals("https://cdn.modrinth.com/data/x/rp.zip", reloaded.get(ea.id()).orElseThrow().url());
    }

    @Test
    void existingEntry_keepsItsDecision() {
        ExternalPackStore s = store();
        ModrinthResolver.Candidate c = candidate("c".repeat(128), "c");
        Entry e = s.upsert(resolution(c), c, "dp/c.zip", false);
        s.reject(e.id(), "Admin");
        Entry again = s.upsert(resolution(c), c, "dp/c-renamed.zip", true);
        assertEquals(State.REJECTED, again.state(), "a rejected file is not asked again, even with auto-approve");
        assertEquals("dp/c-renamed.zip", again.datapack());
    }

    @Test
    void newFileHash_isANewPendingEntry() {
        ExternalPackStore s = store();
        ModrinthResolver.Candidate v1 = candidate("d".repeat(128), "rp");
        Entry e1 = s.upsert(resolution(v1), v1, "dp/x.zip", false);
        s.approve(e1.id(), "Admin");
        ModrinthResolver.Candidate v2 = candidate("e".repeat(128), "rp");
        Entry e2 = s.upsert(resolution(v2), v2, "dp/x.zip", false);
        assertNotEquals(e1.id(), e2.id());
        assertEquals(State.PENDING, e2.state());
        assertEquals(List.of(e2), s.inState(State.PENDING));
    }

    @Test
    void idClash_getsALongerId() {
        ExternalPackStore s = store();
        ModrinthResolver.Candidate a = candidate("12345678" + "a".repeat(120), "a");
        ModrinthResolver.Candidate b = candidate("12345678" + "b".repeat(120), "b");
        Entry ea = s.upsert(resolution(a), a, "dp/a.zip", false);
        Entry eb = s.upsert(resolution(b), b, "dp/b.zip", false);
        assertEquals("mr-12345678", ea.id());
        assertEquals("mr-12345678bbbb", eb.id());
    }

    @Test
    void corruptFile_isMovedAside_andStoreStartsEmpty() throws IOException {
        Files.writeString(file(), "{ this is not json");
        ExternalPackStore s = store();
        assertTrue(s.list().isEmpty());
        assertNotNull(s.loadProblem());
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("external-sources.json.broken-")));
        }
        assertFalse(Files.exists(file()));
    }

    @Test
    void oneBrokenEntry_isSkipped_othersKept() throws IOException {
        ExternalPackStore s = store();
        ModrinthResolver.Candidate good = candidate("c".repeat(128), "good");
        Entry e = s.upsert(resolution(good), good, "dp/good.zip", false);
        s.approve(e.id(), "admin");
        String json = Files.readString(file());
        // Add an entry whose sha512 is not hex, as a bad API response could once store it.
        json = json.replaceFirst("\"entries\": \\{", "\"entries\": {\n    \"mr-bad\": {\"sha512\": \"not-a-hash\", \"state\": \"APPROVED\"},");
        Files.writeString(file(), json);

        ExternalPackStore reloaded = store();
        assertEquals(1, reloaded.list().size());
        assertEquals(State.APPROVED, reloaded.get(e.id()).orElseThrow().state());
        assertNotNull(reloaded.loadProblem());
        assertTrue(reloaded.loadProblem().contains("mr-bad"), reloaded.loadProblem());
        assertTrue(Files.exists(file()), "the file is kept, not moved aside");
    }

    @Test
    void upsert_refusesAFileWithoutValidSha512() {
        ExternalPackStore s = store();
        ModrinthResolver.Candidate bad = candidate("z".repeat(128), "bad");
        assertThrows(IllegalArgumentException.class, () -> s.upsert(resolution(bad), bad, "dp/bad.zip", true));
        assertTrue(s.list().isEmpty());
    }

    @Test
    void writtenFile_hasVersionAndEntries() throws IOException {
        ExternalPackStore s = store();
        ModrinthResolver.Candidate c = candidate("f".repeat(128), "x");
        s.upsert(resolution(c), c, "dp/x.zip", false);
        String json = Files.readString(file());
        assertTrue(json.contains("\"version\": 1"), json);
        assertTrue(json.contains("\"state\": \"PENDING\""), json);
        assertFalse(Files.exists(dir.resolve("external-sources.json.tmp")));
    }
}
