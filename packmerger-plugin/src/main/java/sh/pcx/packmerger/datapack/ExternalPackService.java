package sh.pcx.packmerger.datapack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import sh.pcx.packmerger.PackMergerBootstrap;
import sh.pcx.packmerger.PluginLogger;
import sh.pcx.packmerger.config.ConfigManager;
import sh.pcx.packmerger.datapack.modrinth.JdkTransport;
import sh.pcx.packmerger.datapack.modrinth.ModrinthClient;
import sh.pcx.packmerger.datapack.modrinth.ModrinthException;
import sh.pcx.packmerger.datapack.modrinth.ModrinthResolver;
import sh.pcx.packmerger.datapack.modrinth.ModrinthVersion;
import sh.pcx.packmerger.discovery.PluginPackStager;
import sh.pcx.packmerger.remote.FetchResult;
import sh.pcx.packmerger.remote.VerifiedSpec;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The external part of a datapack scan: Modrinth lookups by SHA-1 (cached), the
 * approval store, and verified downloads of approved packs into
 * {@code cache/modrinth/<sha512>.zip}. Runs inside the async scan only; staging
 * never touches the network.
 */
final class ExternalPackService {

    /** Downloads no approved entry referenced for this long are deleted. */
    private static final Duration UNUSED_DOWNLOAD_TTL = Duration.ofDays(7);

    /** Largest Modrinth API response accepted. */
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    /** After a failed lookup, automatic scans do not ask Modrinth again for this long. */
    static final Duration LOOKUP_RETRY_PAUSE = Duration.ofMinutes(5);

    /** Leftover {@code .part} files of interrupted downloads older than this are deleted. */
    private static final Duration STALE_PART_AGE = Duration.ofHours(1);

    /**
     * One external pack belonging to a datapack found by the last scan.
     *
     * @param alias         staged alias ({@code dp-<datapack>-rp})
     * @param datapackAlias alias of the datapack it belongs to
     * @param info          the inspected, verified download; {@code null} unless approved and downloaded
     */
    record External(String alias, ExternalPackStore.Entry entry, String datapackAlias, DatapackInfo info) {}

    record Result(List<External> externals, int pending, int missing, List<String> lines) {
        static Result empty() {
            return new Result(List.of(), 0, 0, List.of());
        }
    }

    /** A cached lookup: when it was made and what it found. */
    record CachedLookup(String checkedAt, ModrinthResolver.Resolution resolution) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final PackMergerBootstrap plugin;
    private final PluginLogger logger;
    private final Path downloadDir;
    private final Path lookupFile;
    private final ExternalPackStore store;
    private final Map<String, CachedLookup> lookups = new ConcurrentHashMap<>();
    /** Inspection of downloaded files, reused while the fingerprint is unchanged. */
    private final Map<Path, DatapackInfo> downloadInfo = new ConcurrentHashMap<>();

    /** One lock per SHA-512, so an approval and a scan never download the same file at once. */
    private final Map<String, Object> downloadLocks = new ConcurrentHashMap<>();

    private volatile JdkTransport transport;
    private volatile ModrinthClient client;
    private volatile ModrinthResolver resolver;
    /** Automatic lookups wait until then (epoch millis) after a failure. */
    private volatile long lookupsPausedUntil;

    ExternalPackService(PackMergerBootstrap plugin) {
        this.plugin = plugin;
        this.logger = plugin.getPluginLogger();
        this.downloadDir = plugin.getCacheFolder().toPath().resolve("modrinth");
        this.lookupFile = plugin.getCacheFolder().toPath().resolve("datapacks").resolve("modrinth-lookups.json");
        this.store = new ExternalPackStore(plugin.getDataFolder().toPath().resolve("external-sources.json"));
    }

    ExternalPackStore store() {
        return store;
    }

    /** (Re)reads config, the approval store and the lookup cache. */
    void reconfigure() {
        ConfigManager cfg = plugin.getConfigManager();
        String version = plugin.getLoader().getPluginMeta().getVersion();
        String userAgent = userAgent(version);
        URI api;
        try {
            api = URI.create(cfg.getModrinthApiUrl());
        } catch (IllegalArgumentException e) {
            logger.warning("[DATAPACK] invalid datapacks.external.modrinth.api-url; using https://api.modrinth.com/v2");
            api = URI.create("https://api.modrinth.com/v2");
        }
        JdkTransport previous = transport;
        transport = new JdkTransport(MAX_RESPONSE_BYTES);
        if (previous != null) previous.close();
        client = new ModrinthClient(api, userAgent, transport,
                Duration.ofSeconds(cfg.getModrinthTimeoutSeconds()), MAX_RESPONSE_BYTES);
        resolver = new ModrinthResolver(client, cfg.isModrinthIncludeOptional());
        lookupsPausedUntil = 0;
        store.load();
        if (store.loadProblem() != null) logger.warning("[DATAPACK] " + store.loadProblem());
        for (String bad : cfg.getModrinthTrustedProjects()) {
            if (!looksLikeProjectId(bad)) {
                logger.warning("[DATAPACK] datapacks.external.modrinth.trusted-projects: '" + bad + "' is not a "
                        + "Modrinth project ID (8 letters/digits, shown on the project page); slugs can be renamed "
                        + "and taken over, so they are ignored");
            }
        }
        loadLookups();
    }

    /** Releases the HTTP client (plugin disable). */
    void close() {
        JdkTransport t = transport;
        transport = null;
        client = null;
        if (t != null) t.close();
    }

    static String userAgent(String version) {
        return "desquerteur/PackMerger/" + version
                + " (datapack-discovery; fork of pcxgg/PackMerger; +https://github.com/desquerteur/PackMerger)";
    }

    /** @return {@code true} for a Modrinth project ID: 8 base62 characters (slugs may change hands, IDs never do) */
    static boolean looksLikeProjectId(String s) {
        return s != null && s.matches("[A-Za-z0-9]{8}");
    }

    /**
     * @return whether a found pack belongs to a trusted project: its project ID is
     *         listed, compared exactly (IDs are case-sensitive). Slugs never count.
     */
    static boolean isTrusted(Set<String> trustedIds, String projectId) {
        return projectId != null && trustedIds.contains(projectId);
    }

    /** @return where an approved file with this SHA-512 is cached */
    Path downloadFile(String sha512) {
        return downloadDir.resolve(sha512 + ".zip");
    }

    // -------------------------------------------------------------------------
    // Scan step
    // -------------------------------------------------------------------------

    /**
     * Looks up the datapacks (cache first), records found packs in the store, downloads
     * approved packs that are not cached yet and inspects them.
     *
     * @param onlyEnabled look up only datapacks the server has enabled
     * @param network     {@code false}: use only cached lookups and files already
     *                    downloaded (fast, never blocks on Modrinth)
     */
    Result run(List<DatapackPackManager.Candidate> candidates, String mcVersion, boolean forceLookup,
               DatapackInspector inspector, boolean onlyEnabled, boolean network) {
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isModrinthEnabled() || client == null) return Result.empty();

        List<DatapackPackManager.Candidate> eligible = new ArrayList<>();
        for (DatapackPackManager.Candidate c : candidates) {
            DatapackInfo i = c.info();
            if (i.kind() != DatapackKind.DATAPACK || i.sha1() == null) continue;
            if (onlyEnabled && !i.enabledOnServer()) continue;
            eligible.add(c);
        }
        Set<String> fresh = network ? lookUp(eligible, mcVersion, forceLookup, cfg) : Set.of();

        List<External> externals = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        int pending = 0, missing = 0;
        Set<String> trusted = new HashSet<>(cfg.getModrinthTrustedProjects());
        for (DatapackPackManager.Candidate c : eligible) {
            CachedLookup cached = lookups.get(c.info().sha1());
            if (cached == null) continue;
            ModrinthResolver.Resolution res = cached.resolution();
            String where = DatapackPackManager.display(c.info().path());
            boolean isFresh = fresh.contains(c.info().sha1());
            for (String problem : res.problems()) {
                if (problem.startsWith(ModrinthResolver.MISSING)) missing++;
                if (isFresh) logger.warning("[DATAPACK] " + problem.replaceFirst("] ", "] " + c.info().fileName() + ": "));
                lines.add("problem|" + c.alias() + "|" + problem);
            }
            if (isFresh && res.candidates().isEmpty() && res.problems().isEmpty()) {
                logger.debug("[DATAPACK] " + c.info().fileName() + ": "
                        + (res.onModrinth() ? "no resource pack declared on Modrinth" : "not on Modrinth"));
            }
            List<ModrinthResolver.Candidate> found = new ArrayList<>(res.candidates());
            found.removeIf(f -> f.file().sha512() == null || !f.file().sha512().matches("[0-9a-f]{128}")
                    || f.file().url() == null);
            found.sort(Comparator.comparing(f -> f.file().sha512()));
            for (int n = 0; n < found.size(); n++) {
                ModrinthResolver.Candidate f = found.get(n);
                boolean trust = isTrusted(trusted, f.projectId());
                ExternalPackStore.Entry e = store.upsert(res, f, where, cfg.isModrinthAutoApprove() || trust);
                String alias = DatapackStager.externalAliasFor(c.info().fileName()) + (n == 0 ? "" : "-" + (n + 1));
                String label = "Modrinth " + e.projectName() + " " + e.versionNumber();
                switch (e.state()) {
                    case PENDING -> {
                        pending++;
                        lines.add("pending|" + e.id() + "|" + label + " for " + c.info().fileName());
                        if (isFresh || e.firstSeen().equals(e.lastSeen())) {
                            logger.warning("[DATAPACK] [pending-approval] " + e.id() + " " + label + " for "
                                    + c.info().fileName() + " — /pm sources approve " + e.id());
                        }
                        externals.add(new External(alias, e, c.alias(), null));
                    }
                    case APPROVED -> externals.add(new External(alias, e, c.alias(), approvedInfo(e, inspector, network)));
                    case REJECTED -> externals.add(new External(alias, e, c.alias(), null));
                }
            }
        }
        if (network) pruneDownloads();
        return new Result(List.copyOf(externals), pending, missing, List.copyOf(lines));
    }

    /** Hash lookups for datapacks without a usable cached result. @return the SHA-1s resolved now */
    private Set<String> lookUp(List<DatapackPackManager.Candidate> eligible, String mcVersion, boolean force,
                               ConfigManager cfg) {
        Instant now = Instant.now();
        Duration ttl = Duration.ofHours(cfg.getModrinthLookupCacheHours());
        Map<String, DatapackPackManager.Candidate> need = new LinkedHashMap<>();
        for (DatapackPackManager.Candidate c : eligible) {
            CachedLookup cached = lookups.get(c.info().sha1());
            if (!force && cached != null && !expired(cached, now, ttl)) continue;
            need.put(c.info().sha1(), c);
        }
        Set<String> fresh = new LinkedHashSet<>();
        if (need.isEmpty()) return fresh;
        // After a failure, automatic scans (watchers, world loads) leave Modrinth alone for a while.
        if (!force && now.toEpochMilli() < lookupsPausedUntil) {
            logger.debug("[DATAPACK] Modrinth lookups paused after a failure; using cached results");
            return fresh;
        }
        client.resetBudget();
        Map<String, ModrinthVersion> found;
        try {
            found = client.versionsByHashes(need.keySet());
        } catch (ModrinthException e) {
            pauseLookups(now);
            logger.warning("[DATAPACK] Modrinth lookup failed (" + e.getMessage() + "); using cached results");
            return fresh;
        }
        for (String sha1 : need.keySet()) {
            ModrinthVersion v = found.get(sha1);
            try {
                ModrinthResolver.Resolution r = v == null ? ModrinthResolver.Resolution.notOnModrinth(sha1)
                        : resolver.resolve(sha1, v, mcVersion);
                lookups.put(sha1, new CachedLookup(now.toString(), r));
                fresh.add(sha1);
            } catch (ModrinthException e) {
                logger.warning("[DATAPACK] Modrinth: " + need.get(sha1).info().fileName() + ": " + e.getMessage());
                if (e.kind() != ModrinthException.Kind.BAD_RESPONSE) pauseLookups(now);
                if (e.kind() == ModrinthException.Kind.RATE_LIMITED) break;
            }
        }
        saveLookups();
        return fresh;
    }

    /** Remembers a failure: no automatic lookup before the pause (or Modrinth's own reset time) is over. */
    private void pauseLookups(Instant now) {
        long pause = Math.max(LOOKUP_RETRY_PAUSE.toMillis(), client.blockedForSeconds() * 1000L);
        lookupsPausedUntil = now.toEpochMilli() + pause;
    }

    private static boolean expired(CachedLookup c, Instant now, Duration ttl) {
        try {
            return Instant.parse(c.checkedAt()).plus(ttl).isBefore(now);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * @param network {@code false}: only a file that is already downloaded counts
     * @return the inspected download of an approved entry (downloading it first), or {@code null}
     */
    private DatapackInfo approvedInfo(ExternalPackStore.Entry e, DatapackInspector inspector, boolean network) {
        Path file = downloadFile(e.sha512());
        if (!Files.isRegularFile(file)) {
            if (!network) return null;
            FetchResult r = download(e);
            if (r.status() != FetchResult.Status.FETCHED && r.status() != FetchResult.Status.NOT_MODIFIED) return null;
        }
        String fp = PluginPackStager.fingerprint(file.toFile());
        DatapackInfo info = downloadInfo.get(file);
        if (info == null || !fp.equals(info.fingerprint())) {
            info = inspector.inspect(file, "modrinth/" + e.id(), true);
            downloadInfo.put(file, info);
        }
        if (!e.sha512().equals(info.sha512())) {
            logger.warning("[DATAPACK] [validation-error] " + e.id() + ": cached download does not match its sha512; deleting it");
            downloadInfo.remove(file);
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // retried on the next scan
            }
            return null;
        }
        if (!info.hasAssets()) {
            logger.warning("[DATAPACK] [validation-error] " + e.id() + " (" + e.fileName() + "): "
                    + (info.detail() == null ? "not a resource pack" : info.detail()));
        }
        return info;
    }

    /** Downloads one approved entry into the content-addressed cache (verified). Blocking. */
    FetchResult download(ExternalPackStore.Entry e) {
        ConfigManager cfg = plugin.getConfigManager();
        long max = cfg.getModrinthMaxDownloadMb() * 1024L * 1024L;
        if (e.size() > max) {
            String msg = "file is " + ZipLimits.describe(e.size()) + "; the limit is " + ZipLimits.describe(max)
                    + " (datapacks.external.modrinth.max-download-mb)";
            logger.warning("[DATAPACK] [download-error] " + e.id() + ": " + msg);
            return new FetchResult(e.id(), FetchResult.Status.ERROR_NO_CACHE, msg);
        }
        URI url;
        try {
            url = URI.create(e.url());
        } catch (IllegalArgumentException ex) {
            return new FetchResult(e.id(), FetchResult.Status.ERROR_NO_CACHE, "invalid URL");
        }
        VerifiedSpec spec = new VerifiedSpec(e.id(), url, Set.copyOf(cfg.getModrinthAllowedHosts()), max,
                Duration.ofSeconds(Math.max(cfg.getModrinthTimeoutSeconds(), 1L) * 6), e.sha512(), e.sha1(), e.size(),
                userAgent(plugin.getLoader().getPluginMeta().getVersion()));
        FetchResult r;
        // A second caller for the same file waits, then finds it verified on disk (NOT_MODIFIED).
        synchronized (downloadLocks.computeIfAbsent(e.sha512(), k -> new Object())) {
            r = plugin.getRemotePackManager().fetchVerified(spec, downloadFile(e.sha512()).toFile());
        }
        if (r.status() == FetchResult.Status.ERROR_NO_CACHE || r.status() == FetchResult.Status.ERROR_USING_CACHE) {
            logger.warning("[DATAPACK] [download-error] " + e.id() + ": " + r.detail());
        }
        return r;
    }

    /** Deletes cached downloads no approved entry references, once they are a week old. */
    private void pruneDownloads() {
        if (!Files.isDirectory(downloadDir)) return;
        Set<String> keep = new HashSet<>();
        for (ExternalPackStore.Entry e : store.inState(ExternalPackStore.State.APPROVED)) keep.add(e.sha512() + ".zip");
        long cutoff = System.currentTimeMillis() - UNUSED_DOWNLOAD_TTL.toMillis();
        long partCutoff = System.currentTimeMillis() - STALE_PART_AGE.toMillis();
        try (Stream<Path> s = Files.list(downloadDir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (keep.contains(n)) continue;
                File f = p.toFile();
                if (n.endsWith(".part")) {
                    // Left behind by a crash or kill during a download.
                    if (f.isFile() && f.lastModified() < partCutoff && f.delete()) {
                        logger.debug("[DATAPACK] removed stale partial download " + n);
                    }
                    continue;
                }
                if (f.isFile() && f.lastModified() < cutoff && f.delete()) {
                    downloadInfo.remove(p);
                    logger.debug("[DATAPACK] removed unused download " + n);
                }
            }
        } catch (IOException ignored) {
            // best effort
        }
    }

    // -------------------------------------------------------------------------
    // Lookup cache (cache/datapacks/modrinth-lookups.json)
    // -------------------------------------------------------------------------

    private void loadLookups() {
        lookups.clear();
        if (!Files.isRegularFile(lookupFile)) return;
        try {
            Map<String, CachedLookup> m = GSON.fromJson(Files.readString(lookupFile, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, CachedLookup>>() { }.getType());
            if (m != null) {
                m.forEach((k, v) -> {
                    if (v != null && v.resolution() != null) lookups.put(k, v);
                });
            }
        } catch (IOException | RuntimeException e) {
            logger.debug("[DATAPACK] ignoring unreadable " + lookupFile.getFileName() + ": " + e.getMessage());
        }
    }

    private void saveLookups() {
        try {
            Files.createDirectories(lookupFile.getParent());
            Path tmp = lookupFile.resolveSibling(lookupFile.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(new java.util.TreeMap<>(lookups)), StandardCharsets.UTF_8);
            SafeZip.moveAtomic(tmp, lookupFile);
        } catch (IOException | RuntimeException e) {
            logger.debug("[DATAPACK] could not write " + lookupFile.getFileName() + ": " + e.getMessage());
        }
    }
}
