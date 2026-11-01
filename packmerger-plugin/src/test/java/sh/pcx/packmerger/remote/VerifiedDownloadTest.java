package sh.pcx.packmerger.remote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** {@link VerifiedDownload} against a stub HTTP server (loopback HTTP allowed through the test hook). */
class VerifiedDownloadTest {

    @TempDir Path tmp;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private static final byte[] PACK = new byte[50_000];

    static {
        new Random(7).nextBytes(PACK);
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pack.zip", ex -> send(ex, 200, PACK, true));
        server.createContext("/chunked.zip", ex -> send(ex, 200, PACK, false));
        server.createContext("/redirect", ex -> redirect(ex, base() + "/pack.zip"));
        server.createContext("/to-localhost", ex -> redirect(ex,
                "http://localhost:" + server.getAddress().getPort() + "/pack.zip"));
        server.createContext("/to-http-elsewhere", ex -> redirect(ex, "http://cdn.modrinth.com/pack.zip"));
        server.createContext("/loop", ex -> redirect(ex, base() + "/loop"));
        server.createContext("/missing", ex -> send(ex, 404, new byte[0], true));
        server.createContext("/slow", ex -> {
            requests.incrementAndGet();
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(PACK, 0, 10);
                out.flush();
                Thread.sleep(4000);
            } catch (InterruptedException | IOException ignored) {
                // client gave up
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void send(HttpExchange ex, int status, byte[] body, boolean withLength) throws IOException {
        requests.incrementAndGet();
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : (withLength ? body.length : 0));
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
        ex.close();
    }

    private void redirect(HttpExchange ex, String location) throws IOException {
        requests.incrementAndGet();
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private static String hex(String alg, byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(alg).digest(b));
    }

    private VerifiedSpec spec(String path, String sha512, long maxBytes, long expectedSize) throws Exception {
        return new VerifiedSpec("mr-test", URI.create(base() + path), Set.of("127.0.0.1"), maxBytes,
                Duration.ofSeconds(2), sha512, hex("SHA-1", PACK), expectedSize, "test-agent");
    }

    private VerifiedDownload download() {
        VerifiedDownload d = new VerifiedDownload();
        d.allowLoopbackHttp = true;
        return d;
    }

    @Test
    void happyPath_verifiedAndMovedIntoPlace() throws Exception {
        Path target = tmp.resolve("cache/x.zip");
        FetchResult r = download().fetch(spec("/pack.zip", hex("SHA-512", PACK), 1 << 20, PACK.length), target);
        assertEquals(FetchResult.Status.FETCHED, r.status(), r.detail());
        assertArrayEquals(PACK, Files.readAllBytes(target));
        assertFalse(Files.exists(tmp.resolve("cache/x.zip.part")));
    }

    @Test
    void sha512Mismatch_leavesNoFile() throws Exception {
        Path target = tmp.resolve("x.zip");
        FetchResult r = download().fetch(spec("/pack.zip", "0".repeat(128), 1 << 20, -1), target);
        assertEquals(FetchResult.Status.ERROR_NO_CACHE, r.status());
        assertTrue(r.detail().contains("hash mismatch"), r.detail());
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(tmp.resolve("x.zip.part")));
    }

    @Test
    void sizeMismatch_isRejected() throws Exception {
        FetchResult r = download().fetch(spec("/pack.zip", hex("SHA-512", PACK), 1 << 20, PACK.length + 1),
                tmp.resolve("x.zip"));
        assertEquals(FetchResult.Status.ERROR_NO_CACHE, r.status());
        assertTrue(r.detail().contains("size mismatch"), r.detail());
    }

    @Test
    void contentLengthOverCap_refusedBeforeReading() throws Exception {
        FetchResult r = download().fetch(spec("/pack.zip", hex("SHA-512", PACK), 1000, -1), tmp.resolve("x.zip"));
        assertEquals(FetchResult.Status.ERROR_NO_CACHE, r.status());
        assertTrue(r.detail().contains("Content-Length"), r.detail());
        assertFalse(Files.exists(tmp.resolve("x.zip")));
    }

    @Test
    void streamedBodyOverCap_withoutContentLength_isAborted() throws Exception {
        FetchResult r = download().fetch(spec("/chunked.zip", hex("SHA-512", PACK), 1000, -1), tmp.resolve("x.zip"));
        assertEquals(FetchResult.Status.ERROR_NO_CACHE, r.status());
        assertTrue(r.detail().contains("exceeds the limit"), r.detail());
        assertFalse(Files.exists(tmp.resolve("x.zip")));
    }

    @Test
    void redirectToAllowedHost_isFollowed() throws Exception {
        FetchResult r = download().fetch(spec("/redirect", hex("SHA-512", PACK), 1 << 20, -1), tmp.resolve("x.zip"));
        assertEquals(FetchResult.Status.FETCHED, r.status(), r.detail());
    }

    @Test
    void redirectToHostNotAllowed_isRefused() throws Exception {
        FetchResult r = download().fetch(spec("/to-localhost", hex("SHA-512", PACK), 1 << 20, -1), tmp.resolve("x.zip"));
        assertEquals(FetchResult.Status.ERROR_NO_CACHE, r.status());
        assertTrue(r.detail().contains("not in allowed-hosts"), r.detail());
        assertEquals(1, requests.get(), "the disallowed hop is never requested");
    }

    @Test
    void redirectToPlainHttp_isRefused() throws Exception {
        VerifiedSpec s = new VerifiedSpec("mr-test", URI.create(base() + "/to-http-elsewhere"),
                Set.of("127.0.0.1", "cdn.modrinth.com"), 1 << 20, Duration.ofSeconds(2),
                hex("SHA-512", PACK), null, -1, "ua");
        FetchResult r = download().fetch(s, tmp.resolve("x.zip"));
        assertEquals(FetchResult.Status.ERROR_NO_CACHE, r.status());
        assertTrue(r.detail().contains("non-HTTPS"), r.detail());
    }

    @Test
    void tooManyRedirects() throws Exception {
        FetchResult r = download().fetch(spec("/loop", hex("SHA-512", PACK), 1 << 20, -1), tmp.resolve("x.zip"));
        assertEquals(FetchResult.Status.ERROR_NO_CACHE, r.status());
        assertTrue(r.detail().contains("redirects"), r.detail());
        assertEquals(VerifiedDownload.MAX_REDIRECTS + 1, requests.get());
    }

    @Test
    void http404_isAnError() throws Exception {
        FetchResult r = download().fetch(spec("/missing", hex("SHA-512", PACK), 1 << 20, -1), tmp.resolve("x.zip"));
        assertEquals(FetchResult.Status.ERROR_NO_CACHE, r.status());
        assertTrue(r.detail().contains("404"), r.detail());
    }

    @Test
    void slowBody_timesOut() throws Exception {
        FetchResult r = download().fetch(spec("/slow", hex("SHA-512", PACK), 1 << 20, -1), tmp.resolve("x.zip"));
        assertEquals(FetchResult.Status.ERROR_NO_CACHE, r.status());
        assertTrue(r.detail().contains("timed out"), r.detail());
        assertFalse(Files.exists(tmp.resolve("x.zip")));
    }

    @Test
    void alreadyCached_noRequest() throws Exception {
        Path target = tmp.resolve("x.zip");
        Files.write(target, PACK);
        FetchResult r = download().fetch(spec("/pack.zip", hex("SHA-512", PACK), 1 << 20, -1), target);
        assertEquals(FetchResult.Status.NOT_MODIFIED, r.status());
        assertEquals(0, requests.get());
    }

    @Test
    void plainHttp_refusedWithoutTheTestHook() throws Exception {
        FetchResult r = new VerifiedDownload().fetch(spec("/pack.zip", hex("SHA-512", PACK), 1 << 20, -1),
                tmp.resolve("x.zip"));
        assertEquals(FetchResult.Status.ERROR_NO_CACHE, r.status());
        assertTrue(r.detail().contains("non-HTTPS"), r.detail());
        assertEquals(0, requests.get());
    }

    @Test
    void missingSha512_refused() throws Exception {
        FetchResult r = download().fetch(spec("/pack.zip", null, 1 << 20, -1), tmp.resolve("x.zip"));
        assertEquals(FetchResult.Status.ERROR_NO_CACHE, r.status());
        assertEquals(0, requests.get());
    }
}
