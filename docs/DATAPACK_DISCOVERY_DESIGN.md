# Datapack Resource-Pack Discovery — Design

Status: **implemented (stages 1–6), not yet verified on a live server** — see §13 for
deviations and open points. Target: PackMerger 1.4.0 (config-version 7).
Scope: find the resource packs that belong to installed datapacks (embedded, or
published separately on Modrinth), merge them with everything PackMerger already
merges, host the result and send it to vanilla clients — with as little manual
config as possible.

All `file:line` references are to the tree at commit `f83711d` (master). Paths are
relative to `packmerger-plugin/src/main/java/sh/pcx/packmerger/` unless they start
with a module or `docs/`.

---

## 1. Summary of decisions

1. **Extend PackMerger** (new package `sh.pcx.packmerger.datapack`), do not write a
   companion plugin. See §3.
2. Datapack assets are **staged** into `packs/.datapack-packs/<alias>.zip`, exactly
   like plugin packs (`packs/.plugin-packs/`), so `PackMergeEngine` merges them
   without new merge code.
3. Staged zips contain **only** `assets/**`, `pack.png` and a resource-pack
   `pack.mcmeta`. A datapack's `data/**` and its own (data-format) `pack.mcmeta` are
   never copied.
4. Modrinth is resolved **only by file hash** (`sha1` of the datapack zip), then by the
   version's own resource-pack files and its declared dependencies. No name matching.
5. Every external download needs **admin approval** (pending list,
   `/pm sources approve`), unless the project is listed in `trusted-projects` or
   `auto-approve: true`. Downloads are HTTPS-only, host-allowlisted on every redirect
   hop, size-capped, time-limited and verified against Modrinth's `sha512`.
6. Network work happens in the **scan** step (async), never inside the merge step. The
   merge step only reads local, already-verified files.
7. A merge whose inputs are unchanged is **skipped** (input fingerprint), except for
   `/pm merge` and `/pm rebuild`.
8. Distribution, hosting, rollback, "required pack + kick" are **reused unchanged** —
   they already exist.
9. No new third-party dependencies. Tests use JUnit + `@TempDir` + the JDK
   `com.sun.net.httpserver.HttpServer` (already used by `PackHttpHandlerTest`).

---

## 2. Requirement status (current code)

Legend: ✅ implemented · 🟡 partial · ❌ missing.

| # | Requirement | Status | Evidence | Gap |
|---|---|---|---|---|
| 1a | Scan datapack ZIPs / dirs in all loaded worlds | ❌ | No reference to `getWorlds`, `getWorldFolder`, `datapacks` anywhere in `src/main` (grep). Discovery only reads `packs/` and three stage dirs: `merge/PackMergeEngine.java:335-366` | New locator + stager (§5.1–5.4) |
| 1b | Resource-pack dirs | ✅ | `packs/` folders/zips: `merge/PackMergeEngine.java:340-355`; wrapped zips unwrapped `:521-566` | — |
| 1c | Plugin-generated packs | ✅ | 19 built-in adapters + custom ones: `discovery/PluginPackAdapter.java`, `discovery/PluginPackManager.java:131-187`, config `config.yml:164-205` | — |
| 1d | Configured external sources | 🟡 | `remote-packs:` → `remote/RemotePackManager.java:112-174` (ETag cache, HTTPS guard `:119-124`) | No hash pin, no size cap (`:137` loads the whole body into memory), redirects to any host (`:57`) |
| 1e | Detect RP embedded in datapacks (`assets/` next to `data/`, `resourcepack/` subfolder, nested zip) | ❌ | `PluginPackStager.zipRoot` only knows root / single wrapper folder: `discovery/PluginPackStager.java:634-659` | `DatapackInspector` (§5.2) |
| 1f | Data-only datapacks are normal | ❌ | — | Classified `DATA_ONLY`, logged at debug + one summary line |
| 2 | External deps (Modrinth by hash, approval, allowlist, limits) | ❌ | Only generic URL fetch exists (`remote/RemotePackManager.java`) | §5.6–5.8 |
| 3a | Reuse `PackMergeEngine` | ✅ (reuse) | Engine merges every alias in stage dirs: `merge/PackMergeEngine.java:357-365`, `:387-397` | Add `.datapack-packs` to both lists |
| 3b | Dedupe by hash | 🟡 | Output is deterministic (`merge/DeterministicZip.java:49-56`), SHA compared to live pack `PackMergerBootstrap.java:632-637` | No dedupe of identical sources; identical files still counted as collisions (`merge/MergeProvenance.java:75-77`) |
| 3c | Configurable priority | ✅ | `priority:` + unlisted appended alphabetically: `merge/PackMergeEngine.java:416-456`; `/pm priority` `commands/PackMergerCommand.java:139-157` | Add `datapacks.position` for unlisted datapack aliases |
| 3d | Conflict detection | 🟡 | Provenance per file (`merge/MergeProvenance.java:55-78`), strategy notes (`merge/PackMergeEngine.java:637-647`), `/pm inspect collisions` (`commands/PackMergerCommand.java:591-597`) | No "byte-identical" flag; no datapack-focused view → `/pm conflicts` (§5.10) |
| 3e | Rebuild on change | ✅ | `FileWatcher` on `packs/` (`merge/FileWatcher.java:71-169`), plugin-pack poller (`discovery/PluginPackManager.java:226-257`) | Not watching datapack folders |
| 3f | Skip rebuild when inputs unchanged | 🟡 | Plugin-pack staging skips unchanged sources (`discovery/PluginPackStager.java:233-239`); identical output detected only *after* a full merge (`PackMergerBootstrap.java:633-634`) | Input fingerprint before merging (§5.9) |
| 3g | Never put `data/` into the RP | ❌ (risk today) | A datapack zip dropped into `packs/` is merged whole: every entry goes through `processFile` (`merge/PackMergeEngine.java:477-505`); `zipRoot` accepts any zip with a root `pack.mcmeta` (`discovery/PluginPackStager.java:642`) | Extractor whitelists `assets/`, `pack.png` |
| 4a | Hosting + sending | ✅ | Upload chain/self-host/S3/Polymath `PackMergerBootstrap.java:718-757`; join send `distribution/PackDistributor.java:95-130` | — |
| 4b | Required pack + kick on decline | ✅ | `distribution.required` `config.yml:598`, `enforce-required` `config.yml:604-609`; kick on DECLINED / failed download `distribution/PackDistributor.java:267-284`, `:312-319` | Config only: set both to `true` |
| 5a | Startup scan | 🟡 | Remote fetch before first merge `PackMergerBootstrap.java:259-264`, delayed merge `:308-324` | Add datapack scan to that chain |
| 5b | Manual rescan | 🟡 | `/pm merge` re-stages plugin packs (`commands/PackMergerCommand.java:191-194`) | `/pm scan` |
| 5c | Watch datapack folders (reuse `FileWatcher`) | ❌ | `FileWatcher` is hard-wired to `packs/` and to `mergeAndUpload` (`merge/FileWatcher.java:74`, `:250-254`) | Generalise constructor (§5.9) |
| 5d | Partially uploaded ZIPs | ✅ (plugin packs) | Stability polls `discovery/PluginPackStager.java:243-261`; unreadable zip → invalid `:652-655`; `.part` + atomic move `:401-413` | Reuse rules for datapacks, add "young + unreadable = deferred" |
| 5e | Never publish a half-built pack | ✅ | temp output → validate → rollback/commit `PackMergerBootstrap.java:536-627`; publish only after upload `:718-757` under `publishLock` | — |
| 6 | `/pm scan\|sources\|conflicts\|rebuild\|status` | 🟡 | `status` exists `commands/PackMergerCommand.java:246-325`; `merge`, `inspect`, `fetch` exist `:94-131` | Add `scan`, `sources`, `conflicts`, `rebuild`; extend `status` |
| 6b | Clear log categories | 🟡 | Tags `[MERGE]`, `[UPLOAD]`, `[REMOTE]`… `PluginLogger.java:100-127`; text prefixes like `[plugin-packs]` `discovery/PluginPackManager.java:155-160` | `[DATAPACK]` tag + fixed sub-categories (§5.12) |
| 7a | Zip-slip | 🟡 | `safeName` in stager `discovery/PluginPackStager.java:581-583`; engine only strips leading `/` (`merge/PackMergeEngine.java:704-706`) | Shared `SafeZip` used by the extractor |
| 7b | Size / decompression limits | ❌ | `readAllBytes` per entry, no cap: `merge/PackMergeEngine.java:499-502`, `merge/PackMergeEngine.java:591`; downloads unbounded `remote/RemotePackManager.java:137` | Limits in `SafeZip` and verified download |
| 7c | Timeouts | 🟡 | connect 30 s / request 5 min `remote/RemotePackManager.java:56`, `:130` | Shorter, configurable for Modrinth |
| 7d | Heavy work async, Bukkit API on main thread | ✅ (pattern) | Merge runs in `CompletableFuture.runAsync` `PackMergerBootstrap.java:530`; async scheduler for commands `commands/PackMergerCommand.java:221` | Datapack/world snapshot taken on the global region thread (§7) |
| 7e | No code execution | ✅ | Packs are only read as bytes/JSON | Keep it so (no scripts, no class loading) |
| 8 | Tests | 🟡 | 40+ unit test classes, pure-logic focus (`docs/TESTING.md:1-40`) | New tests §9 |

---

## 3. Extend PackMerger vs. companion plugin

| | Extend PackMerger | Companion plugin using `PackMergerApi` |
|---|---|---|
| Staging / stability / pruning | reuse `PluginPackStager` helpers directly | `registerPack(owner, alias, Path)` exists (`PackMergerBootstrap.java:1278-1283`), stager reused indirectly |
| Datapack-only extraction (`data/` stripped) | inside the stage step | companion must build filtered zips itself |
| Conflicts view, `/pm status`, priority position | direct access to `MergeProvenance`, commands | only `getLastMergeProvenance()`; separate command tree |
| Skip-unchanged merge, watcher reuse | can change `mergeAndUpload` / `FileWatcher` | not possible (API has no such hooks) |
| Upstream maintenance | fork diverges unless upstreamed | no fork |

**Decision: extend.** It is the user's stated preference, the stage-dir mechanism is
built for exactly this, and three required features (input-skip, conflicts, watcher
reuse) need internals. To keep the fork mergeable with upstream and suitable for a PR:
all new logic lives in the new package `sh.pcx.packmerger.datapack` plus
`remote/VerifiedDownload*`; changes to existing classes are small and additive
(listed in §6).

---

## 4. Verified platform facts

### 4.1 Paper 26.x world layout (verified on papermc.io/news/26-1)

- Paper 26.1 adopted the vanilla layout. One level folder (`level-name`, default
  `world/`) contains `level.dat`, `datapacks/` and `dimensions/<namespace>/<key>/`
  (`dimensions/minecraft/overworld`, `…/the_nether`, `…/the_end`). The old
  `world_nether/`, `world_the_end/` folders in the server root are gone after
  migration. `paper-world.yml` moved into each dimension folder.
- `WorldInfo#getName` is marked obsolete; use `getKey()`.
- **Datapacks are global to the server**: the server loads them only from
  `<level-name>/datapacks/`. A `datapacks/` folder inside a plugin-created world
  folder (Multiverse etc.) is *not* loaded by the server.
- **Not verified**: what `World#getWorldFolder()` returns on 26.x for the nether/end
  (the level root or the dimension folder) and where plugin-created worlds are stored.
  The locator (§5.1) therefore walks up from each world folder to the nearest
  directory that contains `level.dat`, which works for both answers. Stage 2 must
  confirm on a real 26.2 server and record the result in this doc.

### 4.2 Paper datapack API (verified on jd.papermc.io 26.1.2)

- `Bukkit.getDatapackManager()` → `DatapackManager`: `getPacks()`,
  `getEnabledPacks()`, `getPack(String)`, `refreshPacks()`.
- `Datapack`: `getName()`, `isEnabled()`, `getSource()`, `getTitle()`,
  `getDescription()`, `getCompatibility()`, `isRequired()`, `computeDisplayName()`.
  **No file path.** Folder packs are named `file/<file or folder name>` (vanilla
  convention), which lets us map a file in `world/datapacks/` to its enabled state.
- Thread-safety is not documented → call it only on the global region thread and
  cache a snapshot.
- Datapacks that plugins add through the Paper lifecycle API live inside plugin jars
  and have no path: **out of scope** (cannot be read safely; the plugin can register
  its RP through `PackMergerApi` instead).

### 4.3 Modrinth API v2 (verified on docs.modrinth.com; no live calls were made)

- Base URL `https://api.modrinth.com/v2`. Public reads need no token.
- **A unique `User-Agent` is mandatory**; generic library agents may be blocked.
- Rate limit **300 requests/min per IP**; headers `X-Ratelimit-Limit`,
  `X-Ratelimit-Remaining`, `X-Ratelimit-Reset` (seconds).
- `GET /version_file/{hash}?algorithm=sha1|sha512[&multiple=true]` → version.
- `POST /version_files` body `{"hashes":[…],"algorithm":"sha1"}` → map hash → version
  (one request for all datapacks).
- Version object: `id`, `project_id`, `version_number`, `version_type`,
  `game_versions[]`, `loaders[]`, `date_published`, `dependencies[]`
  (`version_id?`, `project_id?`, `file_name?`, `dependency_type` ∈ `required`,
  `optional`, `incompatible`, `embedded`), `files[]` (`url`, `filename`, `primary`,
  `size`, `hashes.sha1`, `hashes.sha512`, `file_type?` ∈ `required-resource-pack`,
  `optional-resource-pack`, `sources-jar`, `dev-jar`, `javadoc-jar`, `signature`,
  `unknown`).
- `GET /project/{id|slug}/version?game_versions=["26.2"]&loaders=["minecraft"]&include_changelog=false`
  (filters are JSON-array strings).
- `GET /version/{id}`, `GET /project/{id|slug}` (gives `project_type`, `slug`, `title`).
- Files are served from `cdn.modrinth.com`.

**Honest limitations** (these are inherent, not fixable in code):

1. Hash lookup only matches the **unmodified** file as uploaded to Modrinth. Extracted
   datapack folders, re-zipped packs, and packs from CurseForge / PlanetMinecraft /
   GitHub produce 404 → reported as "not on Modrinth", nothing is guessed.
2. It only finds what the author **declared**: either an extra file with
   `file_type: required-resource-pack|optional-resource-pack` in the same version, or a
   `dependencies[]` entry pointing to a project whose `project_type` is
   `resourcepack`. Authors who only link the RP in the description are not detectable.
3. A dependency with only `project_id` (no `version_id`) needs a version choice; we pick
   the newest `release` (then `beta`) whose `game_versions` contains the server's
   Minecraft version. If Modrinth's version string for the server differs from
   `Bukkit.getMinecraftVersion()`, nothing matches → reported as missing dependency.
   Fallback: exact match, then the `major.minor` prefix (`26.2.1` → `26.2`).
4. `file_name`-only dependencies (external, non-Modrinth) are reported as
   "unsupported dependency", never fetched.
5. Hashes leave the server (privacy): the config comment says so, and
   `external.modrinth.enabled: false` turns all lookups off.

---

## 5. Design

### 5.0 Overview

```
            scan (async; startup, /pm scan, watcher, world load, /reload)
 ┌───────────────────────────────────────────────────────────────────────┐
 │ DatapackLocator.snapshot()   (global region thread, cached)           │
 │   → folders + enabled pack ids + MC version                           │
 │ DatapackInspector.inspect()  (per candidate, pure)                    │
 │   → layout NONE | ROOT_ASSETS | SUBFOLDER | NESTED_ZIP, sha1/sha512   │
 │ ModrinthResolver (zip datapacks, cached by sha1)                      │
 │   → ExternalPackStore: PENDING / APPROVED / REJECTED                  │
 │ RemotePackManager.fetchVerified() for APPROVED, not yet cached        │
 └───────────────────────────────────────────────────────────────────────┘
                     │ changed? → plugin.requestMerge("datapacks changed")
                     ▼
 mergeAndUpload (existing, async)
   pluginPackManager.stageAll()        (existing)
   apiPackManager.stageAll()           (existing)
   datapackPackManager.stageAll()      NEW – local only, no network
       └ DatapackStager → packs/.datapack-packs/<alias>.zip (assets only)
   MergeInputs.fingerprint() == last?  NEW – skip unless forced
   PackMergeEngine.merge(temp)         (existing; knows .datapack-packs)
   validate → rollback/commit → upload → publish → distribute (existing)
```

### 5.1 `datapack/DatapackLocator` — find candidates (Bukkit side)

Responsibility: build an immutable snapshot of where datapacks are and which the
server has enabled. Runs on the global region thread; result cached in a
`volatile` field.

```java
package sh.pcx.packmerger.datapack;

public final class DatapackLocator {
    public DatapackLocator(PackMergerBootstrap plugin);

    /** Must run on the global region thread. Cheap: only lists folders. */
    public Snapshot takeSnapshot();

    /** Schedules takeSnapshot() on the global region thread; completes with the new snapshot. */
    public CompletableFuture<Snapshot> refreshAsync();

    /** Latest snapshot, or an empty one before the first refresh. Thread-safe. */
    public Snapshot current();

    public record Snapshot(
            List<Folder> folders,          // deduplicated by canonical path
            Set<String> enabledPackIds,    // e.g. "file/terralith.zip"; empty if API unavailable
            boolean enabledStateKnown,     // false on Folia/old API → only-enabled is not applied
            String minecraftVersion,       // Bukkit.getMinecraftVersion()
            Instant takenAt) {}

    public record Folder(Path path, String origin /* "level", "world:<key>", "extra" */,
                         boolean loadedByServer) {}

    // pure helpers (unit-tested without Bukkit)
    static Path levelRoot(Path worldFolder, Path serverRoot);           // walk up ≤4 levels to level.dat
    static List<Folder> collect(Path serverRoot, String levelName,
                                List<Path> worldFolders, List<String> extraFolders);
}
```

Rules:
- Candidates folders: `<serverRoot>/<level-name>/datapacks` (from
  `ConfigManager.readServerProperties()`, `config/ConfigManager.java:588`), then for
  each `Bukkit.getWorlds()` world: `levelRoot(world.getWorldFolder())/datapacks` and the
  legacy `world.getWorldFolder()/datapacks`, then `datapacks.extra-folders`.
  Only existing directories are kept. Dedupe by `toRealPath()`.
- `loadedByServer = true` only for the main level's folder. Others are scanned and
  reported (`/pm sources` shows "folder not loaded by the server") and merged only if
  `datapacks.only-enabled: false`.
- Enabled ids from `getDatapackManager().getEnabledPacks()` → `getName()`. Wrapped in
  `try/catch (Throwable)`: on failure `enabledStateKnown=false`.
- Refresh triggers: enable (first tick), `WorldLoadEvent`, `WorldUnloadEvent`,
  `ServerResourcesReloadedEvent` (after `/minecraft:reload` and `/datapack enable`),
  `/pm scan`.

### 5.2 `datapack/DatapackInspector` — classify one candidate (pure)

```java
public final class DatapackInspector {
    public DatapackInspector(ZipLimits limits);

    /** Never throws; problems become a status. Reads zip central directory only, plus
     *  nested-zip headers (copied to a bounded temp file). */
    public DatapackInfo inspect(Path candidate, String packId, boolean enabledOnServer);
}

public enum AssetLayout { NONE, ROOT_ASSETS, SUBFOLDER, NESTED_ZIP }

public enum DatapackKind { DATAPACK, RESOURCE_PACK_ONLY, NOT_A_PACK, UNREADABLE, LIMIT_EXCEEDED }

public record DatapackInfo(
        Path path, String packId, boolean enabledOnServer, boolean directory,
        DatapackKind kind,
        AssetLayout layout,
        String assetRoot,      // "" for ROOT_ASSETS, "resourcepack/" for SUBFOLDER,
                               // "MyPack RP.zip" (entry name) for NESTED_ZIP, null for NONE
        String fingerprint,    // PluginPackStager.fingerprint(File) (size+mtime / dir walk)
        String sha1, String sha512,   // zip files only, null for directories
        String detail) {}
```

Detection order (first match wins; content-based, file names only break ties
alphabetically):
1. Unwrap a single wrapper folder (same rule as `PluginPackStager.zipRoot`).
2. `data/` present? → `DATAPACK`, else if `assets/` or `pack.mcmeta` present →
   `RESOURCE_PACK_ONLY` (someone put an RP into `datapacks/`; staged like an RP),
   else `NOT_A_PACK`.
3. `assets/` at the pack root → `ROOT_ASSETS`.
4. A top-level directory (≠ `data`, `assets`) containing `pack.mcmeta` or `assets/` →
   `SUBFOLDER`. Typical: `resourcepack/`, `resource_pack/`, `resources/`.
5. A `.zip` entry at depth ≤ 2 whose own root (after unwrap) has `assets/` →
   `NESTED_ZIP`. Only one level of nesting; zips inside the nested zip are ignored.
6. Otherwise `layout = NONE` (data-only; normal).

Hashes are computed only when the fingerprint changed since the last scan (cached in
`cache/datapacks/state.json`, §5.11).

### 5.3 `datapack/SafeZip` + `ZipLimits` — shared safe reading (pure)

```java
public record ZipLimits(long maxFileBytes, long maxTotalUncompressedBytes,
                        long maxEntryBytes, int maxEntries, int maxCompressionRatio) {
    public static ZipLimits fromConfig(ConfigManager cfg);
}

public final class SafeZip {
    /** Normalises a zip entry name; null if unsafe: absolute, drive letter, "..",
     *  backslash tricks, NUL, empty. */
    public static String safeName(String raw);

    /** Iterates entries, enforcing every limit on *actually read* bytes (a lying
     *  header cannot bypass it). Throws ZipLimitException with a readable message. */
    public static void forEach(ZipFile zip, String prefix, ZipLimits limits,
                               EntryVisitor visitor) throws IOException, ZipLimitException;

    /** Copies one entry to a temp file (bounded) — used for nested zips. */
    public static Path extractEntryToTemp(ZipFile zip, ZipEntry entry, Path tempDir,
                                          ZipLimits limits) throws IOException, ZipLimitException;

    public interface EntryVisitor { void visit(String name, InputStream in) throws IOException; }
    public static final class ZipLimitException extends Exception { … }
}
```

Defaults (config §5.13): file 200 MiB, uncompressed total 512 MiB, entry 64 MiB,
50 000 entries, ratio 100:1 (checked per entry when the entry is > 1 MiB).
Only this new code path uses `SafeZip`; the existing engine is unchanged in this
feature (hardening the engine for `packs/` is listed as an optional follow-up, §11).

### 5.4 `datapack/DatapackAssetExtractor` and `DatapackStager` — build staged zips (pure)

```java
public final class DatapackAssetExtractor {
    public DatapackAssetExtractor(ZipLimits limits, List<MergeStrategy> strategies);

    /** Writes a resource pack with only assets/**, pack.png and a resource-pack
     *  pack.mcmeta to {@code dest} (via dest + ".part" and an atomic move).
     *  Output is deterministic (DeterministicZip.entry). */
    public Result extract(DatapackInfo info, int resourcePackFormat, Path dest)
            throws IOException, SafeZip.ZipLimitException;

    public record Result(int files, long bytes, String sha1, boolean mcmetaSynthesized) {}
}
```

`pack.mcmeta` rule — the datapack's root `pack.mcmeta` carries a **data** format and
must never reach the merge (`PackMcmetaMergeStrategy` would widen the merged range with
data-format numbers):
- `SUBFOLDER` / `NESTED_ZIP` / `RESOURCE_PACK_ONLY`: keep that RP's own `pack.mcmeta`
  (incl. its overlays) — it is a real resource-pack mcmeta.
- `ROOT_ASSETS` (hybrid pack): drop the root mcmeta and synthesize
  `{"pack":{"description":"Assets from datapack <name>","min_format":F,"max_format":F,"pack_format":F}}`
  with `F = PackFormatRegistry.forMinecraftVersion(snapshot.minecraftVersion())`
  (`merge/PackFormatRegistry.java:74-82`; 26.2 → 88). If unknown (`-1`), omit the
  mcmeta and log a debug note.
- Never copied: `data/**`, any root file except `pack.png`, junk (same list as the
  engine, `merge/PackMergeEngine.java:78-88`).

```java
public final class DatapackStager {
    public DatapackStager(Path stageDir, DatapackAssetExtractor extractor, int polls,
                          long intervalMillis, PluginPackStager.Sleeper sleeper);

    /** One staging pass over inspected datapacks + verified external files.
     *  Local disk only. Same contract as PluginPackStager.run. */
    public Report run(List<StageInput> inputs, int resourcePackFormat);

    public record StageInput(String alias, DatapackInfo info /* or */, Path verifiedExternalZip,
                             String sourceLabel) {}
    public enum Status { STAGED, UNCHANGED, DEFERRED, DATA_ONLY, SKIPPED_NOT_ENABLED,
                         NOT_A_PACK, INVALID, LIMIT_EXCEEDED, FAILED }
    public record Outcome(String alias, String sourceLabel, Status status, String detail) {
        public boolean merged(); }
    public record Report(List<Outcome> outcomes, List<String> pruned) {}
}
```

Reuses: `PluginPackStager.fingerprint(File)` (make `public`; now package-private,
`discovery/PluginPackStager.java:674`), `PluginPackStager.prune` (`:704`),
`PluginPackStager.Sleeper` (`:64`), the stability-window algorithm (`:243-261`, copied
logic ~20 lines rather than refactoring the tested class).

Partial uploads:
- File names ending in `.part`, `.tmp`, `.crdownload`, `.filepart`, `.!ut`, `.download`
  and dot-files are ignored by the locator.
- Size + mtime must be equal on `polls` readings (`stability.*`, default 3 × 1 s).
- A zip whose central directory cannot be read and whose mtime is < 60 s old →
  `DEFERRED` (previous staged copy kept); older → `INVALID` with "corrupt zip".
- Writes go to `<alias>.zip.part` then atomic move (same as `:401-413`).

Dedupe: two inputs whose extracted output has the same `sha1` (same datapack in two
folders, or two datapacks pulling the same Modrinth RP) are staged once under the
first alias (sorted); the other is reported `UNCHANGED (duplicate of <alias>)`.

Aliases: `dp-<slug>` where slug = file name without `.zip`, lowercased, `[^a-z0-9._-]`
→ `-`, max 48 chars; external: `dp-<slug>-rp`. On collision append `-<first 6 hex of
sha1 of the path>`. Stable across restarts.

### 5.5 `datapack/DatapackPackManager` — orchestrator (Bukkit side)

Mirrors `discovery/PluginPackManager.java`.

```java
public class DatapackPackManager {
    public static final String STAGE_DIR = ".datapack-packs";

    public DatapackPackManager(PackMergerBootstrap plugin);
    public void reconfigure();                       // enable + /pm reload

    /** Full scan: snapshot, inspect, Modrinth lookups, approved downloads.
     *  Async. Requests a merge when anything that affects staging changed. */
    public CompletableFuture<ScanReport> scan(ScanTrigger trigger, boolean forceLookup);

    /** Called by mergeAndUpload before the engine runs. Local disk only, no network. */
    public int stageAll();

    public void startWatchers();                     // one FileWatcher per datapack folder
    public void stopWatchers();

    public List<SourceView> sources();               // for /pm sources and /pm status
    public List<String> getMergedAliases();
    public String statusLine();                      // "3 merged, 11 data-only, 1 pending approval"

    public enum ScanTrigger { STARTUP, MANUAL, WATCHER, WORLD_CHANGE, RESOURCES_RELOADED, RELOAD }
    public record ScanReport(int datapacks, int withAssets, int dataOnly, int pendingApproval,
                             int missingDependencies, int errors, boolean changed) {}
    public record SourceView(String id, String alias, String kind /* datapack|modrinth */,
                             String origin, String state, String detail) {}
}
```

### 5.6 `datapack/modrinth/ModrinthClient` — HTTP, mockable

```java
public final class ModrinthClient {
    public interface Transport {           // production: JDK HttpClient; tests: stub
        Response send(String method, URI uri, String jsonBody, Map<String,String> headers,
                      Duration timeout) throws IOException, InterruptedException;
        record Response(int status, Map<String, List<String>> headers, byte[] body) {}
    }

    public ModrinthClient(URI apiBase, String userAgent, Transport transport,
                          Duration timeout, int maxResponseBytes /* 2 MiB */);

    public Map<String, ModrinthVersion> versionsByHashes(Collection<String> sha1s) throws ModrinthException;
    public ModrinthVersion version(String versionId) throws ModrinthException;
    public ModrinthProject project(String idOrSlug) throws ModrinthException;
    public List<ModrinthVersion> projectVersions(String projectId, String gameVersion,
                                                 String loader) throws ModrinthException;
    public RateLimit lastRateLimit();      // from X-Ratelimit-* headers

    public record RateLimit(int limit, int remaining, int resetSeconds) {}
}

public record ModrinthVersion(String id, String projectId, String versionNumber, String versionType,
        List<String> gameVersions, List<String> loaders, Instant published,
        List<Dependency> dependencies, List<VersionFile> files) {
    public record Dependency(String versionId, String projectId, String fileName, String type) {}
    public record VersionFile(String url, String filename, boolean primary, long size,
                              String sha1, String sha512, String fileType) {}
}
public record ModrinthProject(String id, String slug, String title, String projectType) {}
```

- User-Agent: `pcxgg/PackMerger/<version> (datapack-discovery; +https://github.com/pcxgg/PackMerger)`.
- Parsing with Gson (provided by Paper). Unknown fields ignored. Body > 2 MiB → error.
- HTTP 429 or `remaining < 5` → stop this scan with `RATE_LIMITED`; the next scan
  continues (results already cached are kept). Per-scan budget: 60 requests.
- 404 on lookup → "not on Modrinth" (negative-cached).

### 5.7 `datapack/modrinth/ModrinthResolver` — pure decision logic

```java
public final class ModrinthResolver {
    public ModrinthResolver(ModrinthClient client, boolean includeOptional);

    /** For one datapack hash: which resource-pack files belong to it. */
    public Resolution resolve(String datapackSha1, ModrinthVersion datapackVersion,
                              String minecraftVersion) throws ModrinthException;

    public record Resolution(String datapackSha1, String datapackProjectId, String datapackVersionId,
                             List<Candidate> candidates, List<String> problems) {}

    public record Candidate(String reason,            // "file_type required-resource-pack" | "dependency required"
                            String projectId, String projectSlug, String versionId,
                            String versionNumber, ModrinthVersion.VersionFile file) {}
}
```

Algorithm:
1. Same version, `files[]` with `file_type == "required-resource-pack"` (and
   `"optional-resource-pack"` when `include-optional`) → candidates.
2. `dependencies[]` with `type == required` (and `optional` when `include-optional`);
   skip `embedded`, `incompatible`.
   - `version_id` set → `version(id)`; else `projectVersions(project_id, mc, "minecraft")`
     and pick: newest `release`, then newest `beta`; `alpha` never.
   - `project(project_id).projectType` must be `resourcepack`; otherwise ignored
     (a required *mod* or *datapack* dependency is not our business; reported at debug).
   - `file_name`-only dependency → problem "unsupported external dependency".
   - Pick the version's primary file (first file if none primary). Must end in `.zip`.
3. No candidates and no problems → "no resource pack declared on Modrinth" (debug).
4. Game version check: if a chosen RP version does not list the server version
   (exact or `major.minor` prefix) → problem "no version for <mc>" → missing dependency.

### 5.8 External packs: approval store and verified download

```java
public final class ExternalPackStore {          // datapack/ExternalPackStore.java
    public ExternalPackStore(Path file /* plugins/PackMerger/external-sources.json */);
    public synchronized void load();             // corrupt file → renamed .broken-<ts>, start empty, warn
    public synchronized Entry upsert(Resolution r, Candidate c, String datapackLabel, boolean autoApprove);
    public synchronized Optional<Entry> get(String id);
    public synchronized boolean approve(String id, String by);
    public synchronized boolean reject(String id, String by);
    public synchronized List<Entry> list();
    public synchronized void markDownloaded(String id, Path file);

    public enum State { PENDING, APPROVED, REJECTED }
    public record Entry(String id, String provider, String datapack, String datapackSha1,
                        String projectId, String projectSlug, String versionId, String versionNumber,
                        String fileName, String url, long size, String sha1, String sha512,
                        String reason, State state, String decidedBy, Instant decidedAt,
                        Instant firstSeen, Instant lastSeen) {}
}
```

- `id` = `mr-` + first 8 hex of the file's `sha512` (short, typeable, stable).
- Approval binds to the **exact file hash**. A new datapack/RP version creates a new
  `PENDING` entry, unless `projectId` or `projectSlug` is in `trusted-projects` or
  `auto-approve: true` (then `decidedBy = "auto-approve"`).
- File is written atomically (tmp + move), pretty JSON, `"version": 1`. It is a separate
  file because `saveConfig()` drops all comments in `config.yml`
  (`commands/PackMergerCommand.java:767-773`).

Example `external-sources.json`:

```json
{
  "version": 1,
  "entries": {
    "mr-3f9a1c22": {
      "provider": "modrinth",
      "datapack": "world/datapacks/dungeons-and-taverns-v4.zip",
      "datapackSha1": "5c1d…",
      "projectId": "tpehi7ww", "projectSlug": "dungeons-and-taverns-rp",
      "versionId": "Ab12Cd34", "versionNumber": "4.4.1",
      "fileName": "DnT-RP-4.4.1.zip",
      "url": "https://cdn.modrinth.com/data/tpehi7ww/versions/Ab12Cd34/DnT-RP-4.4.1.zip",
      "size": 2483112, "sha1": "…", "sha512": "3f9a1c22…",
      "reason": "dependency required",
      "state": "PENDING", "decidedBy": null, "decidedAt": null,
      "firstSeen": "2026-10-31T20:10:00Z", "lastSeen": "2026-10-31T20:10:00Z"
    }
  }
}
```

**Verified download** — new method on the existing `remote/RemotePackManager`, backed by
a second `HttpClient` with `Redirect.NEVER` (redirects followed manually, max 3 hops,
every hop must be `https` and its host in `allowed-hosts`):

```java
public record VerifiedSpec(String alias, URI url, Set<String> allowedHosts, long maxBytes,
                           Duration timeout, String expectedSha512, String expectedSha1,
                           long expectedSize, String userAgent) {}

// RemotePackManager
public FetchResult fetchVerified(VerifiedSpec spec, File targetFile);
```

- Rejects `Content-Length > maxBytes` or `> expectedSize` before reading.
- Streams to `<target>.part` with a counting stream that aborts at `maxBytes`.
- Verifies `sha512` (and `sha1`, and size) before the atomic move; mismatch → delete
  `.part`, `FetchResult.Status.ERROR_*` with "hash mismatch".
- Target: `cache/modrinth/<sha512>.zip` (content-addressed; an already-present file
  with the right hash is never downloaded again).
- The downloaded RP then goes through `DatapackAssetExtractor` like any other input
  (limits, zip-slip, `data/` stripped), into `packs/.datapack-packs/dp-<slug>-rp.zip`.

### 5.9 Automation: watcher, input skip, startup

**FileWatcher generalisation** (`merge/FileWatcher.java`): add a second constructor,
keep the old one delegating to it:

```java
public FileWatcher(PackMergerBootstrap plugin);                       // unchanged behaviour
public FileWatcher(PackMergerBootstrap plugin, Path root, String name,
                   Predicate<Path> relevant, IntSupplier debounceSeconds, Runnable onSettled);
```

- `start()` uses `root` instead of `plugin.getPacksFolder()` (`:74`), the thread name
  `PackMerger-FileWatcher-<name>`, and calls `onSettled` instead of `triggerMerge()`
  (`:250-254`). `isRelevant` stays the default predicate.
- Datapack watchers: one per existing datapack folder; predicate ignores dot-files and
  the partial-upload suffixes (§5.4); `onSettled = () -> scan(WATCHER, false)`.
- Fallback when a folder cannot be watched (inotify limit, network FS): the existing
  warning path (`:215-219`) plus a 60 s poll of folder fingerprints.

**Input fingerprint skip** — new `merge/MergeInputs.java`:

```java
public final class MergeInputs {
    /** sha1 over: ordered pack names + each pack's fingerprint (PluginPackStager.fingerprint),
     *  custom pack.mcmeta/pack.png fingerprints, model-policy, compression-level,
     *  strip-junk-files, plugin version. */
    public static String fingerprint(List<String> order, Function<String, File> resolve,
                                     ConfigManager cfg, String pluginVersion);
}
```

- `PackMergeEngine` gets `public List<String> resolvedOrder()` (runs existing
  `discoverPacks` + `buildPackOrder`, `merge/PackMergeEngine.java:335-456`) and
  `public File resolve(String packName)` (wraps `resolvePackFile`, `:387-397`).
- `PackMergerBootstrap.mergeAndUpload(CommandSender, MergeOptions)` new overload;
  the old signature calls it with `MergeOptions.AUTO`.
  `record MergeOptions(boolean force, boolean rescanDatapacks)`.
- After the staging calls (`PackMergerBootstrap.java:543-560`) and before
  `mergeEngine.merge` (`:562`): if `!force` and the fingerprint equals
  `output/<name>.inputs` and the output exists and the published pack's SHA-1 equals
  the output's and `publishedPack.providerGeneration() == providerGeneration` →
  log `[MERGE] inputs unchanged; keeping the live pack` and return.
- The `.inputs` sidecar is written in `commitMerge` (`:929-949`) next to the
  provenance sidecar and rotated the same way.
- `/pm merge` and `/pm rebuild` pass `force = true` (existing `/pm merge` behaviour is
  kept). Watchers, `requestMerge`, the startup merge use `AUTO`.

**Startup**: in `onEnable` (`PackMergerBootstrap.java:256-264`) the `remoteFetch`
future becomes `remoteFetch.thenCompose(v -> datapackPackManager.scan(STARTUP, false))`
(with `exceptionally` logging), so the delayed first merge (`:308-324`) sees staged and
downloaded datapack assets. The locator snapshot is taken on the first tick (same place
as the competing-host scan, `:328-329`); the scan waits for it with a 10 s timeout and
falls back to `level-name/datapacks` from `server.properties` alone.

**Rescan → merge**: `scan()` ends with `plugin.requestMerge("datapacks changed")`
(`PackMergerBootstrap.java:696-705`) when `ScanReport.changed`; `requestMerge`
already coalesces and defers while a merge runs.

### 5.10 Priority, dedupe and conflicts

- `datapacks.position: bottom|top` (default `bottom`): in `buildPackOrder`
  (`merge/PackMergeEngine.java:437-446`) unlisted aliases that are datapack aliases are
  placed after all other unlisted packs (`bottom`) or before the first listed pack
  (`top`). Aliases written into `priority:` always win — `/pm priority` works on them
  unchanged. To make this testable, extract the body into
  `static List<String> orderPacks(List<String> available, List<String> priority,
  ServerPackConfig server, Set<String> datapackAliases, Position pos)`.
- `MergeProvenance.FileRecord` gains `boolean identical` (all contributions were
  byte-identical plain overwrites). Old 5- and 6-arg constructors stay; JSON field is
  optional on read (`merge/MergeProvenance.java:166-203`). `Builder.record(…)` gets an
  overload with `boolean sameBytesAsPrevious`; `PackMergeEngine.processFile`
  (`merge/PackMergeEngine.java:615-619`) compares the previous `mergedFiles.put` value
  with `Arrays.equals`.
- New `commands/ConflictRenderer` (pure, like `InspectRenderer`):
  a **conflict** = `contributors.size() > 1` and (`strategyName == null && !identical`,
  or the record has a strategy warning note). Cleanly merged JSON (lang union, atlas
  concat, …) and identical files are not conflicts. Datapack aliases are tagged `[dp]`.
- After each merge, one log line: `[DATAPACK] [conflicts] 4 file(s) differ between
  packs; dp-terralith lost 3 (see /pm conflicts)` — only when a datapack alias is involved.

### 5.11 Hash and cache strategy

| What | Key | Where | Invalidation |
|---|---|---|---|
| Datapack identity | `fingerprint` (size+mtime / dir walk) | `cache/datapacks/state.json` | recomputed every scan (cheap) |
| Datapack sha1/sha512 | path + fingerprint | same file | only when fingerprint changes |
| Modrinth lookup | datapack `sha1` | `cache/datapacks/modrinth-lookups.json` | `lookup-cache-hours` (24 h), also negative (404) results; `/pm scan --refresh` |
| Downloaded RP | Modrinth `sha512` | `cache/modrinth/<sha512>.zip` | never stale; pruned when no active entry references it for 7 days |
| Staged RP | alias + input fingerprint | `packs/.datapack-packs/<alias>.zip` | restaged when input fingerprint changes; deterministic bytes |
| Merge inputs | `MergeInputs.fingerprint` | `output/<name>.inputs` | any input/config change |

`cache/` = `PackMergerBootstrap.getCacheFolder()` (`PackMergerBootstrap.java:1195-1197`),
outside `packs/`, so the `packs/` watcher never sees cache writes.

### 5.12 Commands, permissions, messages, logging

New literals in `PackMergerCommand.register()` (`commands/PackMergerCommand.java:89-161`);
heavy work in `Bukkit.getAsyncScheduler().runNow(...)` like `handleValidate` (`:221`).

| Command | Behaviour | Reuses |
|---|---|---|
| `/pm scan [refresh]` | `scan(MANUAL, refresh)`; prints the report; triggers a merge if changed | new |
| `/pm sources [datapacks\|pending]` | lists every source: `packs/`, remote, plugin, API, datapack, external, with state | `PluginPackManager.getIntegrationStates()`, `getRegisteredPacks()` |
| `/pm sources info <id>` | full details of one external entry (URL, size, hashes, reason) | new |
| `/pm sources approve <id\|all>` | approve; download async; scan + merge | `fetchVerified` |
| `/pm sources reject <id>` | reject; entry stays (so it is not asked again) | new |
| `/pm conflicts [pack]` | real conflicts only | `MergeProvenance` |
| `/pm rebuild` | `mergeAndUpload(sender, new MergeOptions(true, true))`: rescan datapacks, force merge | `mergeAndUpload` |
| `/pm status` | + one `Datapacks:` line and, if any, `Pending approval: N (/pm sources pending)` | `handleStatus` `:246-325` |

Permissions (`plugin.yml`, currently `:9-15`):

```yaml
  packmerger.sources.approve:
    description: Approve or reject external resource-pack downloads (/pm sources approve|reject)
    default: op
```

The whole tree still requires `packmerger.admin` (`commands/PackMergerCommand.java:91`);
approve/reject additionally require `packmerger.sources.approve`. Admins with
`packmerger.admin` get a join notice "N external resource pack(s) waiting for
approval" (same mechanism as the plugin-pack advisory in `listeners/PlayerJoinListener.java:100-102`).

Output examples:

```
> /pm scan
[PackMerger] Scanning datapacks…
Datapacks: 14 found in 1 folder (world/datapacks)
  ✓ dp-terralith          assets next to data/ (412 files)
  ✓ dp-incendium          nested zip "Incendium RP.zip" (88 files)
  ! mr-3f9a1c22           Modrinth: dungeons-and-taverns-rp 4.4.1 — waiting for approval
  · 10 data-only datapacks (normal; /pm sources datapacks lists them)
  ✗ dp-broken-pack        corrupt zip (cannot read central directory)
Merge requested (datapacks changed).

> /pm sources pending
━━━ Pending external packs ━━━
mr-3f9a1c22  Modrinth dungeons-and-taverns-rp 4.4.1 (2.4 MB)
             for world/datapacks/dungeons-and-taverns-v4.zip — dependency required
             /pm sources approve mr-3f9a1c22   |   /pm sources reject mr-3f9a1c22

> /pm sources approve mr-3f9a1c22
Approved mr-3f9a1c22. Downloading from cdn.modrinth.com…
✓ verified sha512 (2.4 MB). Merge requested.

> /pm conflicts
━━━ Conflicts (4) ━━━
assets/minecraft/textures/block/stone.png   winner: main-pack.zip   lost: [dp] dp-terralith
assets/minecraft/items/paper.json           winner: oraxen          lost: [dp] dp-incendium (different dispatch property)
…
Change the order with /pm priority, or set datapacks.position.

> /pm status   (new lines)
Datapacks: 3 merged, 10 data-only, 1 pending approval, 1 error (/pm sources datapacks)
```

Messages (`messages_en.yml`; missing keys fall back to the bundled defaults,
`config/MessageManager.java:36-45`; all placeholders are escaped, `:79-83`):

```yaml
# Datapacks
scan.starting: "<yellow>Scanning datapacks…</yellow>"
scan.summary: "<gray>Datapacks: <white>{count}</white> found in {folders} folder(s) ({paths})</gray>"
scan.line-merged: "<green>✓</green> <white>{alias}</white> <gray>{detail}</gray>"
scan.line-pending: "<yellow>!</yellow> <white>{id}</white> <gray>{detail} — waiting for approval</gray>"
scan.line-data-only: "<gray>· {count} data-only datapack(s) (normal; /pm sources datapacks lists them)</gray>"
scan.line-error: "<red>✗</red> <white>{alias}</white> <gray>{detail}</gray>"
scan.merge-requested: "<gray>Merge requested (datapacks changed).</gray>"
scan.unchanged: "<gray>Nothing changed.</gray>"
scan.disabled: "<gray>Datapack discovery is off (datapacks.enabled: false).</gray>"
sources.header: "<aqua>━━━ Sources ━━━</aqua>"
sources.line: "<white>{id}</white> <gray>[{kind}] {state} — {detail}</gray>"
sources.pending-header: "<aqua>━━━ Pending external packs ━━━</aqua>"
sources.pending-line: "<white>{id}</white> <gray>{provider} {project} {version} ({size}) for {datapack} — {reason}</gray>"
sources.pending-hint: "<gray>/pm sources approve {id} | /pm sources reject {id}</gray>"
sources.none-pending: "<green>No external packs are waiting for approval.</green>"
sources.unknown-id: "<red>Unknown source id: {id}</red>"
sources.approved: "<green>Approved {id}.</green> <gray>Downloading from {host}…</gray>"
sources.rejected: "<green>Rejected {id}.</green> <gray>It will not be asked again for this file.</gray>"
sources.download-ok: "<green>✓ verified {algorithm} ({size}).</green> <gray>Merge requested.</gray>"
sources.download-failed: "<red>Download of {id} failed: {error}</red>"
sources.no-permission: "<red>You need packmerger.sources.approve for this.</red>"
conflicts.header: "<aqua>━━━ Conflicts ({count}) ━━━</aqua>"
conflicts.line: "<white>{path}</white> <gray>winner: {winner} lost: {losers}</gray>"
conflicts.none: "<green>No conflicts: overlapping files are identical or merged cleanly.</green>"
conflicts.hint: "<gray>Change the order with /pm priority, or set datapacks.position.</gray>"
conflicts.no-merge: "<red>No merge has completed yet.</red>"
rebuild.starting: "<yellow>Rescanning datapacks and rebuilding the pack…</yellow>"
status.datapacks: "<gray>Datapacks: <white>{state}</white></gray>"
status.datapacks-off: "<gray>Datapacks: <white>discovery off (datapacks.enabled: false)</white></gray>"
status.pending-approval: "<yellow>Pending approval: {count} (/pm sources pending)</yellow>"
join.pending-approval: "<yellow>[PackMerger] {count} external resource pack(s) wait for approval: /pm sources pending</yellow>"
```

Logging: new `PluginLogger.datapack(String)` with tag `<dark_aqua>[DATAPACK]</dark_aqua>`
(same pattern as `remote`, `PluginLogger.java:124-128`). Fixed sub-category prefix in
the message text, so operators can grep:

| Sub-category | Level | Example |
|---|---|---|
| `[discovered]` | info | `[discovered] dp-terralith ← world/datapacks/Terralith.zip (assets next to data/)` |
| `[no-assets]` | debug (+ one info summary per scan) | `[no-assets] 10 data-only datapack(s)` |
| `[missing-dependency]` | warning | `[missing-dependency] dnt.zip needs dungeons-and-taverns-rp: no version for 26.2` |
| `[unsupported]` | warning | `[unsupported] foo.zip: external dependency "bar.zip" (not on Modrinth)` / `nested zip inside nested zip ignored` |
| `[pending-approval]` | warning | `[pending-approval] mr-3f9a1c22 dungeons-and-taverns-rp 4.4.1 — /pm sources approve mr-3f9a1c22` |
| `[download-error]` | warning | `[download-error] mr-3f9a1c22: host evil.example not in allowed-hosts` |
| `[validation-error]` | warning | `[validation-error] dp-x: entry exceeds 64 MiB` / `hash mismatch` / `corrupt zip` |
| `[conflicts]` | warning (summary) | see §5.10 |

### 5.13 `config.yml` additions (config-version 6 → 7)

Inserted after the `plugin-packs:` section (`config.yml:164-205`):

```yaml
# =============================================================================
# Datapack Resource Packs (auto-discovery)
# =============================================================================
# PackMerger looks at the server's datapacks (world/datapacks/, plus the datapacks
# folder of every loaded world) and merges the resource packs that belong to them:
#   - assets/ next to data/ in the datapack ("hybrid" packs)
#   - a resource pack inside the datapack: a folder (e.g. resourcepack/) or a zip
#   - (optional) a resource pack the datapack declares on Modrinth (see external)
# Only assets/, pack.png and a resource-pack pack.mcmeta are taken; a datapack's
# data/ never goes into the resource pack. Most datapacks have no resource pack;
# that is normal and only shows up in /pm sources datapacks.
# Staged copies live in packs/.datapack-packs/<alias>.zip; aliases look like
# "dp-terralith" and can be placed in 'priority:' like any other pack.
datapacks:
  enabled: true
  # Merge only datapacks the server has enabled (/datapack list). A datapacks folder
  # the server does not load (e.g. inside a plugin-created world) is listed but not
  # merged unless this is false.
  # Default: true
  only-enabled: true
  # More folders to scan (relative to the server folder, or absolute).
  # Default: []
  extra-folders: []
  # Where datapack packs not listed in 'priority:' go: "bottom" (lowest priority,
  # so your own packs and plugin packs win) or "top".
  # Default: bottom
  position: bottom
  # Re-scan when a datapack is added, changed or removed.
  # Default: true
  watch: true
  # A changed datapack must keep its size and timestamp for 'polls' readings
  # 'interval-millis' apart before it is read (so a half-uploaded zip is never used).
  stability:
    polls: 3
    interval-millis: 1000
  # Safety limits for reading datapacks and downloaded packs.
  limits:
    max-file-mb: 200
    max-uncompressed-mb: 512
    max-entry-mb: 64
    max-entries: 50000
    max-compression-ratio: 100
  external:
    modrinth:
      # Look up each datapack zip on Modrinth by its SHA-1 and find the resource
      # pack its author declared. PRIVACY: sends the datapacks' SHA-1 hashes to
      # api.modrinth.com. Nothing is downloaded without approval (below).
      # Default: true
      enabled: true
      api-url: "https://api.modrinth.com/v2"
      # Downloads (and every redirect) must be HTTPS to one of these hosts.
      allowed-hosts:
        - "cdn.modrinth.com"
      # Also take resource packs the author marked as optional.
      # Default: false
      include-optional: false
      # true = download every found resource pack without asking. Not recommended.
      # Default: false
      auto-approve: false
      # Project ids or slugs whose new versions are approved automatically.
      # Default: []
      trusted-projects: []
      timeout-seconds: 20
      max-download-mb: 100
      lookup-cache-hours: 24
```

Migration: `CURRENT_CONFIG_VERSION` 6 → 7 (`config/ConfigManager.java:41`), bundled
`config.yml` `config-version: 7` (`config.yml:14`). The existing mechanism is
informational only (`config/ConfigManager.java:330-335`): every new key has the
default above in code, so old configs work unchanged and get the feature enabled.
No file rewrite. CHANGELOG gets one "config-version 7" note (repo convention).

`ConfigManager` getters (all with the defaults above):
`isDatapacksEnabled()`, `isDatapacksOnlyEnabled()`, `getDatapacksExtraFolders()`,
`getDatapacksPosition()`, `isDatapacksWatch()`, `getDatapacksStabilityPolls()`,
`getDatapacksStabilityIntervalMillis()`, `getZipLimits()`, `isModrinthEnabled()`,
`getModrinthApiUrl()`, `getModrinthAllowedHosts()`, `isModrinthIncludeOptional()`,
`isModrinthAutoApprove()`, `getModrinthTrustedProjects()`, `getModrinthTimeoutSeconds()`,
`getModrinthMaxDownloadMb()`, `getModrinthLookupCacheHours()`.

Recommended for this SMP (existing keys, no code): `distribution.required: true` and
`distribution.enforce-required.enabled: true` (`config.yml:598`, `:607`) → players who
decline are kicked; INVALID_URL / FAILED_RELOAD never kick (`config.yml:602-603`).

---

## 6. Exact integration points in existing classes

| File | Change |
|---|---|
| `PackMergerBootstrap.java` | field + getter `datapackPackManager`; create after `pluginPackManager` (`:241-242`); startup chain (`:259-264`, §5.9); `stageAll()` after `apiPackManager.stageAll()` (`:548-551`); input-skip before `merge` (`:562`); `MergeOptions` overload of `mergeAndUpload` (`:511`); `.inputs` sidecar in `commitMerge` (`:929-949`); `reload()` → `datapackPackManager.reconfigure()` + `startWatchers()` (`:850-853`); `onDisable` → `stopWatchers()` (`:342-345`) |
| `merge/PackMergeEngine.java` | `.datapack-packs` in `discoverPacks` (`:357-365`) and `resolvePackFile` (`:387-397`); `orderPacks` extraction + position (`:416-456`); identical-bytes flag (`:615-619`); `resolvedOrder()` / `resolve()` accessors |
| `merge/MergeProvenance.java` | `identical` component, extra `Builder.record` overload, JSON read/write (`:55-78`, `:133-203`, `:224-232`) |
| `merge/FileWatcher.java` | second constructor; `root`, `onSettled`, thread name (`:53-56`, `:74`, `:164`, `:250-254`) |
| `discovery/PluginPackStager.java` | `fingerprint(File)` → `public` (`:674`) |
| `remote/RemotePackManager.java` | `fetchVerified(VerifiedSpec, File)` + no-redirect client; existing `fetchInto` unchanged |
| `config/ConfigManager.java` | version 7 (`:41`); load `datapacks.*` after plugin packs (`:527-556`); getters |
| `commands/PackMergerCommand.java` | literals `scan`, `sources`, `conflicts`, `rebuild` (`:89-161`); `status` lines (`:304-314`) |
| `PluginLogger.java` | `datapack(String)` |
| `listeners/` | new `WorldChangeListener` (WorldLoad/WorldUnload/ServerResourcesReloaded → `locator.refreshAsync()` + `scan(…)`); `PlayerJoinListener` pending-approval notice |
| `resources/config.yml`, `messages_en.yml`, `plugin.yml` | §5.12, §5.13 |
| `README.md`, `CHANGELOG.md`, `docs/TESTING.md` | new section "Datapack Resource Packs", changelog entry, test notes |

New files (all under `packmerger-plugin/src/main/java/sh/pcx/packmerger/`):
`datapack/DatapackLocator.java`, `datapack/DatapackInspector.java`,
`datapack/DatapackInfo.java` (+ `AssetLayout`, `DatapackKind`), `datapack/SafeZip.java`,
`datapack/ZipLimits.java`, `datapack/DatapackAssetExtractor.java`,
`datapack/DatapackStager.java`, `datapack/DatapackPackManager.java`,
`datapack/ExternalPackStore.java`, `datapack/modrinth/ModrinthClient.java`,
`datapack/modrinth/ModrinthVersion.java`, `datapack/modrinth/ModrinthProject.java`,
`datapack/modrinth/ModrinthResolver.java`, `datapack/modrinth/JdkTransport.java`,
`remote/VerifiedSpec.java`, `merge/MergeInputs.java`, `commands/ConflictRenderer.java`,
`listeners/WorldChangeListener.java`.

Invariants and who enforces them:

| Invariant | Enforced by |
|---|---|
| No `data/**` and no datapack `pack.mcmeta` in any staged zip | `DatapackAssetExtractor` (whitelist) + test |
| No network inside `mergeAndUpload` from datapack code | `DatapackPackManager.stageAll()` only reads `cache/` and disk |
| No external file merged without `APPROVED` state and a matching `sha512` | `DatapackPackManager` (checks store + hash before adding a `StageInput`) |
| Bukkit API only on the global region thread | `DatapackLocator.takeSnapshot()` (asserts `Bukkit.isGlobalTickThread()` / `isPrimaryThread()`) |
| One merge at a time, never a half-built pack | existing `merging` flag + temp/validate/commit (`PackMergerBootstrap.java:513`, `:536-627`) |
| Staged files appear atomically | `.part` + atomic move in `DatapackAssetExtractor` |

---

## 7. Threading

| Work | Thread |
|---|---|
| `Bukkit.getWorlds()`, `getWorldFolder()`, `getDatapackManager()` | global region thread (`Bukkit.getGlobalRegionScheduler().run`), result cached in `DatapackLocator` |
| Inspect, hash, extract, stage, Modrinth calls, downloads, JSON store writes | async (`Bukkit.getAsyncScheduler()` / `CompletableFuture` on the common pool, like today) |
| `scan()` concurrency | one scan at a time (`AtomicBoolean scanning`); a second request sets `rescanRequested` and runs after |
| `stageAll()` | inside the existing async merge; guarded by the merge lock; reads the last `ScanReport` (immutable) |
| Command feedback | `sender.sendMessage` from async threads (Adventure is thread-safe; existing pattern `commands/PackMergerCommand.java:218-235`) |
| Events (`WorldLoadEvent` …) | handler only calls `refreshAsync()` / `scan()` and returns |

Folia: same schedulers as the rest of the plugin; if `getDatapackManager()` throws,
`enabledStateKnown=false` and only the main level folder is treated as loaded.

---

## 8. Security and performance

- **Zip-slip**: `SafeZip.safeName` rejects absolute paths, `..`, drive letters,
  backslash-normalised traversal, NUL; applies to datapacks, nested zips and downloads.
  Staged zips are written from normalised names only.
- **Zip bombs**: entry count, per-entry, total and ratio limits on actually read bytes;
  nested zips copied to a bounded temp file; nesting depth 1.
- **Downloads**: HTTPS only, host allowlist on every hop, ≤3 redirects, size cap before
  and during read, `sha512` + `sha1` + size verification, timeouts (connect 10 s,
  request `timeout-seconds`), admin approval, content-addressed cache.
- **Modrinth API**: mandatory User-Agent, request budget per scan, rate-limit headers
  honoured, response size cap, Gson parsing only.
- **No code execution**: packs are bytes/JSON; nothing is loaded as a class or script.
  Note: a resource pack can still harm *clients* (huge textures, heavy core shaders).
  Approval is the mitigation; `/pm sources info` shows size and source.
- **Injection**: names from files go to MiniMessage only through escaped placeholders
  (`config/MessageManager.java:79-83`); the console logger uses `Component.text`
  (`PluginLogger.java:88`).
- **Perf**: steady state = folder listing + size/mtime per datapack + no hashing + no
  network (lookup cache) + merge skipped (input fingerprint). Hashing only on change.
  Extraction streams entries (no whole-zip buffering). The existing engine still reads
  each entry into memory (`merge/PackMergeEngine.java:499-502`) — unchanged.

---

## 9. Test plan

All new unit tests are pure (no Bukkit server), `@TempDir`-based, with builders that
write fixture zips in the test (no binary fixtures in the repo). Existing tests must stay
green; `./gradlew build` runs them all.

| Test class | Cases |
|---|---|
| `datapack/SafeZipTest` | `../`, `/abs`, `C:\x`, `a/../../b`, NUL rejected; entry over limit (lying header); total over limit; 10 000:1 ratio; too many entries |
| `datapack/DatapackInspectorTest` | data-only → `NONE`; `assets/` next to `data/` → `ROOT_ASSETS`; `resourcepack/` subfolder; wrapped single folder; nested zip at depth 1 and 2; nested zip without assets ignored; zip in nested zip ignored; RP put into datapacks/ → `RESOURCE_PACK_ONLY`; neither → `NOT_A_PACK`; corrupt zip → `UNREADABLE`; extracted directory datapack; sha1/sha512 only for zips |
| `datapack/DatapackAssetExtractorTest` | output has no `data/`; root datapack mcmeta replaced by synthesized RP mcmeta (format 88 for "26.2"); subfolder/nested RP keeps its own mcmeta + overlays; unknown MC version → no mcmeta; junk stripped; deterministic: two runs → same sha1; `.part` never left behind |
| `datapack/DatapackStagerTest` (fake `Sleeper`) | add → STAGED; unchanged → UNCHANGED (no rewrite, same mtime); update → restaged; remove → pruned; size changing between polls → DEFERRED, previous copy kept; young unreadable zip → DEFERRED, old → INVALID; duplicate datapack in two folders → staged once; not enabled + only-enabled → SKIPPED_NOT_ENABLED |
| `datapack/DatapackLocatorTest` | `levelRoot` from `world/`, `world/dimensions/minecraft/the_nether`, legacy `world_nether`; dedupe of the same folder via different paths; extra folders relative/absolute; missing folders dropped; `loadedByServer` only for the level folder (multiple worlds) |
| `datapack/modrinth/ModrinthResolverTest` (JSON fixtures as strings, stub client) | same-version `required-resource-pack` file; `optional-resource-pack` only with include-optional; dependency with `version_id`; dependency with only `project_id` → newest release for 26.2, beta fallback, alpha never; dependency on a mod project ignored; `file_name`-only → unsupported; no version for MC → missing dependency; `incompatible`/`embedded` ignored |
| `datapack/modrinth/ModrinthClientTest` (stub `HttpServer` on 127.0.0.1) | bulk `POST /version_files` body + `User-Agent` header asserted; 404 → empty; 429 → RATE_LIMITED; `X-Ratelimit-Remaining: 2` → stops; body > 2 MiB → error; timeout → error; malformed JSON → error |
| `remote/VerifiedDownloadTest` (stub `HttpServer`, test-only allow of `127.0.0.1`+http via a package-private flag) | happy path, hash verified; sha512 mismatch → no file; `Content-Length` over cap; streamed body over cap (no Content-Length); redirect to a host not allowed; redirect https→http; >3 redirects; already cached → no request |
| `datapack/ExternalPackStoreTest` | upsert PENDING; trusted project → APPROVED by "auto-approve"; approve/reject persisted and reloaded; new file hash → new PENDING; corrupt JSON → renamed `.broken-*`, empty store |
| `merge/MergeProvenanceTest` (extend) | `identical` round-trips; old JSON without the field → `false` |
| `merge/PackMergeEngineOrderTest` (new, tests `orderPacks`) | datapack aliases bottom/top; listed alias wins over position; server excludes still apply |
| `commands/ConflictRendererTest` | identical overwrite not listed; clean JSON merge not listed; differing texture listed with `[dp]`; strategy warning listed |
| `merge/MergeInputsTest` | same inputs → same fingerprint; any pack fingerprint, order, model-policy, compression change → different |
| `merge/FileWatcherRelevanceTest` (existing) | must still pass; add a case for the datapack predicate ignoring `.part`/`.crdownload` |

Optional e2e (plugwright, Paper 26.3): `src/test/e2e/tests/embedded-datapacks.spec.ts` + fixture
`world/datapacks/pm-hybrid.zip` built by the test support code: assert the merged pack
contains `assets/pmtest/dp_marker.txt` and no `data/`. Only if the plugwright harness
allows writing into the world folder before start; otherwise documented as a manual
smoke test in `docs/TESTING.md` (multiple worlds, `/datapack disable`, add/remove while
running, half-uploaded zip via slow copy, Modrinth approve flow against the real API).

---

## 10. Implementation stages (one engineer; each ends with `./gradlew build` green)

**Stage 0 — baseline.** JDK 25, `./gradlew build` green on unchanged master (done by the
build-env session). *Done when:* build + all existing tests pass locally.

**Stage 1 — safe reading and extraction (pure).** `ZipLimits`, `SafeZip`,
`DatapackInfo`/enums, `DatapackInspector`, `DatapackAssetExtractor`; make
`PluginPackStager.fingerprint` public. Tests: `SafeZipTest`, `DatapackInspectorTest`,
`DatapackAssetExtractorTest`. No wiring, no behaviour change.
*Done when:* new tests pass; existing tests untouched and green.

**Stage 2 — local discovery wired in.** `DatapackLocator`, `DatapackStager`,
`DatapackPackManager` (local only), `.datapack-packs` in engine discovery/resolve,
`orderPacks` + `datapacks.position`, `ConfigManager` keys + config-version 7, config.yml
section, `PluginLogger.datapack`, startup scan, `/pm scan`, `/pm sources` (list only),
`/pm status` line, `WorldChangeListener`. Verify §4.1 open point on a 26.2 server and
note the result in this doc.
*Done when:* stager/locator/order tests pass; on a test server a hybrid datapack's
textures appear in the merged pack, a data-only datapack is listed as data-only, the
merged zip has no `data/`.

**Stage 3 — automation.** `FileWatcher` second constructor, datapack watchers (+ poll
fallback), `MergeInputs` + `.inputs` sidecar + skip, `MergeOptions`, `/pm rebuild`.
*Done when:* `MergeInputsTest` + watcher test pass; adding/removing/updating a datapack
while running re-merges once; an idle rescan logs "inputs unchanged" and does not upload.

**Stage 4 — conflicts.** `MergeProvenance.identical`, engine flag, `ConflictRenderer`,
`/pm conflicts`, post-merge conflict summary.
*Done when:* provenance/conflict tests pass; `/pm inspect` output unchanged for old
sidecars.

**Stage 5 — Modrinth + approval.** `ModrinthClient` (+ `JdkTransport`),
`ModrinthResolver`, `ExternalPackStore`, `RemotePackManager.fetchVerified`,
`/pm sources pending|info|approve|reject`, permission, join notice, lookup cache.
*Done when:* resolver/client/download/store tests pass with stub HTTP; manual check
against the real API with one datapack known to declare a resource pack.

**Stage 6 — docs.** README section, CHANGELOG (incl. config-version 7), TESTING.md
smoke steps, optional e2e spec.
*Done when:* docs merged; full build green.

Stages 1–4 are useful without any network feature; Stage 5 can be dropped or delayed
without touching the rest.

---

## 11. Deliberately left out / follow-ups

- Datapacks provided by plugins through Paper's lifecycle API (no file path; §4.2).
- Datapacks inside mod/plugin jars, CurseForge / GitHub lookups (no hash API we can
  trust the same way; only Modrinth).
- Matching by file or project name (explicitly forbidden).
- Hardening the existing engine for `packs/` (entry/size limits in
  `merge/PackMergeEngine.java:467-507`) and adding hash pins / size caps to
  `remote-packs:` by reusing `fetchVerified` — small, useful, separate change.
- The engine's default `pack.mcmeta` uses `pack_format 46` (1.21.4,
  `merge/PackMergeEngine.java:229-241`); on 26.x it should use
  `PackFormatRegistry.forMinecraftVersion(...)`. Pre-existing bug, separate one-line fix.
- Proxy networks: each backend scans its own datapacks; no cross-server merging.

## 12. Risks and open questions

1. `World#getWorldFolder()` semantics on 26.x and plugin-world storage are unverified
   (mitigated by `levelRoot` walk; to be confirmed in Stage 2).
2. Datapack ids (`file/<name>`) are a vanilla convention, not a documented Paper
   contract; if they differ, `only-enabled` would hide packs → fall back to
   `enabledStateKnown=false` when no folder file matches any id, and warn.
3. Modrinth coverage is limited to unmodified files whose authors declared the RP
   (§4.3). Expect many datapacks to show "not on Modrinth".
4. Modrinth game-version strings for 26.x vs. `Bukkit.getMinecraftVersion()`.
5. Hybrid packs whose root `pack.mcmeta` holds resource-pack overlays lose them (we
   synthesize a fresh mcmeta). Rare; logged at debug.
6. Default `external.modrinth.enabled: true` sends hashes to Modrinth — a privacy
   choice the server owner should confirm (open question).
7. Merged pack size grows with every hybrid datapack; existing
   `size-warning-mb` (`config.yml:330`) covers the warning.
8. Fork divergence from upstream PackMerger; keep changes additive and consider a PR.

## 13. Implementation notes (2026-11-01)

Implemented as specified, with these deviations:

| Where | Deviation | Why |
|---|---|---|
| `SafeZip` | Reads zips with `java.util.zip` plus a small central-directory reader for symlink entries (commons-compress was tried first, but its `ZipFile` pulls commons-io/lang3/codec unrelocated into the shaded jar, +1 MB); API is a `SafeZip.Tree` (names + bounded streams) plus `forEach`. `ZipLimitException` extends `IOException`. Control characters and `:` in entry names are rejected as well. | `java.util.zip` cannot see the unix mode that marks symlink entries; one tree type serves zips and folders alike. |
| `DatapackInfo` | Extra component `assetFiles` (count shown in `/pm scan`). `assetRoot` includes a wrapper folder (`"Wrapper/"`). | Operator output; one prefix covers wrapped packs. |
| Classification | A zip with `pack.mcmeta` but neither `data/` nor root `assets/` is a `DATAPACK` (shell that may hold a subfolder/nested RP), not `RESOURCE_PACK_ONLY`. | Otherwise an RP folder/zip inside such a pack is never found. |
| `DatapackAssetExtractor` | No `strategies` constructor parameter; an identical staged zip is left untouched (mtime kept). | No union is needed; keeps the merge-input fingerprint stable across restarts. |
| `DatapackStager` | Extra status `DUPLICATE` (instead of `UNCHANGED (duplicate of …)`); `Outcome.merged` is a component (a `DEFERRED` input keeping its previous copy counts as merged). `run()` takes `onlyEnabled`. | Clearer `/pm sources`. |
| Inspection cache | Whole inspection results are cached by path + fingerprint (memory + `cache/datapacks/state.json`), so unchanged datapacks are not even re-read. | Cheaper than caching only hashes. |
| `MergeProvenance` | Besides `identical`, a `warned` flag (set by a new `Builder.warn`) separates strategy warnings from informational notes; `FileRecord.isConflict()` holds the rule. | Otherwise every `pack.mcmeta` range repair would be a "conflict". |
| `ConflictRenderer` | Returns `Conflict` records; the command formats them through `messages_en.yml`. The post-merge `[conflicts]` line is logged by `PackMergerBootstrap`. | Keeps `merge` independent of `commands`. |
| `MergeInputs` | Pure overload with a settings list; also includes the Minecraft version (the generated `pack.mcmeta` depends on it). The `.inputs` sidecar is written after commit, not rotated. | Testable without Bukkit; the skip also requires the published pack's SHA-1 to equal the output's, so a stale sidecar can never skip a needed merge. |
| `MergeOptions` | `FORCED` is used by `/pm merge`, `/pm reload`, `/pm fetch`, `/pm profile switch`, `/pm priority …` and `PackMergerApi.triggerMerge()`; `/pm rebuild` runs the datapack rescan *before* claiming the merge lock (no network inside the merge step). | Explicit requests keep their old "always merge" behaviour. |
| `FileWatcher` | Second constructor as designed, plus `isDegraded()`; the 60 s fingerprint poll runs only while a datapack watcher is degraded. | As designed (fallback). |
| Verified download | Logic in a pure `remote/VerifiedDownload` (no Bukkit), wrapped by `RemotePackManager.fetchVerified`. Whole-download deadline = 6 × `timeout-seconds` (API requests use `timeout-seconds`); the body is copied on a helper thread so a stalled server cannot exceed the deadline. Test hook `allowLoopbackHttp`. | Testable with a stub `HttpServer`. |
| Modrinth | `ExternalPackService` (package-private) holds lookups, the lookup cache (`cache/datapacks/modrinth-lookups.json`), store upserts and downloads. `ExternalPackStore.markDownloaded` was dropped (downloaded = file present in the content-addressed cache). Resolver problems carry their log category as prefix. | Keeps `DatapackPackManager` readable. |
| Pack format fix (§11) | Done: the generated default `pack.mcmeta` uses `PackFormatRegistry.forMinecraftVersion(Bukkit.getMinecraftVersion())` (`min_format`/`max_format` too for formats > 64), newest known format for an unknown version. | Requested. |

Open points (need a real Paper 26.2 server; see `docs/TESTING.md`, "Datapack resource packs"):
§4.1 `World#getWorldFolder()` for nether/end and plugin worlds (handled by the
`levelRoot` walk either way, not confirmed); datapack id format `file/<name>` (a suffix
match and a mismatch warning are in place); Modrinth game-version strings for 26.x;
the real Modrinth approval flow (no live API calls were made); behaviour of
`ServerResourcesReloadedEvent` after `/datapack enable|disable`; Folia.

Build note: the shadow plugin's `minimize` step (Shadow 9.4.2) also treats classes
referenced from **test** code as used. A test fixture that used commons-compress'
`ZipArchiveOutputStream` grew the shipped jar from 0.6 MB to 1.5 MB (commons-io, -lang3
unrelocated). Test code therefore builds its fixture zips with `java.util.zip` only.
Final jar: `packmerger-plugin/build/libs/packmerger-plugin-1.3.0.jar`, 777 857 bytes
(baseline 608 825).
