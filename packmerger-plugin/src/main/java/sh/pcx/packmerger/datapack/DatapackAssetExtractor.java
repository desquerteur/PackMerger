package sh.pcx.packmerger.datapack;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sh.pcx.packmerger.merge.DeterministicZip;
import sh.pcx.packmerger.merge.PackFormatRegistry;
import sh.pcx.packmerger.merge.PackMergeEngine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipOutputStream;

/**
 * Writes the resource-pack part of an inspected datapack (or of a downloaded
 * resource pack) to a staged zip. Pure: no Bukkit references.
 *
 * <p>Only {@code assets/**}, {@code pack.png} and a resource-pack {@code pack.mcmeta}
 * (plus the {@code <overlay>/assets/**} folders that mcmeta declares) are copied. A
 * datapack's {@code data/**} and its own data-format {@code pack.mcmeta} never are:</p>
 * <ul>
 *   <li>{@link AssetLayout#SUBFOLDER}, {@link AssetLayout#NESTED_ZIP} and
 *       {@link DatapackKind#RESOURCE_PACK_ONLY} keep the resource pack's own mcmeta,
 *       without its {@code filter} section (and other unknown top-level keys): a
 *       datapack must not hide vanilla assets for every player;</li>
 *   <li>a hybrid pack ({@link AssetLayout#ROOT_ASSETS} with {@code data/}) gets a
 *       synthesized mcmeta for the running Minecraft version, or none if that version
 *       is unknown (the merged pack still gets one).</li>
 * </ul>
 *
 * <p>The merge reads every staged file into memory and parses JSON into trees, so
 * JSON is bounded here: a {@code .json}/{@code .mcmeta} file over
 * {@link #MAX_JSON_FILE_BYTES} is skipped (a {@code pack.mcmeta} over
 * {@link #MAX_MCMETA_BYTES} is replaced), and a {@link Budget} caps the bytes and
 * the JSON all staged packs of one pass may hold together.</p>
 *
 * <p>Output is deterministic (sorted entries, fixed timestamps) and appears
 * atomically ({@code <dest>.part} then a rename).</p>
 */
public final class DatapackAssetExtractor {

    /** Largest {@code .json} / {@code .mcmeta} file copied; larger ones are skipped. */
    public static final long MAX_JSON_FILE_BYTES = 4 * ZipLimits.MIB;

    /** Largest {@code pack.mcmeta} read; a larger one is replaced by a generated one. */
    public static final long MAX_MCMETA_BYTES = 256 * 1024;

    /** JSON bytes all staged packs of one staging pass may hold together. */
    public static final long MAX_JSON_TOTAL_BYTES = 64 * ZipLimits.MIB;

    /** Prefix of {@link Result#notes()} entries an operator should see as a warning. */
    public static final String NOTE_PREFIX = "[sanitized] ";

    /** The only top-level keys kept in a datapack's resource-pack {@code pack.mcmeta}. */
    private static final Set<String> MCMETA_KEYS = Set.of("pack", "overlays", "language");

    /**
     * @param files              files written
     * @param bytes              uncompressed bytes written
     * @param jsonBytes          of which JSON / mcmeta
     * @param sha1               SHA-1 of the written zip
     * @param mcmetaSynthesized  {@code true} if the pack.mcmeta was generated
     * @param notes              files skipped or changed, each starting with {@link #NOTE_PREFIX}
     */
    public record Result(int files, long bytes, long jsonBytes, String sha1, boolean mcmetaSynthesized,
                         List<String> notes) {
        public Result {
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    /**
     * What the staged packs of one pass may still use. Taken only by a successful
     * extraction; a failed one leaves it unchanged.
     */
    public static final class Budget {
        private long bytesLeft;
        private long jsonLeft;

        public Budget(long bytes, long json) {
            this.bytesLeft = bytes;
            this.jsonLeft = json;
        }

        public long bytesLeft() {
            return bytesLeft;
        }

        public long jsonLeft() {
            return jsonLeft;
        }

        /** Counts an already staged pack that takes part in the merge again. */
        public void take(long bytes, long json) {
            bytesLeft -= bytes;
            jsonLeft -= json;
        }
    }

    private final ZipLimits limits;
    private final Path tempDir;

    /**
     * @param limits  reading limits
     * @param tempDir where nested zips are unpacked to while copying ({@code null}: system temp)
     */
    public DatapackAssetExtractor(ZipLimits limits, Path tempDir) {
        this.limits = limits == null ? ZipLimits.DEFAULTS : limits;
        this.tempDir = tempDir;
    }

    /** @return a budget for one staging pass: the total-uncompressed limit and {@link #MAX_JSON_TOTAL_BYTES} */
    public Budget newBudget() {
        return new Budget(limits.maxTotalUncompressedBytes(), MAX_JSON_TOTAL_BYTES);
    }

    /** {@link #extract(DatapackInfo, int, Path, Budget)} with a fresh {@link #newBudget()}. */
    public Result extract(DatapackInfo info, int resourcePackFormat, Path dest) throws IOException {
        return extract(info, resourcePackFormat, dest, newBudget());
    }

    /**
     * @param info               an inspected source with {@link DatapackInfo#hasAssets()}
     * @param resourcePackFormat the server's resource-pack format, or {@code -1} if unknown
     * @param dest               the staged zip to write
     * @param budget             what all staged packs of this pass may still use; taken on success
     * @throws SafeZip.ZipLimitException if a limit or the budget is exceeded (nothing is written)
     * @throws IOException               on read/write failure (nothing is written)
     */
    public Result extract(DatapackInfo info, int resourcePackFormat, Path dest, Budget budget) throws IOException {
        if (!info.hasAssets()) throw new IOException(info.fileName() + " has no resource pack to extract");
        Path nested = null;
        try (SafeZip.Tree outer = SafeZip.open(info.path(), limits)) {
            if (info.layout() == AssetLayout.NESTED_ZIP) {
                if (!outer.contains(info.assetRoot())) throw new IOException("nested zip " + info.assetRoot() + " is gone");
                nested = outer.copyToTemp(info.assetRoot(), tempDir());
                try (SafeZip.Tree inner = SafeZip.open(nested, limits)) {
                    String root = SafeZip.wrapperPrefix(inner.names(), DatapackInspector.RP_MARKERS);
                    return write(inner, root, true, info, resourcePackFormat, dest, budget);
                }
            }
            boolean hybrid = info.kind() == DatapackKind.DATAPACK && info.layout() == AssetLayout.ROOT_ASSETS;
            return write(outer, info.assetRoot(), !hybrid, info, resourcePackFormat, dest, budget);
        } finally {
            DatapackInspector.deleteQuietly(nested);
        }
    }

    private Path tempDir() {
        return tempDir != null ? tempDir : Path.of(System.getProperty("java.io.tmpdir"));
    }

    private Result write(SafeZip.Tree tree, String root, boolean keepOwnMcmeta, DatapackInfo info,
                         int format, Path dest, Budget budget) throws IOException {
        List<String> rel = SafeZip.under(tree.names(), root);
        List<String> notes = new ArrayList<>();

        // The pack's own mcmeta (bounded, without filter), or null to generate one.
        byte[] ownMcmeta = null;
        boolean generate = !keepOwnMcmeta;
        Set<String> overlays = Set.of();
        if (keepOwnMcmeta && rel.contains("pack.mcmeta")) {
            byte[] raw = readBounded(tree, root + "pack.mcmeta", MAX_MCMETA_BYTES);
            if (raw == null) {
                notes.add(NOTE_PREFIX + "pack.mcmeta is larger than " + ZipLimits.describe(MAX_MCMETA_BYTES)
                        + "; replaced by a generated one");
                generate = true;
            } else {
                ownMcmeta = sanitizeMcmeta(raw, notes);
                overlays = overlayDirs(ownMcmeta);
            }
        }

        List<String> selected = new ArrayList<>();
        for (String name : rel) {
            if (PackMergeEngine.isJunkPath(name)) continue;
            if (included(name, overlays)) selected.add(name);
        }

        byte[] mcmeta = ownMcmeta;
        boolean synthesized = false;
        if (mcmeta == null && generate && format > 0) {
            mcmeta = synthesizedMcmeta(info, format);
            synthesized = true;
        }

        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        Files.createDirectories(dest.toAbsolutePath().getParent());
        int files = 0;
        long bytes = 0;
        long json = 0;
        try {
            try (OutputStream fos = Files.newOutputStream(part); ZipOutputStream zos = new ZipOutputStream(fos)) {
                // rel is sorted (Tree.names() is); pack.mcmeta is inserted in order.
                boolean mcmetaWritten = mcmeta == null;
                for (String name : selected) {
                    if (!mcmetaWritten && name.compareTo("pack.mcmeta") > 0) {
                        writeEntry(zos, "pack.mcmeta", mcmeta);
                        bytes += mcmeta.length;
                        json += mcmeta.length;
                        files++;
                        mcmetaWritten = true;
                    }
                    if (isJson(name)) {
                        byte[] data = readBounded(tree, root + name, MAX_JSON_FILE_BYTES);
                        if (data == null) {
                            notes.add(NOTE_PREFIX + "skipped " + name + " (larger than "
                                    + ZipLimits.describe(MAX_JSON_FILE_BYTES) + ")");
                            continue;
                        }
                        writeEntry(zos, name, data);
                        bytes += data.length;
                        json += data.length;
                    } else {
                        zos.putNextEntry(DeterministicZip.entry(name));
                        try (InputStream in = tree.open(root + name)) {
                            bytes += in.transferTo(zos);
                        }
                        zos.closeEntry();
                    }
                    files++;
                    checkBudget(bytes, json, budget);
                }
                if (!mcmetaWritten) {
                    writeEntry(zos, "pack.mcmeta", mcmeta);
                    bytes += mcmeta.length;
                    json += mcmeta.length;
                    files++;
                }
                checkBudget(bytes, json, budget);
            }
            String sha1 = DatapackInspector.hashes(part)[0];
            // Leave an identical staged zip untouched, so its mtime (and with it the merge
            // input fingerprint) does not change after a restart.
            if (!Files.isRegularFile(dest) || !sha1.equals(DatapackInspector.hashes(dest)[0])) {
                SafeZip.moveAtomic(part, dest);
            }
            budget.take(bytes, json);
            return new Result(files, bytes, json, sha1, synthesized, notes);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private static void checkBudget(long bytes, long json, Budget budget) throws SafeZip.ZipLimitException {
        if (bytes > budget.bytesLeft()) {
            throw new SafeZip.ZipLimitException("the resource packs from datapacks hold more than "
                    + ZipLimits.describe(Math.max(0, budget.bytesLeft())) + " more in total "
                    + "(datapacks.limits.max-uncompressed-mb applies to all of them together)");
        }
        if (json > budget.jsonLeft()) {
            throw new SafeZip.ZipLimitException("the resource packs from datapacks hold more than "
                    + ZipLimits.describe(MAX_JSON_TOTAL_BYTES) + " of JSON in total");
        }
    }

    private static boolean included(String name, Set<String> overlays) {
        if (name.startsWith("assets/")) return true;
        if (name.equals("pack.png")) return true;
        for (String o : overlays) {
            if (name.startsWith(o + "/assets/")) return true;
        }
        return false;
    }

    /** @return {@code true} for files the merge parses as JSON (or may): {@code .json}, {@code .mcmeta} */
    static boolean isJson(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".json") || n.endsWith(".mcmeta");
    }

    /** @return the entry's bytes, or {@code null} if it is larger than {@code max} (only {@code max + 1} bytes are read) */
    private static byte[] readBounded(SafeZip.Tree tree, String name, long max) throws IOException {
        try (InputStream in = tree.open(name)) {
            byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max + 1));
            return data.length > max ? null : data;
        }
    }

    private static void writeEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(DeterministicZip.entry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static byte[] synthesizedMcmeta(DatapackInfo info, int format) {
        String name = info.fileName();
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) name = name.substring(0, name.length() - 4);
        return PackFormatRegistry.mcmetaFor(format, "Assets from datapack " + name).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Drops every top-level key but {@code pack}, {@code overlays} and {@code language}
     * from a datapack's resource-pack mcmeta. {@code filter} would otherwise reach the
     * merged pack and could hide vanilla assets for every player. Unchanged (or
     * malformed) input is returned as it is.
     */
    static byte[] sanitizeMcmeta(byte[] raw, List<String> notes) {
        JsonObject o;
        try {
            JsonElement el = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
            if (!el.isJsonObject()) return raw;
            o = el.getAsJsonObject();
        } catch (RuntimeException e) {
            return raw; // malformed: copied as-is; the validator reports it after the merge
        }
        List<String> dropped = o.keySet().stream().filter(k -> !MCMETA_KEYS.contains(k)).sorted().toList();
        if (dropped.isEmpty()) return raw;
        JsonObject clean = new JsonObject();
        for (String k : o.keySet()) {
            if (MCMETA_KEYS.contains(k)) clean.add(k, o.get(k));
        }
        notes.add(NOTE_PREFIX + "removed " + String.join(", ", dropped) + " from pack.mcmeta"
                + (dropped.contains("filter") ? " (a datapack may not hide vanilla assets)" : ""));
        return new GsonBuilder().disableHtmlEscaping().create().toJson(clean).getBytes(StandardCharsets.UTF_8);
    }

    /** @return the safe overlay directory names a resource-pack mcmeta declares */
    private static Set<String> overlayDirs(byte[] mcmeta) {
        Set<String> out = new LinkedHashSet<>();
        try {
            JsonElement el = JsonParser.parseString(new String(mcmeta, StandardCharsets.UTF_8));
            if (!el.isJsonObject()) return out;
            JsonElement ov = el.getAsJsonObject().get("overlays");
            if (ov == null || !ov.isJsonObject()) return out;
            JsonObject overlays = ov.getAsJsonObject();
            if (!overlays.has("entries") || !overlays.get("entries").isJsonArray()) return out;
            JsonArray entries = overlays.getAsJsonArray("entries");
            for (JsonElement e : entries) {
                if (!e.isJsonObject() || !e.getAsJsonObject().has("directory")) continue;
                String dir = SafeZip.safeName(e.getAsJsonObject().get("directory").getAsString());
                if (dir != null && !dir.equals("assets") && !dir.equals("data")) out.add(dir);
            }
        } catch (RuntimeException e) {
            // Malformed mcmeta: copied as-is, no overlays; the validator reports it after the merge.
        }
        return out;
    }
}
