package sh.pcx.packmerger.datapack.modrinth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** {@link ModrinthClient} + {@link JdkTransport} against a stub HTTP server on 127.0.0.1. */
class ModrinthClientTest {

    private HttpServer server;
    private final AtomicReference<Handler> handler = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAgent = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    interface Handler {
        void handle(HttpExchange ex) throws IOException;
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAgent.set(ex.getRequestHeaders().getFirst("User-Agent"));
            lastPath.set(ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? ""
                    : "?" + ex.getRequestURI().getRawQuery()));
            try {
                handler.get().handle(ex);
            } finally {
                ex.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ModrinthClient client(Duration timeout, int max) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v2");
        return new ModrinthClient(base, "pcxgg/PackMerger/test (datapack-discovery)", new JdkTransport(max), timeout, max);
    }

    private ModrinthClient client() {
        return client(Duration.ofSeconds(5), 2 * 1024 * 1024);
    }

    private static void reply(HttpExchange ex, int status, String body, Map<String, String> headers) throws IOException {
        headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        }
    }

    private static final String VERSION = "{\"id\":\"V1\",\"project_id\":\"P1\",\"version_number\":\"1.0\","
            + "\"version_type\":\"release\",\"game_versions\":[\"26.2\"],\"loaders\":[\"datapack\"],"
            + "\"dependencies\":[{\"project_id\":\"RP\",\"dependency_type\":\"required\"}],\"files\":[]}";

    @Test
    void bulkLookup_postsHashesWithUserAgent() throws Exception {
        handler.set(ex -> reply(ex, 200, "{\"abc\":" + VERSION + "}", Map.of("X-Ratelimit-Remaining", "250")));
        Map<String, ModrinthVersion> found = client().versionsByHashes(List.of("ABC", "def"));
        assertEquals("/v2/version_files", lastPath.get());
        JsonObject body = JsonParser.parseString(lastBody.get()).getAsJsonObject();
        assertEquals("sha1", body.get("algorithm").getAsString());
        assertEquals(2, body.getAsJsonArray("hashes").size());
        assertEquals("abc", body.getAsJsonArray("hashes").get(0).getAsString());
        assertEquals("pcxgg/PackMerger/test (datapack-discovery)", lastAgent.get());
        assertEquals(1, found.size());
        ModrinthVersion v = found.get("abc");
        assertEquals("P1", v.projectId());
        assertEquals("RP", v.dependencies().get(0).projectId());
    }

    @Test
    void notFound_isEmptyOrNull() throws Exception {
        handler.set(ex -> reply(ex, 404, "", Map.of()));
        ModrinthClient c = client();
        assertNull(c.versionByHash("abc"));
        assertNull(c.project("nope"));
        assertTrue(c.projectVersions("nope", "26.2", "minecraft").isEmpty());
    }

    @Test
    void notOnModrinth_bulkReturnsEmptyObject() throws Exception {
        handler.set(ex -> reply(ex, 200, "{}", Map.of()));
        assertTrue(client().versionsByHashes(List.of("abc")).isEmpty());
    }

    private ModrinthClient client(java.util.concurrent.atomic.AtomicLong clock) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v2");
        return new ModrinthClient(base, "ua", new JdkTransport(1 << 20), Duration.ofSeconds(5), 1 << 20, clock::get);
    }

    @Test
    void http429_isRateLimited_andStopsFurtherRequests() {
        handler.set(ex -> reply(ex, 429, "", Map.of("X-Ratelimit-Reset", "90")));
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000);
        ModrinthClient c = client(clock);
        ModrinthException e = assertThrows(ModrinthException.class, () -> c.project("x"));
        assertEquals(ModrinthException.Kind.RATE_LIMITED, e.kind());
        lastPath.set(null);
        assertThrows(ModrinthException.class, () -> c.project("y"));
        assertNull(lastPath.get(), "no request after a 429");
        // A new scan does not lift the pause before Modrinth's reset time (security review L6).
        c.resetBudget();
        assertEquals(90, c.blockedForSeconds());
        assertThrows(ModrinthException.class, () -> c.project("y"));
        assertNull(lastPath.get(), "no request while rate-limited, even in the next scan");
        clock.addAndGet(91_000);
        c.resetBudget();
        handler.set(ex -> reply(ex, 404, "", Map.of()));
        assertDoesNotThrow(() -> c.project("z"));
    }

    @Test
    void http429_withoutResetHeader_pausesAtLeastAMinute() {
        handler.set(ex -> reply(ex, 429, "", Map.of()));
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(0);
        ModrinthClient c = client(clock);
        assertThrows(ModrinthException.class, () -> c.project("x"));
        clock.addAndGet(30_000);
        c.resetBudget();
        assertEquals(30, c.blockedForSeconds());
        clock.addAndGet(31_000);
        c.resetBudget();
        assertEquals(0, c.blockedForSeconds());
    }

    @Test
    void scanTimeBudget_stopsRequests() throws Exception {
        handler.set(ex -> reply(ex, 404, "", Map.of()));
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(0);
        ModrinthClient c = client(clock);
        c.resetBudget();
        c.project("a");
        clock.addAndGet(ModrinthClient.SCAN_TIME_BUDGET.toMillis() + 1);
        ModrinthException e = assertThrows(ModrinthException.class, () -> c.project("b"));
        assertEquals(ModrinthException.Kind.RATE_LIMITED, e.kind());
        c.resetBudget();
        assertDoesNotThrow(() -> c.project("c"));
    }

    @Test
    void stalledBody_timesOut() throws Exception {
        // Headers arrive at once, the body never ends: the JDK request timeout does not cover
        // that, the transport's own deadline does (security review M1).
        handler.set(ex -> {
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().write("{\"id\":".getBytes(StandardCharsets.UTF_8));
            ex.getResponseBody().flush();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        long start = System.nanoTime();
        ModrinthException e = assertThrows(ModrinthException.class,
                () -> client(Duration.ofMillis(700), 1 << 20).project("x"));
        assertEquals(ModrinthException.Kind.NETWORK, e.kind());
        assertTrue(System.nanoTime() - start < 4_000_000_000L, "gave up at the deadline, not when the server did");
    }

    @Test
    void readBody_returnsBeforeDeadline() throws Exception {
        byte[] b = JdkTransport.readBody(new java.io.ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)),
                3, System.nanoTime() + 1_000_000_000L);
        assertEquals("hel", new String(b, StandardCharsets.UTF_8));
    }

    @Test
    void lowRemaining_usesThisResponseThenStops() throws Exception {
        handler.set(ex -> reply(ex, 200, "{\"id\":\"P\",\"slug\":\"s\",\"project_type\":\"resourcepack\"}",
                Map.of("X-Ratelimit-Remaining", "2", "X-Ratelimit-Limit", "300")));
        ModrinthClient c = client();
        assertEquals("s", c.project("P").slug());
        assertEquals(2, c.lastRateLimit().remaining());
        assertEquals(300, c.lastRateLimit().limit());
        ModrinthException e = assertThrows(ModrinthException.class, () -> c.project("P"));
        assertEquals(ModrinthException.Kind.RATE_LIMITED, e.kind());
    }

    @Test
    void serverError() {
        handler.set(ex -> reply(ex, 500, "oops", Map.of()));
        ModrinthException e = assertThrows(ModrinthException.class, () -> client().project("x"));
        assertEquals(ModrinthException.Kind.SERVER_ERROR, e.kind());
    }

    @Test
    void oversizedBody_isRejected() {
        String big = "\"" + "x".repeat(3 * 1024 * 1024) + "\"";
        handler.set(ex -> reply(ex, 200, big, Map.of()));
        ModrinthException e = assertThrows(ModrinthException.class, () -> client().project("x"));
        assertEquals(ModrinthException.Kind.BAD_RESPONSE, e.kind());
    }

    @Test
    void timeout_isNetworkError() {
        handler.set(ex -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            reply(ex, 200, "{}", Map.of());
        });
        ModrinthException e = assertThrows(ModrinthException.class,
                () -> client(Duration.ofMillis(500), 1 << 20).project("x"));
        assertEquals(ModrinthException.Kind.NETWORK, e.kind());
    }

    @Test
    void malformedJson_isBadResponse() {
        handler.set(ex -> reply(ex, 200, "{not json", Map.of()));
        ModrinthException e = assertThrows(ModrinthException.class, () -> client().versionsByHashes(List.of("a")));
        assertEquals(ModrinthException.Kind.BAD_RESPONSE, e.kind());
    }

    @Test
    void requestBudget_isEnforced() throws Exception {
        handler.set(ex -> reply(ex, 404, "", Map.of()));
        ModrinthClient c = client();
        for (int i = 0; i < ModrinthClient.REQUEST_BUDGET; i++) c.project("p" + i);
        ModrinthException e = assertThrows(ModrinthException.class, () -> c.project("one-more"));
        assertEquals(ModrinthException.Kind.RATE_LIMITED, e.kind());
    }

    @Test
    void transport_refusesPlainHttpToOtherHosts() {
        ModrinthClient c = new ModrinthClient(URI.create("http://example.com/v2"), "ua", new JdkTransport(1024),
                Duration.ofSeconds(2), 1024);
        ModrinthException e = assertThrows(ModrinthException.class, () -> c.project("x"));
        assertEquals(ModrinthException.Kind.NETWORK, e.kind());
        assertTrue(e.getMessage().contains("non-HTTPS"));
    }
}
