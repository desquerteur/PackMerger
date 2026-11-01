package sh.pcx.packmerger.merge;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The {@code identical} / {@code warned} flags added for {@code /pm conflicts}. */
class MergeProvenanceIdenticalTest {

    private static MergeProvenance build(MergeProvenance.Builder b) {
        return b.build(List.of("high", "low"), Instant.parse("2026-11-01T12:00:00Z"));
    }

    @Test
    void identicalOverwrite_isFlagged_andRoundTrips() {
        MergeProvenance.Builder b = new MergeProvenance.Builder();
        b.record("assets/a.png", "low", null, false, false);
        b.record("assets/a.png", "high", null, false, true);
        b.record("assets/b.png", "low", null, false, false);
        b.record("assets/b.png", "high", null, false, false);
        MergeProvenance p = build(b);
        assertTrue(p.files().get("assets/a.png").identical());
        assertFalse(p.files().get("assets/b.png").identical());

        MergeProvenance back = MergeProvenance.fromJson(p.toJson());
        assertNotNull(back);
        assertTrue(back.files().get("assets/a.png").identical());
        assertFalse(back.files().get("assets/b.png").identical());
        assertFalse(back.files().get("assets/a.png").isConflict());
        assertTrue(back.files().get("assets/b.png").isConflict());
    }

    @Test
    void singleContributor_isNeitherIdenticalNorConflict() {
        MergeProvenance.Builder b = new MergeProvenance.Builder();
        b.record("assets/a.png", "low", null, false, false);
        MergeProvenance.FileRecord r = build(b).files().get("assets/a.png");
        assertFalse(r.identical());
        assertFalse(r.isConflict());
    }

    @Test
    void warned_roundTrips_andIsAConflict() {
        MergeProvenance.Builder b = new MergeProvenance.Builder();
        b.record("assets/minecraft/items/paper.json", "low", "item-definition", false);
        b.warn("assets/minecraft/items/paper.json", "different dispatch property");
        b.record("assets/minecraft/items/paper.json", "high", "item-definition", true);
        MergeProvenance back = MergeProvenance.fromJson(build(b).toJson());
        MergeProvenance.FileRecord r = back.files().get("assets/minecraft/items/paper.json");
        assertTrue(r.warned());
        assertTrue(r.isConflict());
        assertEquals(List.of("different dispatch property"), r.notes());
    }

    @Test
    void oldSidecarWithoutFlags_readsAsFalse() {
        String old = """
                {"mergedAt":"2026-01-01T00:00:00Z","packOrder":["a","b"],
                 "files":{"assets/x.png":{"winner":"a","contributors":["b","a"],"wasMerged":false}}}""";
        MergeProvenance p = MergeProvenance.fromJson(old);
        assertNotNull(p);
        MergeProvenance.FileRecord r = p.files().get("assets/x.png");
        assertFalse(r.identical());
        assertFalse(r.warned());
        assertTrue(r.isConflict());
    }

    @Test
    void oldConstructors_stillWork() {
        MergeProvenance.FileRecord five = new MergeProvenance.FileRecord("p", "a", List.of("a"), null, false);
        MergeProvenance.FileRecord six = new MergeProvenance.FileRecord("p", "a", List.of("a"), null, false, List.of("n"));
        assertFalse(five.identical());
        assertFalse(six.warned());
        assertEquals(List.of("n"), six.notes());
    }

    @Test
    void flagsOff_areNotWrittenToJson() {
        MergeProvenance.Builder b = new MergeProvenance.Builder();
        b.record("assets/a.png", "low", null, false, false);
        String json = build(b).toJson();
        assertFalse(json.contains("identical"));
        assertFalse(json.contains("warned"));
    }
}
