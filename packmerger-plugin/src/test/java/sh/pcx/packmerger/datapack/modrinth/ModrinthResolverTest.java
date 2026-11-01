package sh.pcx.packmerger.datapack.modrinth;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link ModrinthResolver} against a stub transport serving JSON fixtures. */
class ModrinthResolverTest {

    private static final String SHA512 = "a".repeat(128);

    /** Path (with query) prefix → JSON body; anything else is a 404. */
    private final Map<String, String> routes = new LinkedHashMap<>();
    private final List<String> requested = new ArrayList<>();

    private ModrinthClient client() {
        ModrinthClient.Transport t = (method, uri, body, headers, timeout) -> {
            String pq = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            requested.add(pq);
            for (Map.Entry<String, String> e : routes.entrySet()) {
                if (pq.startsWith(e.getKey())) {
                    return new ModrinthClient.Transport.Response(200, Map.of(),
                            e.getValue().getBytes(StandardCharsets.UTF_8));
                }
            }
            return new ModrinthClient.Transport.Response(404, Map.of(), new byte[0]);
        };
        return new ModrinthClient(URI.create("https://api.example/v2"), "test", t, Duration.ofSeconds(5), 1 << 20);
    }

    private ModrinthResolver resolver(boolean includeOptional) {
        return new ModrinthResolver(client(), includeOptional);
    }

    private static String file(String name, String fileType, boolean primary) {
        return "{\"url\":\"https://cdn.modrinth.com/data/x/" + name + "\",\"filename\":\"" + name + "\",\"primary\":"
                + primary + ",\"size\":1234,\"hashes\":{\"sha1\":\"" + "b".repeat(40) + "\",\"sha512\":\"" + SHA512
                + "\"}" + (fileType == null ? "" : ",\"file_type\":\"" + fileType + "\"") + "}";
    }

    private static String version(String id, String project, String type, String date, String gameVersions,
                                  String deps, String files) {
        return "{\"id\":\"" + id + "\",\"project_id\":\"" + project + "\",\"version_number\":\"" + id
                + "-num\",\"version_type\":\"" + type + "\",\"date_published\":\"" + date
                + "\",\"game_versions\":" + gameVersions + ",\"loaders\":[\"minecraft\"],\"dependencies\":" + deps
                + ",\"files\":" + files + "}";
    }

    private static ModrinthVersion dp(String deps, String files) {
        return ModrinthVersion.parse(JsonParser.parseString(
                version("DPV", "dpproj", "release", "2026-10-01T00:00:00Z", "[\"26.2\"]", deps, files)));
    }

    /** Serves a project (and, unless already routed, an empty version list; that route must match first). */
    private void project(String id, String slug, String type) {
        routes.putIfAbsent("/v2/project/" + id + "/version", "[]");
        routes.put("/v2/project/" + id, "{\"id\":\"" + id + "\",\"slug\":\"" + slug + "\",\"title\":\"" + slug
                + "\",\"project_type\":\"" + type + "\"}");
    }

    @Test
    void sameVersionRequiredResourcePackFile() throws Exception {
        project("dpproj", "terralith", "datapack");
        ModrinthVersion v = dp("[]", "[" + file("dp.zip", null, true) + "," + file("rp.zip", "required-resource-pack", false) + "]");
        ModrinthResolver.Resolution r = resolver(false).resolve("sha", v, "26.2");
        assertEquals(1, r.candidates().size());
        ModrinthResolver.Candidate c = r.candidates().get(0);
        assertEquals("rp.zip", c.file().filename());
        assertEquals("file_type required-resource-pack", c.reason());
        assertEquals("terralith", c.projectSlug());
        assertTrue(r.problems().isEmpty());
    }

    @Test
    void optionalResourcePackFile_onlyWithIncludeOptional() throws Exception {
        project("dpproj", "terralith", "datapack");
        ModrinthVersion v = dp("[]", "[" + file("rp.zip", "optional-resource-pack", false) + "]");
        assertTrue(resolver(false).resolve("sha", v, "26.2").candidates().isEmpty());
        assertEquals(1, resolver(true).resolve("sha", v, "26.2").candidates().size());
    }

    @Test
    void dependencyWithVersionId() throws Exception {
        project("rpproj", "dnt-rp", "resourcepack");
        routes.put("/v2/version/RPV", version("RPV", "rpproj", "release", "2026-09-01T00:00:00Z", "[\"26.2\"]", "[]",
                "[" + file("DnT-RP.zip", null, true) + "]"));
        ModrinthVersion v = dp("[{\"version_id\":\"RPV\",\"project_id\":\"rpproj\",\"dependency_type\":\"required\"}]", "[]");
        ModrinthResolver.Resolution r = resolver(false).resolve("sha", v, "26.2");
        assertEquals(1, r.candidates().size());
        assertEquals("RPV", r.candidates().get(0).versionId());
        assertEquals("dependency required", r.candidates().get(0).reason());
        assertEquals("dnt-rp", r.candidates().get(0).projectSlug());
    }

    @Test
    void dependencyWithProjectOnly_picksNewestRelease() throws Exception {
        routes.put("/v2/project/rpproj/version", "["
                + version("OLD", "rpproj", "release", "2026-01-01T00:00:00Z", "[\"26.2\"]", "[]", "[" + file("old.zip", null, true) + "]") + ","
                + version("NEW", "rpproj", "release", "2026-06-01T00:00:00Z", "[\"26.2\"]", "[]", "[" + file("new.zip", null, true) + "]") + ","
                + version("BETA", "rpproj", "beta", "2026-09-01T00:00:00Z", "[\"26.2\"]", "[]", "[" + file("beta.zip", null, true) + "]") + "]");
        project("rpproj", "dnt-rp", "resourcepack");
        ModrinthVersion v = dp("[{\"project_id\":\"rpproj\",\"dependency_type\":\"required\"}]", "[]");
        ModrinthResolver.Resolution r = resolver(false).resolve("sha", v, "26.2");
        assertEquals("NEW", r.candidates().get(0).versionId());
        assertTrue(requested.stream().anyMatch(q -> q.contains("game_versions=%5B%2226.2%22%5D")), requested.toString());
        assertTrue(requested.stream().anyMatch(q -> q.contains("loaders=%5B%22minecraft%22%5D")), requested.toString());
    }

    @Test
    void betaFallback_alphaNever() throws Exception {
        routes.put("/v2/project/rpproj/version", "["
                + version("A", "rpproj", "alpha", "2026-10-01T00:00:00Z", "[\"26.2\"]", "[]", "[" + file("a.zip", null, true) + "]") + ","
                + version("B", "rpproj", "beta", "2026-05-01T00:00:00Z", "[\"26.2\"]", "[]", "[" + file("b.zip", null, true) + "]") + "]");
        project("rpproj", "x", "resourcepack");
        ModrinthVersion v = dp("[{\"project_id\":\"rpproj\",\"dependency_type\":\"required\"}]", "[]");
        assertEquals("B", resolver(false).resolve("sha", v, "26.2").candidates().get(0).versionId());

        assertNull(ModrinthResolver.pickVersion(List.of(ModrinthVersion.parse(JsonParser.parseString(
                version("A", "p", "alpha", "2026-10-01T00:00:00Z", "[]", "[]", "[]"))))));
    }

    @Test
    void dependencyOnAModProject_isIgnored() throws Exception {
        project("modproj", "some-mod", "mod");
        ModrinthVersion v = dp("[{\"project_id\":\"modproj\",\"dependency_type\":\"required\"}]", "[]");
        ModrinthResolver.Resolution r = resolver(false).resolve("sha", v, "26.2");
        assertTrue(r.candidates().isEmpty());
        assertTrue(r.problems().isEmpty());
    }

    @Test
    void fileNameOnlyDependency_isUnsupported() throws Exception {
        ModrinthVersion v = dp("[{\"file_name\":\"Extra RP.zip\",\"dependency_type\":\"required\"}]", "[]");
        ModrinthResolver.Resolution r = resolver(false).resolve("sha", v, "26.2");
        assertEquals(1, r.problems().size());
        assertTrue(r.problems().get(0).startsWith(ModrinthResolver.UNSUPPORTED), r.problems().toString());
    }

    @Test
    void noVersionForMinecraft_isMissingDependency() throws Exception {
        project("rpproj", "dnt-rp", "resourcepack");  // the version list is empty
        ModrinthVersion v = dp("[{\"project_id\":\"rpproj\",\"dependency_type\":\"required\"}]", "[]");
        ModrinthResolver.Resolution r = resolver(false).resolve("sha", v, "26.2.1");
        assertTrue(r.candidates().isEmpty());
        assertEquals(1, r.problems().size());
        assertTrue(r.problems().get(0).startsWith(ModrinthResolver.MISSING), r.problems().toString());
        // Exact version first, then the major.minor fallback.
        assertTrue(requested.stream().anyMatch(q -> q.contains("%2226.2.1%22")));
        assertTrue(requested.stream().anyMatch(q -> q.contains("%2226.2%22%5D")));
    }

    @Test
    void pinnedVersionNotListingMinecraft_isMissingDependency() throws Exception {
        project("rpproj", "dnt-rp", "resourcepack");
        routes.put("/v2/version/RPV", version("RPV", "rpproj", "release", "2026-09-01T00:00:00Z", "[\"26.1\"]", "[]",
                "[" + file("rp.zip", null, true) + "]"));
        ModrinthVersion v = dp("[{\"version_id\":\"RPV\",\"dependency_type\":\"required\"}]", "[]");
        ModrinthResolver.Resolution r = resolver(false).resolve("sha", v, "26.2");
        assertTrue(r.candidates().isEmpty());
        assertTrue(r.problems().get(0).contains("no version for 26.2"));
    }

    @Test
    void incompatibleAndEmbedded_areIgnored() throws Exception {
        ModrinthVersion v = dp("[{\"project_id\":\"a\",\"dependency_type\":\"incompatible\"},"
                + "{\"project_id\":\"b\",\"dependency_type\":\"embedded\"},"
                + "{\"project_id\":\"c\",\"dependency_type\":\"optional\"}]", "[]");
        ModrinthResolver.Resolution r = resolver(false).resolve("sha", v, "26.2");
        assertTrue(r.candidates().isEmpty());
        assertTrue(r.problems().isEmpty());
        assertTrue(requested.isEmpty(), "no request for ignored dependencies: " + requested);
    }

    @Test
    void noResourcePackDeclared_isEmpty() throws Exception {
        ModrinthResolver.Resolution r = resolver(false).resolve("sha", dp("[]", "[" + file("dp.zip", null, true) + "]"), "26.2");
        assertTrue(r.onModrinth());
        assertTrue(r.candidates().isEmpty());
        assertTrue(r.problems().isEmpty());
        assertFalse(ModrinthResolver.Resolution.notOnModrinth("x").onModrinth());
    }

    @Test
    void majorMinorAndSupports() {
        assertEquals("26.2", ModrinthResolver.majorMinor("26.2.1"));
        assertEquals("26.2", ModrinthResolver.majorMinor("26.2"));
        ModrinthVersion v = ModrinthVersion.parse(JsonParser.parseString(
                version("X", "p", "release", "2026-01-01T00:00:00Z", "[\"26.2\"]", "[]", "[]")));
        assertTrue(ModrinthResolver.supports(v, "26.2.1"));
        assertFalse(ModrinthResolver.supports(v, "26.3"));
    }
}
