package sh.pcx.packmerger.datapack;

import sh.pcx.packmerger.discovery.PluginPackStager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Stages the resource-pack parts of inspected datapacks (and of approved,
 * verified external packs) into {@code packs/.datapack-packs/<alias>.zip}. Same
 * contract as {@link PluginPackStager#run}: local disk only, no Bukkit references.
 *
 * <p>One {@link #run} pass, per input in alias order:</p>
 * <ol>
 *   <li>data-only, not-a-pack, over-limit and (with only-enabled) disabled datapacks
 *       are reported and not staged;</li>
 *   <li>an unreadable zip younger than a minute is a probably half-uploaded file:
 *       {@code DEFERRED}, the previous staged copy is kept; an older one is
 *       {@code INVALID};</li>
 *   <li>an input whose fingerprint (and the target format) is what is already staged
 *       is {@code UNCHANGED};</li>
 *   <li>changed inputs must keep their fingerprint for {@code polls} readings
 *       {@code intervalMillis} apart, otherwise {@code DEFERRED};</li>
 *   <li>the same source (by SHA-1) or the same output twice is staged once, under the
 *       first alias; the others are {@code DUPLICATE};</li>
 *   <li>staged files of aliases not kept this pass are deleted.</li>
 * </ol>
 */
public final class DatapackStager {

    /** An unreadable zip modified less than this long ago is assumed to be still uploading. */
    static final long YOUNG_MILLIS = 60_000;

    /**
     * One thing to stage.
     *
     * @param alias       the staged alias ({@code dp-…})
     * @param info        the inspected datapack or downloaded pack
     * @param sourceLabel what to show operators (a path or "Modrinth slug version")
     * @param external    {@code true} for an approved external download
     */
    public record StageInput(String alias, DatapackInfo info, String sourceLabel, boolean external) {}

    /** What happened to one input. */
    public enum Status {
        STAGED, UNCHANGED, DEFERRED, DUPLICATE, DATA_ONLY, SKIPPED_NOT_ENABLED,
        NOT_A_PACK, INVALID, LIMIT_EXCEEDED, FAILED
    }

    /**
     * @param merged {@code true} when a staged zip for the alias takes part in the merge
     *               (also a kept previous copy of a {@code DEFERRED} input)
     */
    public record Outcome(String alias, String sourceLabel, Status status, String detail, boolean merged) {
        Outcome(String alias, String sourceLabel, Status status, String detail) {
            this(alias, sourceLabel, status, detail, false);
        }

        /** @return {@code true} for problems an operator should look at */
        public boolean error() {
            return status == Status.INVALID || status == Status.LIMIT_EXCEEDED || status == Status.FAILED;
        }
    }

    public record Report(List<Outcome> outcomes, List<String> pruned) {
        public List<String> mergedAliases() {
            return outcomes.stream().filter(Outcome::merged).map(Outcome::alias).toList();
        }

        public int count(Status s) {
            return (int) outcomes.stream().filter(o -> o.status() == s).count();
        }

        public boolean hasDeferred() {
            return count(Status.DEFERRED) > 0;
        }

        public Outcome outcome(String alias) {
            return outcomes.stream().filter(o -> o.alias().equals(alias)).findFirst().orElse(null);
        }
    }

    private final Path stageDir;
    private final DatapackAssetExtractor extractor;
    private final int polls;
    private final long intervalMillis;
    private final PluginPackStager.Sleeper sleeper;
    private final LongSupplier clock;

    /** Input fingerprint (+ format) behind each staged zip. */
    private final Map<String, String> stagedKeys = new ConcurrentHashMap<>();
    /** SHA-1 of each staged zip, for duplicate detection. */
    private final Map<String, String> stagedSha1 = new ConcurrentHashMap<>();
    /** {bytes, JSON bytes} of each staged zip, counted against the pass budget while it is kept. */
    private final Map<String, long[]> stagedSize = new ConcurrentHashMap<>();

    public DatapackStager(Path stageDir, DatapackAssetExtractor extractor, int polls, long intervalMillis,
                          PluginPackStager.Sleeper sleeper) {
        this(stageDir, extractor, polls, intervalMillis, sleeper, System::currentTimeMillis);
    }

    DatapackStager(Path stageDir, DatapackAssetExtractor extractor, int polls, long intervalMillis,
                   PluginPackStager.Sleeper sleeper, LongSupplier clock) {
        this.stageDir = stageDir;
        this.extractor = extractor;
        this.polls = Math.max(1, polls);
        this.intervalMillis = Math.max(0, intervalMillis);
        this.sleeper = sleeper == null ? Thread::sleep : sleeper;
        this.clock = clock;
    }

    public Path stagedFile(String alias) {
        return stageDir.resolve(alias + ".zip");
    }

    /**
     * Runs one staging pass.
     *
     * @param inputs             everything found by the last scan
     * @param resourcePackFormat the server's resource-pack format, {@code -1} if unknown
     * @param onlyEnabled        skip datapacks the server has not enabled
     */
    public Report run(List<StageInput> inputs, int resourcePackFormat, boolean onlyEnabled) {
        try {
            Files.createDirectories(stageDir);
        } catch (IOException e) {
            return new Report(List.of(new Outcome("*", stageDir.toString(), Status.FAILED,
                    "cannot create " + stageDir + ": " + e.getMessage())), List.of());
        }
        List<StageInput> sorted = new ArrayList<>(inputs);
        sorted.sort(Comparator.comparing(StageInput::alias));

        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        Set<String> keep = new HashSet<>();
        Map<String, String> sourceOwner = new HashMap<>();   // source sha1 → alias staged for it
        List<StageInput> changed = new ArrayList<>();

        for (StageInput in : sorted) {
            DatapackInfo i = in.info();
            String alias = in.alias();
            Outcome early = classify(in, onlyEnabled);
            if (early != null) {
                if (early.status() == Status.DEFERRED && Files.isRegularFile(stagedFile(alias))) {
                    keep.add(alias);
                    early = new Outcome(alias, in.sourceLabel(), Status.DEFERRED,
                            early.detail() + "; keeping the previously staged copy");
                }
                outcomes.put(alias, early);
                continue;
            }
            if (i.sha1() != null) {
                String owner = sourceOwner.putIfAbsent(i.sha1(), alias);
                if (owner != null) {
                    outcomes.put(alias, new Outcome(alias, in.sourceLabel(), Status.DUPLICATE,
                            "same file as " + owner));
                    continue;
                }
            }
            if (key(i, resourcePackFormat).equals(stagedKeys.get(alias)) && Files.isRegularFile(stagedFile(alias))) {
                keep.add(alias);
                outcomes.put(alias, new Outcome(alias, in.sourceLabel(), Status.UNCHANGED, i.detail()));
                continue;
            }
            changed.add(in);
        }

        // Stability window: only changed inputs are polled, so a steady-state pass never sleeps.
        Set<String> unstable = new HashSet<>();
        if (!changed.isEmpty() && polls > 1) {
            for (int p = 1; p < polls && unstable.size() < changed.size(); p++) {
                try {
                    sleeper.sleep(intervalMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                for (StageInput in : changed) {
                    if (unstable.contains(in.alias())) continue;
                    String now = PluginPackStager.fingerprint(in.info().path().toFile());
                    if (!now.equals(in.info().fingerprint())) unstable.add(in.alias());
                }
            }
        }

        Map<String, String> outputOwner = new HashMap<>();
        for (Map.Entry<String, String> e : stagedSha1.entrySet()) {
            if (keep.contains(e.getKey())) outputOwner.putIfAbsent(e.getValue(), e.getKey());
        }
        // Everything staged takes part in the merge, which holds it in memory: kept copies
        // count first, then each changed input may use what is left.
        DatapackAssetExtractor.Budget budget = extractor.newBudget();
        for (String alias : keep) {
            long[] size = stagedSize.get(alias);
            if (size != null) budget.take(size[0], size[1]);
        }
        for (StageInput in : changed) {
            String alias = in.alias();
            if (unstable.contains(alias)) {
                boolean hasPrevious = Files.isRegularFile(stagedFile(alias));
                if (hasPrevious) keep.add(alias);
                outcomes.put(alias, new Outcome(alias, in.sourceLabel(), Status.DEFERRED,
                        "file is still being written; " + (hasPrevious
                                ? "keeping the previously staged copy" : "will retry once it settles")));
                continue;
            }
            try {
                DatapackAssetExtractor.Result r = extractor.extract(in.info(), resourcePackFormat, stagedFile(alias),
                        budget);
                String owner = outputOwner.get(r.sha1());
                if (owner != null && !owner.equals(alias)) {
                    Files.deleteIfExists(stagedFile(alias));
                    stagedKeys.remove(alias);
                    stagedSha1.remove(alias);
                    stagedSize.remove(alias);
                    outcomes.put(alias, new Outcome(alias, in.sourceLabel(), Status.DUPLICATE,
                            "same resource pack as " + owner));
                    continue;
                }
                outputOwner.put(r.sha1(), alias);
                stagedKeys.put(alias, key(in.info(), resourcePackFormat));
                stagedSha1.put(alias, r.sha1());
                stagedSize.put(alias, new long[]{r.bytes(), r.jsonBytes()});
                keep.add(alias);
                outcomes.put(alias, new Outcome(alias, in.sourceLabel(), Status.STAGED,
                        in.info().detail() + " → " + r.files() + " files"
                                + (r.mcmetaSynthesized() ? ", pack.mcmeta generated" : "")
                                + (r.notes().isEmpty() ? "" : "; " + String.join("; ", r.notes()))));
            } catch (SafeZip.ZipLimitException e) {
                stagedKeys.remove(alias);
                outcomes.put(alias, new Outcome(alias, in.sourceLabel(), Status.LIMIT_EXCEEDED, e.getMessage()));
            } catch (IOException | RuntimeException e) {
                stagedKeys.remove(alias);
                outcomes.put(alias, new Outcome(alias, in.sourceLabel(), Status.FAILED,
                        "staging failed: " + e.getMessage()));
            }
        }

        List<String> pruned = PluginPackStager.prune(stageDir.toFile(), keep);
        for (String alias : pruned) {
            stagedKeys.remove(alias);
            stagedSha1.remove(alias);
            stagedSize.remove(alias);
        }
        stagedKeys.keySet().retainAll(keep);
        stagedSha1.keySet().retainAll(keep);
        stagedSize.keySet().retainAll(keep);
        List<Outcome> out = new ArrayList<>();
        for (Outcome o : outcomes.values()) {
            out.add(new Outcome(o.alias(), o.sourceLabel(), o.status(), o.detail(), keep.contains(o.alias())));
        }
        return new Report(List.copyOf(out), pruned);
    }

    /** Deletes every staged file (used when {@code datapacks.enabled} is turned off). */
    public List<String> clear() {
        stagedKeys.clear();
        stagedSha1.clear();
        stagedSize.clear();
        return PluginPackStager.prune(stageDir.toFile(), Set.of());
    }

    /** @return the outcome for inputs that are never staged, or {@code null} if it may be staged */
    private Outcome classify(StageInput in, boolean onlyEnabled) {
        DatapackInfo i = in.info();
        String alias = in.alias();
        switch (i.kind()) {
            case UNREADABLE -> {
                long age = clock.getAsLong() - i.path().toFile().lastModified();
                if (age < YOUNG_MILLIS) {
                    return new Outcome(alias, in.sourceLabel(), Status.DEFERRED,
                            "cannot be read yet (probably still uploading)");
                }
                return new Outcome(alias, in.sourceLabel(), Status.INVALID, i.detail());
            }
            case LIMIT_EXCEEDED -> {
                return new Outcome(alias, in.sourceLabel(), Status.LIMIT_EXCEEDED, i.detail());
            }
            case NOT_A_PACK -> {
                return new Outcome(alias, in.sourceLabel(), Status.NOT_A_PACK, i.detail());
            }
            default -> { }
        }
        if (!i.hasAssets()) return new Outcome(alias, in.sourceLabel(), Status.DATA_ONLY, i.detail());
        if (onlyEnabled && !in.external() && !i.enabledOnServer()) {
            return new Outcome(alias, in.sourceLabel(), Status.SKIPPED_NOT_ENABLED,
                    "not enabled on the server (datapacks.only-enabled)");
        }
        return null;
    }

    private static String key(DatapackInfo i, int format) {
        return i.fingerprint() + "#" + i.layout() + ":" + i.assetRoot() + "@" + format;
    }

    // -------------------------------------------------------------------------
    // Aliases
    // -------------------------------------------------------------------------

    /** @return {@code dp-<slug>} for a datapack file or folder name */
    public static String aliasFor(String fileName) {
        return "dp-" + slug(fileName);
    }

    /** @return {@code dp-<slug>-rp} for an external resource pack belonging to a datapack */
    public static String externalAliasFor(String datapackFileName) {
        return "dp-" + slug(datapackFileName) + "-rp";
    }

    static String slug(String fileName) {
        String n = fileName;
        if (n.toLowerCase(Locale.ROOT).endsWith(".zip")) n = n.substring(0, n.length() - 4);
        n = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        if (n.length() > 48) n = n.substring(0, 48);
        return n.isEmpty() ? "pack" : n;
    }

    /**
     * Assigns stable aliases to candidates: {@code dp-<slug>}; when two candidates
     * share a slug, every one but the first (by path) gets {@code -<6 hex of sha1(path)>}.
     */
    public static Map<Path, String> assignAliases(List<Path> candidates) {
        return assignAliases(candidates, Set.of());
    }

    /**
     * Like {@link #assignAliases(List)}, but {@code preferred} candidates (those in a
     * folder the server loads) get the plain alias before any other: a stray copy of a
     * datapack in another world's folder never takes {@code dp-<name>} from the copy
     * the server actually uses, so a {@code priority:} entry keeps pointing at it.
     */
    public static Map<Path, String> assignAliases(List<Path> candidates, Set<Path> preferred) {
        List<Path> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparing((Path p) -> !preferred.contains(p)).thenComparing(Path::toString));
        Map<Path, String> out = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (Path p : sorted) {
            String base = aliasFor(p.getFileName().toString());
            String alias = base;
            if (!used.add(alias)) {
                alias = base + "-" + sha1Hex(p.toString()).substring(0, 6);
                used.add(alias);
            }
            out.put(p, alias);
        }
        return out;
    }

    static String sha1Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
