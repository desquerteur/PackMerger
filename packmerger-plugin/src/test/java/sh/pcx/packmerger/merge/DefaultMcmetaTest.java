package sh.pcx.packmerger.merge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The pack.mcmeta the engine generates when no merged pack has one. */
class DefaultMcmetaTest {

    private static JsonObject pack(String mcVersion) {
        return JsonParser.parseString(PackMergeEngine.defaultMcmeta(mcVersion)).getAsJsonObject().getAsJsonObject("pack");
    }

    @Test
    void usesTheRunningServersFormat() {
        JsonObject p = pack("26.2");
        assertEquals(88, p.get("pack_format").getAsInt());
        assertEquals(88, p.get("min_format").getAsInt());
        assertEquals(88, p.get("max_format").getAsInt());
        assertEquals("Merged resource pack by PackMerger", p.get("description").getAsString());
        assertEquals(97, pack("26.3.1").get("pack_format").getAsInt());
    }

    @Test
    void legacyFormat_hasOnlyPackFormat() {
        JsonObject p = pack("1.21.4");
        assertEquals(46, p.get("pack_format").getAsInt());
        assertFalse(p.has("min_format"));
        assertFalse(p.has("max_format"));
    }

    @Test
    void unknownVersion_fallsBackToNewestKnownFormat() {
        assertEquals(PackFormatRegistry.newestKnown(), pack("27.1").get("pack_format").getAsInt());
        assertEquals(PackFormatRegistry.newestKnown(), pack(null).get("pack_format").getAsInt());
    }

    @Test
    void generatedMcmeta_passesTheValidatorsFormatCheck() {
        // A 26.2 server must not flag its own generated mcmeta as drifted.
        assertEquals(PackFormatRegistry.Drift.MATCH,
                PackFormatRegistry.classify(pack("26.2").get("pack_format").getAsInt(), null, "26.2"));
    }
}
