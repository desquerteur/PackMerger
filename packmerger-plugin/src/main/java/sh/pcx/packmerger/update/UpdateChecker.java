package sh.pcx.packmerger.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sh.pcx.packmerger.PackMergerBootstrap;
import sh.pcx.packmerger.PluginLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks on plugin enable whether a newer release of this fork
 * (<a href="https://github.com/desquerteur/PackMerger">desquerteur/PackMerger</a>) is
 * available and notifies admins.
 *
 * <p>The default source is GitHub's "latest release" API for the fork, which only
 * returns full releases (never drafts or pre-releases, so the {@code dev-*} builds of
 * every push are ignored; a {@code dev-*} tag is also skipped defensively). A custom
 * {@code update-check.url} may instead point at a minimal manifest:</p>
 * <pre>
 * {
 *   "latest": "1.4.0",
 *   "latest_url": "https://github.com/desquerteur/PackMerger/releases/tag/v1.4.0",
 *   "latest_notes": "Datapack resource packs, zero config"
 * }
 * </pre>
 *
 * <p>Admins with the {@code packmerger.admin} permission see a chat notice on
 * join if an update is available; the console also logs at enable time.
 * Everything is advisory — the plugin never auto-downloads or prompts.</p>
 */
public class UpdateChecker {

    /** Default source: the fork's latest full release on GitHub. */
    public static final String DEFAULT_URL =
            "https://api.github.com/repos/desquerteur/PackMerger/releases/latest";

    private final PackMergerBootstrap plugin;
    private final PluginLogger logger;
    private final HttpClient client;
    private final AtomicReference<Result> latestResult = new AtomicReference<>();

    public UpdateChecker(PackMergerBootstrap plugin) {
        this.plugin = plugin;
        this.logger = plugin.getPluginLogger();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Fetches the manifest, compares against the running version, and caches
     * the result. Safe to call repeatedly; the last result is always available
     * via {@link #getLatestResult()}.
     */
    public void checkNow(String manifestUrl, String runningVersion) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(manifestUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", userAgent(runningVersion))
                    .header("Accept", "application/vnd.github+json, application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                logger.debug("[update] no release published yet; skipping");
                return;
            }
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                logger.debug("[update] update source returned HTTP " + resp.statusCode() + "; skipping");
                return;
            }

            Result result = parse(resp.body(), runningVersion);
            if (result == null) {
                logger.debug("[update] update source has no usable release version; skipping");
                return;
            }
            latestResult.set(result);

            if (result.updateAvailable()) {
                logger.warning("[update] newer PackMerger release available: "
                        + result.latest() + " (running " + runningVersion + ")");
                if (result.url() != null) logger.warning("[update] release: " + result.url());
            } else {
                logger.info("[update] PackMerger is up to date (" + runningVersion + ")");
            }
        } catch (Exception e) {
            logger.debug("[update] check failed: " + e.getMessage());
        }
    }

    /** @return the User-Agent sent to GitHub (its API refuses requests without one) */
    static String userAgent(String runningVersion) {
        return "desquerteur/PackMerger/" + runningVersion + " (update-check; +https://github.com/desquerteur/PackMerger)";
    }

    /**
     * Reads either a GitHub release object ({@code tag_name}, {@code html_url},
     * {@code name}, {@code prerelease}) or a manifest ({@code latest},
     * {@code latest_url}, {@code latest_notes}).
     *
     * @return the result, or {@code null} for anything that is not a usable full release
     *         (no version, a draft or pre-release, or a {@code dev-*} build)
     */
    static Result parse(String body, String runningVersion) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (RuntimeException e) {
            return null;
        }
        if (!parsed.isJsonObject()) return null;
        JsonObject root = parsed.getAsJsonObject();
        String latest;
        String url;
        String notes;
        if (root.has("tag_name")) {
            if (bool(root, "prerelease") || bool(root, "draft")) return null;
            String tag = str(root, "tag_name");
            if (tag == null || tag.startsWith("dev-")) return null;
            latest = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
            url = str(root, "html_url");
            notes = str(root, "name");
        } else {
            latest = str(root, "latest");
            url = str(root, "latest_url");
            notes = str(root, "latest_notes");
        }
        if (latest == null || latest.isBlank() || !Character.isDigit(latest.charAt(0))) return null;
        return new Result(runningVersion, latest, url, notes, compareVersions(runningVersion, latest) < 0);
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    /** @return the most recent check's result, or {@code null} if none completed */
    public Result getLatestResult() { return latestResult.get(); }

    /**
     * @return a one-line advisory message describing the available update, or
     *         {@code null} if no update is currently known. Callers render it
     *         into whatever chat API they have available (Adventure Component,
     *         legacy String, etc.) so this class stays free of Paper/Bukkit
     *         imports and remains unit-testable in isolation.
     */
    public String updateMessageOrNull() {
        Result r = latestResult.get();
        if (r == null || !r.updateAvailable()) return null;
        return "[PackMerger] Update available: " + r.running() + " → " + r.latest()
                + (r.url() != null ? " — " + r.url() : "");
    }

    /**
     * Result of a version check. Fields are all strings so the caller can
     * display them verbatim without re-parsing.
     *
     * @param running          version currently running (from plugin.yml)
     * @param latest           version declared in the manifest
     * @param url              optional release URL from the manifest
     * @param notes            optional release notes from the manifest
     * @param updateAvailable  {@code true} if {@code latest} sorts higher than {@code running}
     */
    public record Result(String running, String latest, String url, String notes, boolean updateAvailable) {}

    /**
     * Semantic version comparison: dot-separated numeric components, missing
     * trailing components treated as zero, non-numeric components compared
     * lexically as a last-resort tiebreaker.
     */
    static int compareVersions(String a, String b) {
        if (Objects.equals(a, b)) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        String[] ap = a.split("[.-]");
        String[] bp = b.split("[.-]");
        int max = Math.max(ap.length, bp.length);
        for (int i = 0; i < max; i++) {
            String ai = i < ap.length ? ap[i] : "0";
            String bi = i < bp.length ? bp[i] : "0";
            Integer an = tryInt(ai);
            Integer bn = tryInt(bi);
            if (an != null && bn != null) {
                int cmp = Integer.compare(an, bn);
                if (cmp != 0) return cmp;
            } else {
                int cmp = ai.compareTo(bi);
                if (cmp != 0) return cmp;
            }
        }
        return 0;
    }

    private static Integer tryInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
