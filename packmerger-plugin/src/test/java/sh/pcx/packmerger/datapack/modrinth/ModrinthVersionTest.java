package sh.pcx.packmerger.datapack.modrinth;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Hash validation when parsing Modrinth versions (security review L1). */
class ModrinthVersionTest {

    private static ModrinthVersion.VersionFile parseFile(String sha1, String sha512) {
        String json = "{\"id\":\"V\",\"files\":[{\"url\":\"https://cdn.modrinth.com/a.zip\",\"filename\":\"a.zip\","
                + "\"primary\":true,\"size\":10,\"hashes\":{\"sha1\":\"" + sha1 + "\",\"sha512\":\"" + sha512 + "\"}}]}";
        return ModrinthVersion.parse(JsonParser.parseString(json)).files().get(0);
    }

    @Test
    void validHashes_areKept_inLowercase() {
        ModrinthVersion.VersionFile f = parseFile("A".repeat(40), "B".repeat(128));
        assertEquals("a".repeat(40), f.sha1());
        assertEquals("b".repeat(128), f.sha512());
    }

    @Test
    void invalidHashes_becomeNull() {
        ModrinthVersion.VersionFile f = parseFile("xyz", "../../etc/passwd" + "0".repeat(112));
        assertNull(f.sha1());
        assertNull(f.sha512());
        assertNull(parseFile("a".repeat(40), "a".repeat(127)).sha512());
        assertNull(parseFile("a".repeat(40), "g".repeat(128)).sha512());
    }
}
