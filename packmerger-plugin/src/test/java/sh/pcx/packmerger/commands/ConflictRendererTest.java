package sh.pcx.packmerger.commands;

import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.merge.MergeProvenance;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConflictRendererTest {

    private static final Set<String> DP = Set.of("dp-terralith", "dp-incendium");

    /** main.zip (high) over oraxen over dp-terralith over dp-incendium (low). */
    private static MergeProvenance sample() {
        MergeProvenance.Builder b = new MergeProvenance.Builder();
        // identical overwrite: same bytes in both packs → not a conflict
        b.record("assets/minecraft/textures/block/dirt.png", "dp-terralith", null, false, false);
        b.record("assets/minecraft/textures/block/dirt.png", "main.zip", null, false, true);
        // differing texture → conflict, datapack lost
        b.record("assets/minecraft/textures/block/stone.png", "dp-terralith", null, false, false);
        b.record("assets/minecraft/textures/block/stone.png", "main.zip", null, false, false);
        // cleanly merged JSON (lang union) → not a conflict
        b.record("assets/minecraft/lang/en_us.json", "dp-incendium", "lang", false);
        b.record("assets/minecraft/lang/en_us.json", "oraxen", "lang", true);
        // strategy warning → conflict
        b.record("assets/minecraft/items/paper.json", "dp-incendium", "item-definition", false);
        b.warn("assets/minecraft/items/paper.json", "different dispatch property");
        b.record("assets/minecraft/items/paper.json", "oraxen", "item-definition", true);
        // informational note on a clean merge → not a conflict
        b.record("pack.mcmeta", "dp-incendium", "pack-mcmeta", false);
        b.note("pack.mcmeta", "format range widened");
        b.record("pack.mcmeta", "main.zip", "pack-mcmeta", true);
        // single contributor
        b.record("assets/terra/a.png", "dp-terralith", null, false, false);
        return b.build(List.of("main.zip", "oraxen", "dp-terralith", "dp-incendium"), Instant.now());
    }

    @Test
    void onlyRealConflictsAreListed() {
        List<ConflictRenderer.Conflict> c = ConflictRenderer.conflicts(sample(), DP, null);
        assertEquals(List.of("assets/minecraft/items/paper.json", "assets/minecraft/textures/block/stone.png"),
                c.stream().map(ConflictRenderer.Conflict::path).toList());
    }

    @Test
    void datapackAliasesAreTagged() {
        ConflictRenderer.Conflict stone = ConflictRenderer.conflicts(sample(), DP, null).get(1);
        assertEquals("main.zip", stone.winner());
        assertEquals(List.of("[dp] dp-terralith"), stone.losers());
        assertNull(stone.note());
    }

    @Test
    void strategyWarningIsShown() {
        ConflictRenderer.Conflict paper = ConflictRenderer.conflicts(sample(), DP, null).get(0);
        assertEquals("oraxen", paper.winner());
        assertEquals(List.of("[dp] dp-incendium"), paper.losers());
        assertEquals("different dispatch property", paper.note());
    }

    @Test
    void packFilter() {
        assertEquals(1, ConflictRenderer.conflicts(sample(), DP, "dp-terralith").size());
        assertEquals(0, ConflictRenderer.conflicts(sample(), DP, "unknown").size());
    }

    @Test
    void noProvenance_noConflicts() {
        assertTrue(ConflictRenderer.conflicts(null, DP, null).isEmpty());
    }

    @Test
    void datapackSummary_onlyWhenADatapackIsInvolved() {
        String s = ConflictRenderer.datapackSummary(sample(), DP);
        assertEquals("2 file(s) differ between packs; dp-incendium lost 1, dp-terralith lost 1 (see /pm conflicts)", s);
        assertNull(ConflictRenderer.datapackSummary(sample(), Set.of()));

        MergeProvenance.Builder b = new MergeProvenance.Builder();
        b.record("assets/x.png", "a.zip", null, false, false);
        b.record("assets/x.png", "b.zip", null, false, false);
        assertNull(ConflictRenderer.datapackSummary(b.build(List.of("b.zip", "a.zip"), Instant.now()), DP));
    }
}
