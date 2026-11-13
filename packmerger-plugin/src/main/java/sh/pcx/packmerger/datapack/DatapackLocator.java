package sh.pcx.packmerger.datapack;

import org.bukkit.Bukkit;
import org.bukkit.World;
import sh.pcx.packmerger.PackMergerBootstrap;
import sh.pcx.packmerger.config.ConfigManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Finds the folders that may hold datapacks and which datapacks the server has
 * enabled. The Bukkit part ({@link #takeSnapshot()}) runs on the global region
 * thread and only lists worlds and pack ids; everything that touches the disk
 * beyond that is static and unit-tested.
 *
 * <p>Paper 26.x loads datapacks only from {@code <level-name>/datapacks/}; a
 * {@code datapacks/} folder inside another (plugin-created) world is scanned and
 * reported, but marked {@link Folder#loadedByServer()} {@code false}. Because it is
 * not documented what {@code World#getWorldFolder()} returns for the nether and the
 * end on 26.x (the level root or {@code dimensions/minecraft/the_nether}), each
 * world folder is walked up to the nearest folder containing {@code level.dat}.</p>
 */
public final class DatapackLocator {

    /** File-name endings of uploads in progress; such files are never datapack candidates. */
    static final List<String> PARTIAL_SUFFIXES = List.of(
            ".part", ".tmp", ".crdownload", ".filepart", ".!ut", ".download");

    /**
     * @param folders           existing folders, deduplicated by real path, level folder first
     * @param enabledPackIds    ids of enabled packs (e.g. {@code file/terralith.zip}); empty if unknown
     * @param enabledStateKnown {@code false} when the datapack API was unavailable
     * @param minecraftVersion  the running Minecraft version
     * @param takenAt           when the snapshot was taken
     */
    public record Snapshot(List<Folder> folders, Set<String> enabledPackIds, boolean enabledStateKnown,
                           String minecraftVersion, Instant takenAt) {
        static Snapshot empty() {
            return new Snapshot(List.of(), Set.of(), false, null, Instant.EPOCH);
        }
    }

    /**
     * @param path           the folder
     * @param origin         {@code "level"}, {@code "world:<key>"} or {@code "extra"}
     * @param loadedByServer whether the server loads datapacks from it (only the level folder)
     */
    public record Folder(Path path, String origin, boolean loadedByServer) {}

    /** One loaded world as seen on the server thread. */
    public record WorldDir(String key, Path folder) {}

    private final PackMergerBootstrap plugin;
    private volatile Snapshot current = Snapshot.empty();

    public DatapackLocator(PackMergerBootstrap plugin) {
        this.plugin = plugin;
    }

    /** @return the latest snapshot, or an empty one before the first refresh. Thread-safe. */
    public Snapshot current() {
        return current;
    }

    /**
     * Lists worlds and enabled datapacks. Must run on the global region (main) thread.
     * Cheap: only lists folders.
     */
    public Snapshot takeSnapshot() {
        if (!Bukkit.isGlobalTickThread() && !Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("DatapackLocator.takeSnapshot must run on the global region thread");
        }
        List<WorldDir> worlds = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            try {
                worlds.add(new WorldDir(w.getKey().asString(), w.getWorldFolder().toPath()));
            } catch (RuntimeException e) {
                plugin.getPluginLogger().debug("[DATAPACK] cannot read the folder of world " + w.getName()
                        + ": " + e.getMessage());
            }
        }
        Set<String> enabled = new HashSet<>();
        boolean known;
        try {
            for (var pack : Bukkit.getDatapackManager().getEnabledPacks()) enabled.add(pack.getName());
            known = true;
        } catch (Throwable t) {
            // Folia or an API change: treat every pack in the level folder as loaded.
            plugin.getPluginLogger().debug("[DATAPACK] datapack API unavailable (" + t + "); "
                    + "treating the level folder's datapacks as enabled");
            enabled.clear();
            known = false;
        }
        Snapshot s = build(worlds, enabled, known, minecraftVersion());
        current = s;
        return s;
    }

    /** Schedules {@link #takeSnapshot()} on the global region thread; completes with the new snapshot. */
    public CompletableFuture<Snapshot> refreshAsync() {
        CompletableFuture<Snapshot> f = new CompletableFuture<>();
        try {
            Bukkit.getGlobalRegionScheduler().run(plugin.getLoader(), t -> {
                try {
                    f.complete(takeSnapshot());
                } catch (Throwable e) {
                    f.completeExceptionally(e);
                }
            });
        } catch (RuntimeException e) {
            f.completeExceptionally(e); // plugin disabled meanwhile
        }
        return f;
    }

    /**
     * A snapshot without the server thread: only {@code <level-name>/datapacks} from
     * {@code server.properties} and the extra folders; enabled state unknown. Used when
     * the first tick does not come within the startup timeout.
     */
    public Snapshot fallbackSnapshot() {
        Snapshot s = build(List.of(), Set.of(), false, minecraftVersion());
        current = s;
        return s;
    }

    private Snapshot build(List<WorldDir> worlds, Set<String> enabled, boolean known, String mcVersion) {
        ConfigManager cfg = plugin.getConfigManager();
        String levelName = ConfigManager.readServerProperties().getProperty("level-name", "world");
        if (levelName == null || levelName.isBlank()) levelName = "world";
        Path container;
        try {
            container = Bukkit.getWorldContainer().toPath();
        } catch (RuntimeException e) {
            container = serverRoot();
        }
        List<Folder> folders = collect(serverRoot(), container, levelName, worlds, cfg.getDatapacksExtraFolders());
        return new Snapshot(List.copyOf(folders), Set.copyOf(enabled), known, mcVersion, Instant.now());
    }

    static Path serverRoot() {
        return Path.of("").toAbsolutePath();
    }

    private static String minecraftVersion() {
        try {
            return Bukkit.getMinecraftVersion();
        } catch (Throwable t) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Pure helpers (unit-tested without Bukkit)
    // -------------------------------------------------------------------------

    /**
     * Walks up from a world folder (at most 4 levels, never above {@code serverRoot})
     * to the nearest folder containing {@code level.dat}.
     *
     * @return that folder, or {@code worldFolder} itself if none is found
     */
    static Path levelRoot(Path worldFolder, Path serverRoot) {
        Path start = worldFolder.toAbsolutePath().normalize();
        Path stop = serverRoot == null ? null : serverRoot.toAbsolutePath().normalize();
        Path p = start;
        for (int i = 0; i <= 4 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("level.dat"))) return p;
            if (p.equals(stop)) break;
            p = p.getParent();
        }
        return start;
    }

    /**
     * Builds the candidate folder list: the level's {@code datapacks/} first, then for
     * every world {@code levelRoot(world)/datapacks} and {@code world/datapacks}, then the
     * extra folders. Only existing folders are kept, deduplicated by real path; only the
     * level folder is {@code loadedByServer}.
     *
     * @param serverRoot     the server folder (extra folders are relative to it)
     * @param worldContainer where worlds live ({@code Bukkit.getWorldContainer()})
     * @param levelName      {@code level-name} from server.properties
     */
    static List<Folder> collect(Path serverRoot, Path worldContainer, String levelName,
                                List<WorldDir> worlds, List<String> extraFolders) {
        List<Folder> raw = new ArrayList<>();
        Path container = worldContainer == null ? serverRoot : worldContainer;
        raw.add(new Folder(container.resolve(levelName).resolve("datapacks"), "level", true));
        for (WorldDir w : worlds) {
            String origin = "world:" + w.key();
            raw.add(new Folder(levelRoot(w.folder(), serverRoot).resolve("datapacks"), origin, false));
            raw.add(new Folder(w.folder().resolve("datapacks"), origin, false));
        }
        for (String extra : extraFolders) {
            Path p;
            try {
                p = Path.of(extra);
            } catch (RuntimeException e) {
                continue; // invalid path in config
            }
            raw.add(new Folder(p.isAbsolute() ? p : serverRoot.resolve(p), "extra", false));
        }
        List<Folder> out = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (Folder f : raw) {
            if (!Files.isDirectory(f.path())) continue;
            Path real;
            try {
                real = f.path().toRealPath();
            } catch (IOException e) {
                continue;
            }
            if (!seen.add(real)) continue;
            out.add(new Folder(real, f.origin(), f.loadedByServer()));
        }
        return out;
    }

    /**
     * @return {@code true} for names that may be datapacks: not hidden, not an upload
     *         in progress; zips by extension, folders by any name
     */
    public static boolean isCandidateName(String name, boolean directory) {
        if (name == null || name.isEmpty() || name.startsWith(".")) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String s : PARTIAL_SUFFIXES) if (lower.endsWith(s)) return false;
        return directory || lower.endsWith(".zip");
    }

    /**
     * Watcher filter: whether a change at {@code relative} (relative to a datapacks
     * folder) may affect a datapack. Hidden files/folders and uploads in progress
     * ({@code .part}, {@code .crdownload}, …) are ignored; the finished file's rename
     * is a change of its own.
     */
    public static boolean isRelevantChange(Path relative) {
        if (relative == null || relative.toString().isEmpty()) return false;
        for (Path segment : relative) {
            if (segment.toString().startsWith(".")) return false;
        }
        String first = relative.getName(0).toString().toLowerCase(Locale.ROOT);
        String last = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String s : PARTIAL_SUFFIXES) {
            if (first.endsWith(s) || last.endsWith(s)) return false;
        }
        return true;
    }

    /** @return the datapack candidates (zips and folders) directly inside a folder, sorted */
    public static List<Path> candidates(Path folder) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(folder)) {
            s.sorted().forEach(p -> {
                boolean dir = Files.isDirectory(p);
                if (isCandidateName(p.getFileName().toString(), dir)) out.add(p);
            });
        } catch (IOException e) {
            // Folder vanished or unreadable: no candidates this scan.
        }
        return out;
    }

    /**
     * Whether {@code path} was modified within the last {@code windowMillis}: an unreadable
     * zip that fresh is an upload in progress (FTP, panel file manager), not a broken
     * datapack. A timestamp in the future or an unreadable one counts as settled, so a
     * broken file is never hidden for good.
     */
    public static boolean isStillBeingWritten(Path path, long windowMillis, long nowMillis) {
        try {
            long age = nowMillis - Files.getLastModifiedTime(path).toMillis();
            return age >= 0 && age < windowMillis;
        } catch (IOException e) {
            return false;
        }
    }

    /** @return the server's id for a datapack file or folder: {@code file/<name>} */
    public static String packId(Path candidate) {
        return "file/" + candidate.getFileName();
    }
}
