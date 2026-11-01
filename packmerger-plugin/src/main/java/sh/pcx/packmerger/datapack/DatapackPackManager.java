package sh.pcx.packmerger.datapack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import sh.pcx.packmerger.PackMergerBootstrap;
import sh.pcx.packmerger.PluginLogger;
import sh.pcx.packmerger.config.ConfigManager;
import sh.pcx.packmerger.discovery.PluginPackStager;
import sh.pcx.packmerger.merge.FileWatcher;
import sh.pcx.packmerger.merge.PackFormatRegistry;
import sh.pcx.packmerger.merge.PackMergeEngine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Finds the resource packs that belong to the server's datapacks and stages them
 * into {@code packs/.datapack-packs/<alias>.zip}, so the merge pipeline picks them
 * up by alias like plugin packs ({@link sh.pcx.packmerger.discovery.PluginPackManager}).
 *
 * <p>Two steps, kept apart on purpose:</p>
 * <ul>
 *   <li>{@link #scan} (async; startup, {@code /pm scan}, watchers, world load,
 *       {@code /reload}) takes a {@link DatapackLocator} snapshot and inspects every
 *       datapack (only changed ones are read again) using cached Modrinth results only;
 *       then, outside every lock, it asks Modrinth and downloads approved packs. Each
 *       phase requests a merge when something that affects staging changed.</li>
 *   <li>{@link #stageAll()} runs inside every merge and only reads local, already
 *       inspected files: no network, no Bukkit calls, and it never waits for the
 *       network phase of a running scan.</li>
 * </ul>
 */
public class DatapackPackManager {

    /** Sub-directory of {@code packs/} where datapack assets are staged. */
    public static final String STAGE_DIR = PackMergeEngine.DATAPACK_STAGE_DIR;

    /** How long the startup scan waits for the first server tick before using server.properties alone. */
    private static final long SNAPSHOT_TIMEOUT_SECONDS = 10;

    public enum ScanTrigger { STARTUP, MANUAL, WATCHER, WORLD_CHANGE, RESOURCES_RELOADED, RELOAD, REBUILD }

    /**
     * Summary of one scan.
     *
     * @param changed whether anything that affects staging changed since the previous scan
     */
    public record ScanReport(int datapacks, int withAssets, int dataOnly, int pendingApproval,
                             int missingDependencies, int errors, boolean changed, List<String> folders,
                             List<String> lines) {
        static ScanReport disabled() {
            return new ScanReport(0, 0, 0, 0, 0, 0, false, List.of(), List.of());
        }
    }

    /**
     * One source for {@code /pm sources}.
     *
     * @param kind {@code datapack} or {@code modrinth}
     */
    public record SourceView(String id, String alias, String kind, String origin, String state, String detail) {}

    /** One datapack found by the last scan. */
    public record Candidate(String alias, DatapackLocator.Folder folder, DatapackInfo info) {}

    /** Immutable result of the last scan. */
    record ScanState(DatapackLocator.Snapshot snapshot, List<Candidate> candidates,
                     List<ExternalPackService.External> externals, String signature) {}

    private final PackMergerBootstrap plugin;
    private final PluginLogger logger;
    private final Path stageDir;
    private final Path stateFile;
    private final DatapackLocator locator;
    private final ExternalPackService external;

    private volatile DatapackInspector inspector;
    private volatile DatapackStager stager;
    private volatile ScanState lastScan;
    private volatile DatapackStager.Report lastStage;

    /** Inspection results by path, reused while the fingerprint is unchanged. */
    private final Map<Path, DatapackInfo> infoCache = new ConcurrentHashMap<>();

    /** Guards {@link #lastScan} changes: the local scan phase and its network update. Never held during I/O on the network. */
    private final Object scanLock = new Object();

    /** Guards {@link #scanning}, {@link #rescanRequested} and {@link #runningScan} together. */
    private final Object scanStateLock = new Object();
    private boolean scanning;
    private boolean rescanRequested;
    private CompletableFuture<ScanReport> runningScan;

    /** Set by {@link #shutdown()}: no scan or watcher starts afterwards. */
    private volatile boolean closed;

    public DatapackPackManager(PackMergerBootstrap plugin) {
        this.plugin = plugin;
        this.logger = plugin.getPluginLogger();
        this.stageDir = plugin.getPacksFolder().toPath().resolve(STAGE_DIR);
        this.stateFile = plugin.getCacheFolder().toPath().resolve("datapacks").resolve("state.json");
        this.locator = new DatapackLocator(plugin);
        this.external = new ExternalPackService(plugin);
    }

    /** @return the locator (world/datapack snapshot), for listeners */
    public DatapackLocator getLocator() {
        return locator;
    }

    /** Rebuilds inspector and stager from current config (enable and /pm reload). */
    public void reconfigure() {
        ConfigManager cfg = plugin.getConfigManager();
        Path temp = plugin.getCacheFolder().toPath().resolve("datapacks").resolve("tmp");
        // Nested zips copied by a scan that was killed stay behind; on enable all of them
        // go, on a reload only old ones (a scan may be using a fresh one right now).
        cleanTemp(temp, stager == null ? 0 : TEMP_MAX_AGE_MILLIS);
        ZipLimits limits = cfg.getZipLimits();
        inspector = new DatapackInspector(limits, temp);
        stager = new DatapackStager(stageDir, new DatapackAssetExtractor(limits, temp),
                cfg.getDatapacksStabilityPolls(), cfg.getDatapacksStabilityIntervalMillis(), Thread::sleep);
        // New limits may change what an unchanged file inspects to; state.json written
        // under other limits (or by another plugin version) is not reused.
        infoCache.clear();
        loadState();
        external.reconfigure();
    }

    /** Stops watchers, closes the Modrinth client and refuses new scans (plugin disable). */
    public void shutdown() {
        closed = true;
        stopWatchers();
        external.close();
    }

    /** A {@code pm-nested-*.zip} temp file older than this is left over from an interrupted scan. */
    private static final long TEMP_MAX_AGE_MILLIS = TimeUnit.MINUTES.toMillis(10);

    /** Deletes {@code pm-nested-*.zip} files older than {@code maxAgeMillis} from the temp folder. */
    static int cleanTemp(Path temp, long maxAgeMillis) {
        if (!Files.isDirectory(temp)) return 0;
        long cutoff = System.currentTimeMillis() - maxAgeMillis;
        int n = 0;
        try (java.util.stream.Stream<Path> s = Files.list(temp)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String name = p.getFileName().toString();
                if (!name.startsWith("pm-nested-") || !Files.isRegularFile(p)) continue;
                try {
                    if (maxAgeMillis > 0 && Files.getLastModifiedTime(p).toMillis() >= cutoff) continue;
                    if (Files.deleteIfExists(p)) n++;
                } catch (IOException ignored) {
                    // in use or gone; the next reconfigure tries again
                }
            }
        } catch (IOException ignored) {
            // best effort
        }
        return n;
    }

    private DatapackStager stager() {
        if (stager == null) reconfigure();
        return stager;
    }

    // -------------------------------------------------------------------------
    // Scan
    // -------------------------------------------------------------------------

    /**
     * Full scan, async; one scan at a time (a scan requested meanwhile runs once the
     * current one ends and gets the running scan's future).
     * <ol>
     *   <li>local phase: snapshot, inspection, cached Modrinth results and files already
     *       downloaded; quick, and the only part the startup merge waits for;</li>
     *   <li>network phase (when Modrinth is enabled): lookups and verified downloads,
     *       outside every lock, so a slow Modrinth never blocks a merge or the server
     *       stopping.</li>
     * </ol>
     * Each phase requests a merge when something that affects staging changed (except
     * on startup and rebuild, where a merge follows anyway).
     *
     * @param forceLookup ignore cached external lookups ({@code /pm scan refresh})
     * @return for {@link ScanTrigger#STARTUP} a future completed after the local phase;
     *         otherwise one completed after both phases
     */
    public CompletableFuture<ScanReport> scan(ScanTrigger trigger, boolean forceLookup) {
        if (closed || !plugin.getConfigManager().isDatapacksEnabled()) {
            return CompletableFuture.completedFuture(ScanReport.disabled());
        }
        CompletableFuture<ScanReport> done = new CompletableFuture<>();
        CompletableFuture<ScanReport> local = new CompletableFuture<>();
        synchronized (scanStateLock) {
            if (scanning) {
                rescanRequested = true;
                return runningScan;
            }
            scanning = true;
            runningScan = done;
        }
        CompletableFuture<DatapackLocator.Snapshot> refresh = locator.refreshAsync();
        refresh.copy()
                .completeOnTimeout(null, SNAPSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(t -> {
                    logger.debug("[DATAPACK] world snapshot failed: " + t.getMessage());
                    return null;
                })
                .thenAcceptAsync(snapshot -> {
                    DatapackLocator.Snapshot s = snapshot;
                    if (s == null) {
                        s = fallback();
                        rescanWhenSnapshotArrives(refresh);
                    }
                    ScanReport first = runScan(s, trigger, forceLookup);
                    local.complete(first);
                    ScanReport full = networkPhase(trigger, forceLookup, first);
                    done.complete(full != null ? full : first);
                })
                .whenComplete((ignored, t) -> {
                    if (t != null) {
                        logger.warning("[DATAPACK] scan failed: " + t.getMessage());
                        local.completeExceptionally(t);
                        done.completeExceptionally(t);
                    }
                    boolean again;
                    synchronized (scanStateLock) {
                        scanning = false;
                        again = rescanRequested;
                        rescanRequested = false;
                    }
                    if (again) scan(ScanTrigger.WATCHER, false);
                });
        return trigger == ScanTrigger.STARTUP ? local : done;
    }

    private DatapackLocator.Snapshot fallback() {
        logger.debug("[DATAPACK] no world snapshot from the server thread; scanning <level-name>/datapacks only");
        return locator.fallbackSnapshot();
    }

    /**
     * The startup scan used the fallback snapshot (the first tick came late): once the
     * real snapshot arrives, scan again, so disabled datapacks and other worlds are seen.
     */
    private void rescanWhenSnapshotArrives(CompletableFuture<DatapackLocator.Snapshot> refresh) {
        refresh.thenAccept(s -> {
            if (s != null && !closed) {
                logger.debug("[DATAPACK] world snapshot arrived late; scanning again with it");
                scan(ScanTrigger.WORLD_CHANGE, false);
            }
        });
    }

    /**
     * Modrinth lookups and downloads for the candidates of the last local phase, run
     * without any lock held; the result replaces the external part of {@link #lastScan}
     * unless a newer scan replaced it meanwhile.
     *
     * @return the full report, or {@code null} if there was nothing to do
     */
    private ScanReport networkPhase(ScanTrigger trigger, boolean forceLookup, ScanReport first) {
        ConfigManager cfg = plugin.getConfigManager();
        ScanState base = lastScan;
        if (base == null || closed || !cfg.isModrinthEnabled()) return null;
        ExternalPackService.Result ext = external.run(base.candidates(), base.snapshot().minecraftVersion(),
                forceLookup, inspector(), cfg.isDatapacksOnlyEnabled(), true);
        ScanReport report;
        boolean changed;
        synchronized (scanLock) {
            if (lastScan != base) return null;
            String signature = signature(base.candidates(), ext.externals(), base.snapshot());
            changed = !signature.equals(base.signature());
            lastScan = new ScanState(base.snapshot(), base.candidates(), ext.externals(), signature);
            report = report(base.candidates(), ext, base.snapshot(), first.changed() || changed);
        }
        if (changed && trigger != ScanTrigger.REBUILD) {
            plugin.requestMerge("datapack resource packs from Modrinth changed");
        }
        return report;
    }

    /**
     * The local scan phase: snapshot folders, inspection, cached external results.
     * Runs off the server thread, never on the network; under {@link #scanLock} so
     * stageAll never sees half a scan.
     */
    ScanReport runScan(DatapackLocator.Snapshot snapshot, ScanTrigger trigger, boolean forceLookup) {
        synchronized (scanLock) {
            return runScanLocked(snapshot, trigger, forceLookup);
        }
    }

    private ScanReport runScanLocked(DatapackLocator.Snapshot snapshot, ScanTrigger trigger, boolean forceLookup) {
        List<Candidate> candidates = new ArrayList<>();
        List<Path> all = new ArrayList<>();
        Set<Path> loaded = new java.util.HashSet<>();
        Map<Path, DatapackLocator.Folder> folderOf = new LinkedHashMap<>();
        for (DatapackLocator.Folder folder : snapshot.folders()) {
            for (Path p : DatapackLocator.candidates(folder.path())) {
                all.add(p);
                folderOf.put(p, folder);
                if (folder.loadedByServer()) loaded.add(p);
            }
        }
        // Copies in folders the server loads get the plain dp-<name> alias first.
        Map<Path, String> aliases = DatapackStager.assignAliases(all, loaded);
        Set<String> ids = snapshot.enabledPackIds();
        boolean known = snapshot.enabledStateKnown();
        if (known && idsLookForeign(ids, folderOf)) {
            logger.warning("[DATAPACK] the server reports file datapacks, but none matches a file in "
                    + "the datapacks folder; treating every datapack in the level folder as enabled");
            known = false;
        }

        List<String> discoveredNow = new ArrayList<>();
        for (Path p : all) {
            DatapackLocator.Folder folder = folderOf.get(p);
            String id = DatapackLocator.packId(p);
            boolean enabled = folder.loadedByServer() && (!known || isEnabled(ids, p));
            String fp = PluginPackStager.fingerprint(p.toFile());
            DatapackInfo cached = infoCache.get(p);
            DatapackInfo info;
            if (cached != null && fp.equals(cached.fingerprint()) && cached.kind() != DatapackKind.UNREADABLE) {
                info = cached.withEnabled(id, enabled);
            } else {
                info = inspector().inspect(p, id, enabled);
                infoCache.put(p, info);
                discoveredNow.add(aliases.get(p));
            }
            candidates.add(new Candidate(aliases.get(p), folder, info));
        }
        infoCache.keySet().retainAll(all);

        // External resource packs (Modrinth) from cached lookups and finished downloads only;
        // the network phase (lookups, downloads) runs after this, without the lock.
        ExternalPackService.Result ext = external.run(candidates, snapshot.minecraftVersion(), forceLookup,
                inspector(), plugin.getConfigManager().isDatapacksOnlyEnabled(), false);

        String signature = signature(candidates, ext.externals(), snapshot);
        ScanState previous = lastScan;
        boolean changed = previous == null || !previous.signature().equals(signature);
        lastScan = new ScanState(snapshot, List.copyOf(candidates), ext.externals(), signature);

        ScanReport report = report(candidates, ext, snapshot, changed);
        logScan(candidates, discoveredNow, report, trigger);
        if (changed) saveState();
        if (changed && trigger != ScanTrigger.STARTUP && trigger != ScanTrigger.REBUILD) {
            plugin.requestMerge("datapacks changed");
        }
        return report;
    }

    private DatapackInspector inspector() {
        if (inspector == null) reconfigure();
        return inspector;
    }

    /** The enabled-id check, tolerant of a different id prefix than {@code file/}. */
    static boolean isEnabled(Set<String> ids, Path candidate) {
        String name = candidate.getFileName().toString();
        if (ids.contains("file/" + name)) return true;
        for (String id : ids) {
            if (id.equals(name) || id.endsWith("/" + name)) return true;
        }
        return false;
    }

    /** @return {@code true} if the server lists file packs but none matches a level-folder file */
    private static boolean idsLookForeign(Set<String> ids, Map<Path, DatapackLocator.Folder> folderOf) {
        List<Path> level = folderOf.entrySet().stream()
                .filter(e -> e.getValue().loadedByServer()).map(Map.Entry::getKey).toList();
        if (level.isEmpty() || ids.stream().noneMatch(id -> id.startsWith("file/"))) return false;
        return level.stream().noneMatch(p -> isEnabled(ids, p));
    }

    private static String signature(List<Candidate> candidates, List<ExternalPackService.External> externals,
                                    DatapackLocator.Snapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append(snapshot.minecraftVersion()).append('\n');
        for (Candidate c : candidates) {
            DatapackInfo i = c.info();
            sb.append(c.alias()).append('|').append(i.path()).append('|').append(i.fingerprint()).append('|')
                    .append(i.kind()).append('|').append(i.layout()).append('|').append(i.enabledOnServer())
                    .append('\n');
        }
        for (ExternalPackService.External x : externals) {
            sb.append(x.alias()).append('|').append(x.entry().id()).append('|').append(x.entry().state())
                    .append('|').append(x.info() == null ? "-" : x.info().fingerprint()).append('\n');
        }
        return sb.toString();
    }

    private ScanReport report(List<Candidate> candidates, ExternalPackService.Result ext,
                              DatapackLocator.Snapshot snapshot, boolean changed) {
        int withAssets = 0, dataOnly = 0, errors = 0;
        List<String> lines = new ArrayList<>();
        for (Candidate c : candidates) {
            DatapackInfo i = c.info();
            switch (i.kind()) {
                case UNREADABLE, LIMIT_EXCEEDED -> {
                    errors++;
                    lines.add("error|" + c.alias() + "|" + i.detail());
                }
                case NOT_A_PACK -> lines.add("error|" + c.alias() + "|not a datapack: " + i.detail());
                default -> {
                    if (i.hasAssets()) {
                        withAssets++;
                        lines.add("merged|" + c.alias() + "|" + i.detail()
                                + (i.enabledOnServer() ? "" : " (not enabled on the server)"));
                    } else {
                        dataOnly++;
                    }
                }
            }
        }
        List<String> folders = snapshot.folders().stream().map(f -> display(f.path())).toList();
        lines.addAll(ext.lines());
        return new ScanReport(candidates.size(), withAssets, dataOnly, ext.pending(), ext.missing(), errors, changed,
                folders, lines);
    }

    private void logScan(List<Candidate> candidates, List<String> discoveredNow, ScanReport report, ScanTrigger trigger) {
        for (Candidate c : candidates) {
            if (!discoveredNow.contains(c.alias())) continue;
            DatapackInfo i = c.info();
            String where = display(i.path());
            switch (i.kind()) {
                case UNREADABLE, LIMIT_EXCEEDED -> logger.warning("[DATAPACK] [validation-error] " + c.alias()
                        + " (" + where + "): " + i.detail());
                case NOT_A_PACK -> logger.debug("[DATAPACK] " + where + " is not a datapack: " + i.detail());
                default -> {
                    if (i.hasAssets()) {
                        logger.datapack("[discovered] " + c.alias() + " ← " + where + " (" + i.detail() + ")"
                                + (i.enabledOnServer() ? "" : " — not enabled on the server"));
                    } else {
                        logger.debug("[DATAPACK] [no-assets] " + where);
                    }
                    if (i.detail() != null && i.detail().contains("nested zip inside nested zip")) {
                        logger.warning("[DATAPACK] [unsupported] " + where + ": nested zip inside nested zip ignored");
                    }
                }
            }
        }
        if (report.changed() || trigger == ScanTrigger.MANUAL || trigger == ScanTrigger.STARTUP) {
            logger.datapack(report.datapacks() + " datapack(s) in " + report.folders().size() + " folder(s): "
                    + report.withAssets() + " with a resource pack, " + report.dataOnly() + " data-only"
                    + (report.errors() > 0 ? ", " + report.errors() + " unreadable" : ""));
            if (report.dataOnly() > 0) {
                logger.debug("[DATAPACK] [no-assets] " + report.dataOnly() + " data-only datapack(s)");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Staging (inside the merge)
    // -------------------------------------------------------------------------

    /**
     * Stages the resource-pack part of every datapack found by the last scan. Local disk
     * only, no network; runs inside the async merge. Without a previous scan, a local
     * scan without the server thread is run first.
     *
     * @return the number of packs (re)staged this pass
     */
    public int stageAll() {
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isDatapacksEnabled()) {
            lastStage = null;
            for (String alias : stager().clear()) {
                logger.datapack("removed staged pack '" + alias + "' (datapacks.enabled is false)");
            }
            return 0;
        }
        ScanState state;
        synchronized (scanLock) {
            if (lastScan == null) {
                DatapackLocator.Snapshot s = locator.current();
                runScan(s.takenAt().equals(java.time.Instant.EPOCH) ? locator.fallbackSnapshot() : s,
                        ScanTrigger.STARTUP, false);
            }
            state = lastScan;
        }
        List<DatapackStager.StageInput> inputs = new ArrayList<>();
        for (Candidate c : state.candidates()) {
            inputs.add(new DatapackStager.StageInput(c.alias(), c.info(), display(c.info().path()), false));
        }
        // Approved external packs, only when the approval still stands and the cached
        // download is the exact approved file (its SHA-512 was checked when it was inspected).
        for (ExternalPackService.External x : state.externals()) {
            if (x.info() == null || !cfg.isModrinthEnabled()) continue;
            ExternalPackStore.Entry now = external.store().get(x.entry().id()).orElse(null);
            if (now == null || now.state() != ExternalPackStore.State.APPROVED) continue;
            if (!now.sha512().equals(x.info().sha512())) continue;
            inputs.add(new DatapackStager.StageInput(x.alias(), x.info(),
                    "Modrinth " + now.projectName() + " " + now.versionNumber() + " (" + now.id() + ")", true));
        }
        // Same fallback as the merge engine: a Minecraft version newer than the registry
        // still gets a pack.mcmeta in a staged hybrid pack.
        int format = PackFormatRegistry.forMinecraftVersion(state.snapshot().minecraftVersion());
        if (format < 0) format = PackFormatRegistry.newestKnown();
        DatapackStager.Report report = stager().run(inputs, format, cfg.isDatapacksOnlyEnabled());
        lastStage = report;
        for (DatapackStager.Outcome o : report.outcomes()) {
            switch (o.status()) {
                case STAGED -> {
                    logger.datapack("staged '" + o.alias() + "' ← " + o.sourceLabel() + " (" + o.detail() + ")");
                    if (o.detail() != null && o.detail().contains(DatapackAssetExtractor.NOTE_PREFIX)) {
                        logger.warning("[DATAPACK] '" + o.alias() + "' was changed while staging: "
                                + o.detail().substring(o.detail().indexOf(DatapackAssetExtractor.NOTE_PREFIX)));
                    }
                }
                case UNCHANGED -> logger.debug("[DATAPACK] '" + o.alias() + "' unchanged, reusing the staged copy");
                case DEFERRED -> logger.datapack(o.sourceLabel() + ": " + o.detail());
                case DUPLICATE -> logger.debug("[DATAPACK] '" + o.alias() + "' skipped: " + o.detail());
                case SKIPPED_NOT_ENABLED -> logger.debug("[DATAPACK] '" + o.alias() + "' skipped: " + o.detail());
                case INVALID, LIMIT_EXCEEDED, FAILED -> logger.warning("[DATAPACK] [validation-error] "
                        + o.alias() + " (" + o.sourceLabel() + "): " + o.detail());
                default -> { }
            }
        }
        for (String alias : report.pruned()) {
            logger.datapack("removed stale staged pack '" + alias + "' (datapack removed, changed to data-only, or disabled)");
        }
        return (int) report.outcomes().stream().filter(o -> o.status() == DatapackStager.Status.STAGED).count();
    }

    // -------------------------------------------------------------------------
    // Views for commands
    // -------------------------------------------------------------------------

    /** @return the datapacks found by the last scan (empty before the first scan) */
    public List<Candidate> candidates() {
        ScanState s = lastScan;
        return s == null ? List.of() : s.candidates();
    }

    /** @return the folders the last scan looked at */
    public List<DatapackLocator.Folder> folders() {
        ScanState s = lastScan;
        return s == null ? List.of() : s.snapshot().folders();
    }

    /** @return aliases merged from datapacks on the last staging pass */
    public List<String> getMergedAliases() {
        DatapackStager.Report r = lastStage;
        return r == null ? List.of() : r.mergedAliases();
    }

    /** @return every datapack source with its state, for {@code /pm sources} */
    public List<SourceView> sources() {
        List<SourceView> out = new ArrayList<>();
        DatapackStager.Report stage = lastStage;
        for (Candidate c : candidates()) {
            DatapackInfo i = c.info();
            DatapackStager.Outcome o = stage == null ? null : stage.outcome(c.alias());
            String state;
            if (o != null) {
                state = switch (o.status()) {
                    case STAGED, UNCHANGED -> "merged";
                    case DEFERRED -> o.merged() ? "merged (update pending)" : "waiting for the upload to finish";
                    case DUPLICATE -> "duplicate";
                    case DATA_ONLY -> "data-only";
                    case SKIPPED_NOT_ENABLED -> c.folder().loadedByServer() ? "not enabled" : "folder not loaded by the server";
                    case NOT_A_PACK -> "not a pack";
                    case INVALID, LIMIT_EXCEEDED, FAILED -> "error";
                };
            } else {
                state = switch (i.kind()) {
                    case UNREADABLE, LIMIT_EXCEEDED -> "error";
                    case NOT_A_PACK -> "not a pack";
                    default -> i.hasAssets() ? "found (not merged yet)" : "data-only";
                };
            }
            String detail = o != null && o.detail() != null ? o.detail() : i.detail();
            out.add(new SourceView(c.alias(), c.alias(), "datapack", display(i.path()), state, detail));
        }
        ScanState s = lastScan;
        if (s != null) {
            for (ExternalPackService.External x : s.externals()) {
                ExternalPackStore.Entry e = external.store().get(x.entry().id()).orElse(x.entry());
                DatapackStager.Outcome o = stage == null ? null : stage.outcome(x.alias());
                String state = switch (e.state()) {
                    case PENDING -> "pending approval";
                    case REJECTED -> "rejected";
                    case APPROVED -> o != null && o.merged() ? "merged"
                            : x.info() == null ? "approved, not downloaded" : "approved";
                };
                out.add(new SourceView(e.id(), x.alias(), "modrinth",
                        "Modrinth " + e.projectName() + " " + e.versionNumber(), state,
                        "for " + e.datapack() + " — " + e.reason()));
            }
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // External packs: approval
    // -------------------------------------------------------------------------

    /** @return the approval store */
    public ExternalPackStore externalStore() {
        return external.store();
    }

    /** @return entries found by the last scan that wait for approval */
    public List<ExternalPackStore.Entry> pendingEntries() {
        ScanState s = lastScan;
        if (s == null) return List.of();
        List<ExternalPackStore.Entry> out = new ArrayList<>();
        for (ExternalPackService.External x : s.externals()) {
            external.store().get(x.entry().id())
                    .filter(e -> e.state() == ExternalPackStore.State.PENDING)
                    .filter(e -> out.stream().noneMatch(o -> o.id().equals(e.id())))
                    .ifPresent(out::add);
        }
        return out;
    }

    /**
     * Approves an entry, downloads it (verified) and re-scans, which requests a merge.
     * Runs async; completes with the download result, or {@code null} for an unknown id.
     */
    public CompletableFuture<sh.pcx.packmerger.remote.FetchResult> approve(String id, String by) {
        if (!external.store().approve(id, by)) return CompletableFuture.completedFuture(null);
        ExternalPackStore.Entry e = external.store().get(id).orElseThrow();
        logger.datapack("approved " + e.id() + " (" + e.projectName() + " " + e.versionNumber() + ") by " + by);
        return CompletableFuture.supplyAsync(() -> {
            sh.pcx.packmerger.remote.FetchResult r = external.download(e);
            scan(ScanTrigger.MANUAL, false);
            return r;
        });
    }

    /** Rejects an entry and re-scans (a staged copy is removed by the next merge). @return false if unknown */
    public boolean reject(String id, String by) {
        if (!external.store().reject(id, by)) return false;
        logger.datapack("rejected " + id + " by " + by);
        scan(ScanTrigger.MANUAL, false);
        return true;
    }

    /** @return e.g. {@code "3 merged, 10 data-only, 1 error"} */
    public String statusLine() {
        List<SourceView> s = sources();
        if (s.isEmpty()) return lastScan == null ? "not scanned yet" : "none found";
        long merged = s.stream().filter(v -> v.state().startsWith("merged")).count();
        long pending = s.stream().filter(v -> v.state().equals("pending approval")).count();
        long dataOnly = s.stream().filter(v -> v.state().equals("data-only")).count();
        long errors = s.stream().filter(v -> v.state().equals("error")).count();
        long notLoaded = s.stream().filter(v -> v.state().startsWith("not enabled")
                || v.state().startsWith("folder not loaded")).count();
        StringBuilder sb = new StringBuilder();
        sb.append(merged).append(" merged, ").append(dataOnly).append(" data-only");
        if (notLoaded > 0) sb.append(", ").append(notLoaded).append(" not enabled");
        if (pending > 0) sb.append(", ").append(pending).append(" pending approval");
        if (errors > 0) sb.append(", ").append(errors).append(" error").append(errors == 1 ? "" : "s");
        return sb.toString();
    }

    /** @return a path relative to the server folder when possible */
    static String display(Path p) {
        try {
            return DatapackLocator.serverRoot().relativize(p.toAbsolutePath()).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return p.toString();
        }
    }

    // -------------------------------------------------------------------------
    // Persisted inspection cache (cache/datapacks/state.json)
    // -------------------------------------------------------------------------

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * What a persisted inspection depends on besides the file: the reading limits and
     * the plugin version (a newer version may classify differently).
     */
    private String stateKey() {
        return stateKey(plugin.getConfigManager().getZipLimits(), plugin.getLoader().getPluginMeta().getVersion());
    }

    static String stateKey(ZipLimits limits, String pluginVersion) {
        return "v2|" + pluginVersion + "|" + limits;
    }

    /** Restores inspection results so a restart does not re-hash unchanged datapacks. */
    private void loadState() {
        if (!Files.isRegularFile(stateFile)) return;
        try {
            Map<Path, DatapackInfo> restored = parseState(Files.readString(stateFile, StandardCharsets.UTF_8), stateKey());
            if (restored.isEmpty()) {
                logger.debug("[DATAPACK] " + stateFile.getFileName() + " was written with other limits or by "
                        + "another version; datapacks are inspected again");
            }
            infoCache.putAll(restored);
        } catch (IOException | RuntimeException e) {
            logger.debug("[DATAPACK] ignoring unreadable " + stateFile.getFileName() + ": " + e.getMessage());
            infoCache.clear();
        }
    }

    /**
     * @return the inspections stored in {@code json}, or none if it was written under
     *         another {@link #stateKey} (other limits, other plugin version)
     */
    static Map<Path, DatapackInfo> parseState(String json, String key) {
        Map<Path, DatapackInfo> out = new LinkedHashMap<>();
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("datapacks")) return out;
        JsonObject r = root.getAsJsonObject();
        if (!r.has("key") || !key.equals(r.get("key").getAsString())) return out;
        for (Map.Entry<String, JsonElement> e : r.getAsJsonObject("datapacks").entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            Path p = Path.of(e.getKey());
            DatapackKind kind = DatapackKind.valueOf(o.get("kind").getAsString());
            if (kind == DatapackKind.UNREADABLE || kind == DatapackKind.LIMIT_EXCEEDED) continue;
            out.put(p, new DatapackInfo(p, DatapackLocator.packId(p), false,
                    o.get("directory").getAsBoolean(), kind,
                    AssetLayout.valueOf(o.get("layout").getAsString()),
                    str(o, "assetRoot"), o.get("assetFiles").getAsInt(),
                    o.get("fingerprint").getAsString(), str(o, "sha1"), str(o, "sha512"), str(o, "detail")));
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private void saveState() {
        writeAtomically(stateFile, stateJson(infoCache, stateKey()));
    }

    /**
     * @return the persisted form of {@code infos}; unreadable and over-limit results are
     *         left out, so they are inspected again after a restart (or a limit change)
     */
    static String stateJson(Map<Path, DatapackInfo> infos, String key) {
        JsonObject packs = new JsonObject();
        for (Map.Entry<Path, DatapackInfo> e : infos.entrySet()) {
            DatapackInfo i = e.getValue();
            if (i.kind() == DatapackKind.UNREADABLE || i.kind() == DatapackKind.LIMIT_EXCEEDED) continue;
            JsonObject o = new JsonObject();
            o.addProperty("directory", i.directory());
            o.addProperty("kind", i.kind().name());
            o.addProperty("layout", i.layout().name());
            o.addProperty("assetRoot", i.assetRoot());
            o.addProperty("assetFiles", i.assetFiles());
            o.addProperty("fingerprint", i.fingerprint());
            o.addProperty("sha1", i.sha1());
            o.addProperty("sha512", i.sha512());
            o.addProperty("detail", i.detail());
            packs.add(e.getKey().toString(), o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 2);
        root.addProperty("key", key);
        root.add("datapacks", packs);
        return GSON.toJson(root);
    }

    /** Writes a small JSON file via a temp file and an atomic move; failures are logged at debug. */
    void writeAtomically(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            SafeZip.moveAtomic(tmp, file);
        } catch (IOException e) {
            logger.debug("[DATAPACK] could not write " + file.getFileName() + ": " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Watchers
    // -------------------------------------------------------------------------

    /** Seconds between fingerprint polls for folders the OS watcher cannot watch. */
    private static final long POLL_SECONDS = 60;

    /** Guards the watchers; separate from the scan locks so stopping never waits for a scan. */
    private final Object watcherLock = new Object();
    private final List<FileWatcher> watchers = new ArrayList<>();
    private List<Path> watchedFolders = List.of();
    private ScheduledTask pollTask;

    /**
     * Starts one {@link FileWatcher} per datapack folder of the last scan (if
     * {@code datapacks.watch}), plus a 60 s fingerprint poll as fallback for folders the
     * OS cannot watch. A settled change runs {@code scan(WATCHER)}, which requests a
     * merge only if a datapack really changed. Unchanged folders keep their watchers.
     * Does nothing once the plugin is disabled (a scan finishing late must not start
     * threads for an unloaded plugin).
     */
    public void startWatchers() {
        synchronized (watcherLock) {
            startWatchersLocked();
        }
    }

    private void startWatchersLocked() {
        ConfigManager cfg = plugin.getConfigManager();
        if (closed || !plugin.getLoader().isEnabled() || !cfg.isDatapacksEnabled() || !cfg.isDatapacksWatch()) {
            stopWatchers();
            return;
        }
        List<Path> folders = folders().stream().map(DatapackLocator.Folder::path).toList();
        if (folders.equals(watchedFolders) && !watchers.isEmpty()) return;
        stopWatchers();
        int n = 0;
        for (Path folder : folders) {
            FileWatcher w = new FileWatcher(plugin, folder, "datapacks-" + (++n), DatapackLocator::isRelevantChange,
                    () -> Math.max(1, plugin.getConfigManager().getHotReloadDebounceSeconds()),
                    () -> scan(ScanTrigger.WATCHER, false));
            w.start();
            watchers.add(w);
        }
        watchedFolders = folders;
        pollTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin.getLoader(), t -> pollIfDegraded(),
                POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
        if (!folders.isEmpty()) {
            logger.debug("[DATAPACK] watching " + folders.size() + " datapack folder(s)");
        }
    }

    /** Stops every datapack folder watcher and the fallback poll. Safe to call when none runs. */
    public void stopWatchers() {
        synchronized (watcherLock) {
            for (FileWatcher w : watchers) w.stop();
            watchers.clear();
            watchedFolders = List.of();
            if (pollTask != null) {
                pollTask.cancel();
                pollTask = null;
            }
        }
    }

    /** Restarts the watchers once the running scan (if any) has a new folder list. */
    public void restartWatchersLater() {
        CompletableFuture<ScanReport> running;
        synchronized (scanStateLock) {
            running = runningScan;
        }
        if (running == null || running.isDone()) {
            startWatchers();
        } else {
            running.whenComplete((r, t) -> startWatchers());
        }
    }

    /** Fallback for unwatchable folders: re-scan when a datapack's fingerprint changed. */
    private void pollIfDegraded() {
        try {
            boolean degraded;
            synchronized (watcherLock) {
                degraded = watchers.stream().anyMatch(FileWatcher::isDegraded);
            }
            if (!degraded || isScanning() || plugin.isMerging()) return;
            ScanState s = lastScan;
            if (s == null || !quickSignature(s.snapshot().folders()).equals(quickSignature(s))) {
                logger.debug("[DATAPACK] poll found a change in an unwatched datapack folder");
                scan(ScanTrigger.WATCHER, false);
            }
        } catch (RuntimeException e) {
            logger.debug("[DATAPACK] poll failed: " + e.getMessage());
        }
    }

    /** Paths and fingerprints of every candidate in the folders (no reading of zip contents). */
    static String quickSignature(List<DatapackLocator.Folder> folders) {
        StringBuilder sb = new StringBuilder();
        for (DatapackLocator.Folder f : folders) {
            for (Path p : DatapackLocator.candidates(f.path())) {
                sb.append(p).append('=').append(PluginPackStager.fingerprint(p.toFile())).append('\n');
            }
        }
        return sb.toString();
    }

    private static String quickSignature(ScanState s) {
        StringBuilder sb = new StringBuilder();
        for (Candidate c : s.candidates()) {
            sb.append(c.info().path()).append('=').append(c.info().fingerprint()).append('\n');
        }
        return sb.toString();
    }

    /** @return {@code true} while a scan runs */
    public boolean isScanning() {
        synchronized (scanStateLock) {
            return scanning;
        }
    }
}
