package sh.pcx.packmerger.datapack.modrinth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal Modrinth API v2 client for datapack discovery: hash lookups, versions and
 * projects. Holds no Bukkit references; the HTTP layer is a {@link Transport} so
 * tests use a stub.
 *
 * <ul>
 *   <li>Every request carries the configured, unique {@code User-Agent} (Modrinth
 *       requires one).</li>
 *   <li>Responses larger than {@code maxResponseBytes} are rejected.</li>
 *   <li>HTTP 429, or fewer than {@value #MIN_REMAINING} requests left according to
 *       {@code X-Ratelimit-Remaining}, stops further requests with
 *       {@link ModrinthException.Kind#RATE_LIMITED} until the reset time Modrinth
 *       sends ({@code X-Ratelimit-Reset}, at least {@value #MIN_BLOCK_SECONDS} s) has
 *       passed, across scans: {@link #resetBudget()} does not lift it early.</li>
 *   <li>Using up the per-scan budget of {@value #REQUEST_BUDGET} requests or
 *       {@link #SCAN_TIME_BUDGET} stops the scan's requests until {@link #resetBudget()}.</li>
 * </ul>
 */
public final class ModrinthClient {

    /** Requests one scan may make. */
    public static final int REQUEST_BUDGET = 60;

    /** Time one scan may spend on Modrinth requests. */
    public static final Duration SCAN_TIME_BUDGET = Duration.ofMinutes(2);

    /** Shortest pause after Modrinth rate-limits us (also when it sends no reset time). */
    static final int MIN_BLOCK_SECONDS = 60;

    /** Below this many remaining requests (X-Ratelimit-Remaining) the scan stops. */
    static final int MIN_REMAINING = 5;

    /** The HTTP layer: the JDK {@code HttpClient} in production ({@link JdkTransport}), a stub in tests. */
    public interface Transport {
        Response send(String method, URI uri, String jsonBody, Map<String, String> headers, Duration timeout)
                throws IOException, InterruptedException;

        /** @param body the response body, at most {@code maxResponseBytes + 1} bytes */
        record Response(int status, Map<String, List<String>> headers, byte[] body) {}
    }

    /** Rate-limit state from the last response's {@code X-Ratelimit-*} headers ({@code -1} if absent). */
    public record RateLimit(int limit, int remaining, int resetSeconds) {}

    private final URI apiBase;
    private final String userAgent;
    private final Transport transport;
    private final Duration timeout;
    private final int maxResponseBytes;

    private final java.util.function.LongSupplier clock;

    private volatile RateLimit lastRateLimit = new RateLimit(-1, -1, -1);
    private int requestsThisScan;
    private long scanDeadlineMillis = Long.MAX_VALUE;
    private boolean exhausted;
    private String exhaustedReason;
    /** Until when (clock millis) Modrinth asked us to wait; survives {@link #resetBudget()}. */
    private long blockedUntilMillis;

    public ModrinthClient(URI apiBase, String userAgent, Transport transport, Duration timeout, int maxResponseBytes) {
        this(apiBase, userAgent, transport, timeout, maxResponseBytes, System::currentTimeMillis);
    }

    /** @param clock current time in milliseconds (tests) */
    ModrinthClient(URI apiBase, String userAgent, Transport transport, Duration timeout, int maxResponseBytes,
                   java.util.function.LongSupplier clock) {
        String base = apiBase.toString();
        this.apiBase = URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
        this.userAgent = userAgent;
        this.transport = transport;
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
        this.clock = clock;
    }

    /**
     * Starts a new per-scan request and time budget. A rate-limit pause Modrinth asked
     * for stays in force until its reset time has passed.
     */
    public synchronized void resetBudget() {
        long now = clock.getAsLong();
        requestsThisScan = 0;
        scanDeadlineMillis = now + SCAN_TIME_BUDGET.toMillis();
        if (now >= blockedUntilMillis) {
            exhausted = false;
            exhaustedReason = null;
        }
    }

    /** @return seconds until Modrinth's rate-limit pause ends, {@code 0} if none */
    public synchronized long blockedForSeconds() {
        return Math.max(0, (blockedUntilMillis - clock.getAsLong() + 999) / 1000);
    }

    /** @return the rate-limit headers of the last response */
    public RateLimit lastRateLimit() {
        return lastRateLimit;
    }

    // -------------------------------------------------------------------------
    // Endpoints
    // -------------------------------------------------------------------------

    /**
     * {@code POST /version_files} — one request for all hashes.
     *
     * @return SHA-1 → version for every hash Modrinth knows (unknown hashes are absent)
     */
    public Map<String, ModrinthVersion> versionsByHashes(Collection<String> sha1s) throws ModrinthException {
        Map<String, ModrinthVersion> out = new LinkedHashMap<>();
        if (sha1s.isEmpty()) return out;
        JsonObject body = new JsonObject();
        JsonArray hashes = new JsonArray();
        for (String h : sha1s) hashes.add(h.toLowerCase(Locale.ROOT));
        body.add("hashes", hashes);
        body.addProperty("algorithm", "sha1");
        JsonElement resp = request("POST", "/version_files", body.toString());
        if (resp == null) return out;
        if (!resp.isJsonObject()) throw new ModrinthException(ModrinthException.Kind.BAD_RESPONSE, "version_files: not an object");
        for (Map.Entry<String, JsonElement> e : resp.getAsJsonObject().entrySet()) {
            try {
                out.put(e.getKey().toLowerCase(Locale.ROOT), ModrinthVersion.parse(e.getValue()));
            } catch (IllegalArgumentException ex) {
                throw new ModrinthException(ModrinthException.Kind.BAD_RESPONSE, "version_files: " + ex.getMessage());
            }
        }
        return out;
    }

    /** {@code GET /version_file/{hash}?algorithm=sha1} — {@code null} if Modrinth does not know the file. */
    public ModrinthVersion versionByHash(String sha1) throws ModrinthException {
        JsonElement resp = request("GET", "/version_file/" + enc(sha1) + "?algorithm=sha1", null);
        return resp == null ? null : parseVersion(resp);
    }

    /** {@code GET /version/{id}} — {@code null} on 404. */
    public ModrinthVersion version(String versionId) throws ModrinthException {
        JsonElement resp = request("GET", "/version/" + enc(versionId), null);
        return resp == null ? null : parseVersion(resp);
    }

    /** {@code GET /project/{id|slug}} — {@code null} on 404. */
    public ModrinthProject project(String idOrSlug) throws ModrinthException {
        JsonElement resp = request("GET", "/project/" + enc(idOrSlug), null);
        if (resp == null) return null;
        try {
            return ModrinthProject.parse(resp);
        } catch (IllegalArgumentException e) {
            throw new ModrinthException(ModrinthException.Kind.BAD_RESPONSE, "project: " + e.getMessage());
        }
    }

    /**
     * {@code GET /project/{id}/version?game_versions=["…"]&loaders=["…"]&include_changelog=false}.
     *
     * @param gameVersion filter, or {@code null} for every version
     * @param loader      filter, or {@code null}
     * @return the versions (empty on 404)
     */
    public List<ModrinthVersion> projectVersions(String projectId, String gameVersion, String loader)
            throws ModrinthException {
        StringBuilder q = new StringBuilder("/project/").append(enc(projectId)).append("/version?include_changelog=false");
        if (gameVersion != null) q.append("&game_versions=").append(enc("[\"" + gameVersion + "\"]"));
        if (loader != null) q.append("&loaders=").append(enc("[\"" + loader + "\"]"));
        JsonElement resp = request("GET", q.toString(), null);
        if (resp == null) return List.of();
        if (!resp.isJsonArray()) throw new ModrinthException(ModrinthException.Kind.BAD_RESPONSE, "versions: not an array");
        java.util.ArrayList<ModrinthVersion> out = new java.util.ArrayList<>();
        for (JsonElement e : resp.getAsJsonArray()) out.add(parseVersion(e));
        return out;
    }

    private static ModrinthVersion parseVersion(JsonElement e) throws ModrinthException {
        try {
            return ModrinthVersion.parse(e);
        } catch (IllegalArgumentException ex) {
            throw new ModrinthException(ModrinthException.Kind.BAD_RESPONSE, ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Transport handling
    // -------------------------------------------------------------------------

    /** @return the parsed body, or {@code null} on 404 */
    private JsonElement request(String method, String pathAndQuery, String body) throws ModrinthException {
        synchronized (this) {
            if (exhausted) throw new ModrinthException(ModrinthException.Kind.RATE_LIMITED, exhaustedReason);
            if (requestsThisScan >= REQUEST_BUDGET) {
                stop("request budget of " + REQUEST_BUDGET + " per scan used up; the next scan continues");
                throw new ModrinthException(ModrinthException.Kind.RATE_LIMITED, exhaustedReason);
            }
            if (clock.getAsLong() > scanDeadlineMillis) {
                stop("time budget of " + SCAN_TIME_BUDGET.toSeconds() + "s per scan used up; the next scan continues");
                throw new ModrinthException(ModrinthException.Kind.RATE_LIMITED, exhaustedReason);
            }
            requestsThisScan++;
        }
        URI uri = URI.create(apiBase + pathAndQuery);
        Map<String, String> headers = new TreeMap<>();
        headers.put("User-Agent", userAgent);
        headers.put("Accept", "application/json");
        if (body != null) headers.put("Content-Type", "application/json");
        Transport.Response r;
        try {
            r = transport.send(method, uri, body, headers, timeout);
        } catch (HttpTimeoutException e) {
            throw new ModrinthException(ModrinthException.Kind.NETWORK, "timeout after " + timeout.toSeconds() + "s");
        } catch (IOException e) {
            throw new ModrinthException(ModrinthException.Kind.NETWORK, String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModrinthException(ModrinthException.Kind.NETWORK, "interrupted");
        }
        readRateLimit(r.headers());
        int status = r.status();
        if (status == 429) {
            long wait = block(lastRateLimit.resetSeconds());
            stop("HTTP 429 (rate limited); retry after " + wait + "s");
            throw new ModrinthException(ModrinthException.Kind.RATE_LIMITED, exhaustedReason);
        }
        RateLimit rl = lastRateLimit;
        if (rl.remaining() >= 0 && rl.remaining() < MIN_REMAINING) {
            // Use this response, but make no further requests until the window resets.
            long wait = block(rl.resetSeconds());
            stop("only " + rl.remaining() + " Modrinth requests left; retry after " + wait + "s");
        }
        if (status == 404) return null;
        if (status / 100 != 2) {
            throw new ModrinthException(ModrinthException.Kind.SERVER_ERROR, "HTTP " + status + " from " + uri.getPath());
        }
        byte[] bytes = r.body() == null ? new byte[0] : r.body();
        if (bytes.length > maxResponseBytes) {
            throw new ModrinthException(ModrinthException.Kind.BAD_RESPONSE,
                    "response larger than " + maxResponseBytes + " bytes");
        }
        try {
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new ModrinthException(ModrinthException.Kind.BAD_RESPONSE, "malformed JSON: " + e.getMessage());
        }
    }

    private synchronized void stop(String reason) {
        exhausted = true;
        exhaustedReason = reason;
    }

    /** Pauses requests for {@code resetSeconds} (at least {@value #MIN_BLOCK_SECONDS}). @return the pause in seconds */
    private synchronized long block(int resetSeconds) {
        long wait = Math.max(MIN_BLOCK_SECONDS, resetSeconds);
        blockedUntilMillis = Math.max(blockedUntilMillis, clock.getAsLong() + wait * 1000L);
        return wait;
    }

    private void readRateLimit(Map<String, List<String>> headers) {
        lastRateLimit = new RateLimit(header(headers, "x-ratelimit-limit"), header(headers, "x-ratelimit-remaining"),
                header(headers, "x-ratelimit-reset"));
    }

    private static int header(Map<String, List<String>> headers, String name) {
        if (headers == null) return -1;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() == null || !e.getKey().equalsIgnoreCase(name) || e.getValue().isEmpty()) continue;
            try {
                return Integer.parseInt(e.getValue().get(0).trim());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
