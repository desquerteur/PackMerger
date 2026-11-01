package sh.pcx.packmerger.merge;

import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.config.ConfigManager.DatapackPosition;
import sh.pcx.packmerger.config.ConfigManager.ServerPackConfig;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** {@link PackMergeEngine#orderPacks}: priority list, unlisted packs and datapack aliases. */
class PackMergeEngineOrderTest {

    private static final List<String> AVAILABLE = List.of(
            "zeta.zip", "alpha.zip", "oraxen", "dp-terralith", "dp-incendium", "main.zip");
    private static final Set<String> DATAPACKS = Set.of("dp-terralith", "dp-incendium");

    @Test
    void bottom_unlistedDatapacksAfterEveryOtherUnlistedPack() {
        List<String> order = PackMergeEngine.orderPacks(AVAILABLE, List.of("main.zip", "oraxen"), null,
                DATAPACKS, DatapackPosition.BOTTOM);
        assertEquals(List.of("main.zip", "oraxen", "alpha.zip", "zeta.zip", "dp-incendium", "dp-terralith"), order);
    }

    @Test
    void top_unlistedDatapacksBeforeTheFirstListedPack() {
        List<String> order = PackMergeEngine.orderPacks(AVAILABLE, List.of("main.zip", "oraxen"), null,
                DATAPACKS, DatapackPosition.TOP);
        assertEquals(List.of("dp-incendium", "dp-terralith", "main.zip", "oraxen", "alpha.zip", "zeta.zip"), order);
    }

    @Test
    void listedDatapackAlias_keepsItsListedPlace() {
        List<String> order = PackMergeEngine.orderPacks(AVAILABLE, List.of("dp-terralith", "main.zip"), null,
                DATAPACKS, DatapackPosition.BOTTOM);
        assertEquals(List.of("dp-terralith", "main.zip", "alpha.zip", "oraxen", "zeta.zip", "dp-incendium"), order);
    }

    @Test
    void serverExcludesAndAdditional_stillApply() {
        ServerPackConfig server = new ServerPackConfig(List.of("zeta.zip"), List.of("dp-incendium", "alpha.zip"));
        List<String> order = PackMergeEngine.orderPacks(AVAILABLE, List.of("main.zip"), server,
                DATAPACKS, DatapackPosition.BOTTOM);
        // "bottom" datapack packs sit below the server-specific additional packs too.
        assertEquals(List.of("main.zip", "oraxen", "zeta.zip", "dp-terralith"), order);
    }

    @Test
    void top_datapacksStayAboveServerAdditional() {
        ServerPackConfig server = new ServerPackConfig(List.of("zeta.zip"), List.of());
        List<String> order = PackMergeEngine.orderPacks(AVAILABLE, List.of("main.zip"), server,
                DATAPACKS, DatapackPosition.TOP);
        assertEquals(List.of("dp-incendium", "dp-terralith", "main.zip", "alpha.zip", "oraxen", "zeta.zip"), order);
    }

    @Test
    void noDatapacks_behavesAsBefore() {
        List<String> order = PackMergeEngine.orderPacks(List.of("b.zip", "a.zip", "c.zip"), List.of("c.zip"), null,
                Set.of(), DatapackPosition.TOP);
        assertEquals(List.of("c.zip", "a.zip", "b.zip"), order);
    }
}
