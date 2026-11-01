package sh.pcx.packmerger.datapack;

import sh.pcx.packmerger.discovery.PluginPackStager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Classifies one datapack candidate (zip or folder) and finds its resource-pack
 * part. Pure: no Bukkit references, never throws (problems become a
 * {@link DatapackKind}). Reads only the zip's central directory, plus nested zips,
 * which are copied to a bounded temp file.
 *
 * <p>Detection order (content-based; names only break ties alphabetically):</p>
 * <ol>
 *   <li>a single wrapper folder is unwrapped;</li>
 *   <li>{@code data/} present → {@link DatapackKind#DATAPACK}; else {@code assets/}
 *       → {@link DatapackKind#RESOURCE_PACK_ONLY}; else a {@code pack.mcmeta}
 *       → {@code DATAPACK} (a datapack shell); else {@link DatapackKind#NOT_A_PACK};</li>
 *   <li>{@code assets/} at the pack root → {@link AssetLayout#ROOT_ASSETS};</li>
 *   <li>a top-level folder (not {@code data}/{@code assets}) holding {@code assets/}
 *       and no {@code data/} of its own → {@link AssetLayout#SUBFOLDER};</li>
 *   <li>a {@code .zip} at depth ≤ 2 whose own root has {@code assets/} →
 *       {@link AssetLayout#NESTED_ZIP} (zips inside that zip are ignored);</li>
 *   <li>otherwise {@link AssetLayout#NONE}: a normal data-only datapack.</li>
 * </ol>
 */
public final class DatapackInspector {

    static final List<String> PACK_MARKERS = List.of("data/", "assets/", "pack.mcmeta");
    static final List<String> RP_MARKERS = List.of("assets/", "pack.mcmeta");

    private final ZipLimits limits;
    private final Path tempDir;

    /**
     * @param limits  reading limits
     * @param tempDir where nested zips are copied while being looked at ({@code null}: system temp)
     */
    public DatapackInspector(ZipLimits limits, Path tempDir) {
        this.limits = limits == null ? ZipLimits.DEFAULTS : limits;
        this.tempDir = tempDir;
    }

    public DatapackInspector(ZipLimits limits) {
        this(limits, null);
    }

    /** Never throws; see the class comment for the rules. */
    public DatapackInfo inspect(Path candidate, String packId, boolean enabledOnServer) {
        boolean directory = Files.isDirectory(candidate);
        String fingerprint = PluginPackStager.fingerprint(candidate.toFile());
        Classification c;
        try (SafeZip.Tree tree = SafeZip.open(candidate, limits)) {
            c = classify(tree);
        } catch (SafeZip.ZipLimitException e) {
            return new DatapackInfo(candidate, packId, enabledOnServer, directory, DatapackKind.LIMIT_EXCEEDED,
                    AssetLayout.NONE, null, 0, fingerprint, null, null, e.getMessage());
        } catch (IOException | RuntimeException e) {
            return new DatapackInfo(candidate, packId, enabledOnServer, directory, DatapackKind.UNREADABLE,
                    AssetLayout.NONE, null, 0, fingerprint, null, null,
                    "corrupt zip (cannot read central directory: " + e.getMessage() + ")");
        }
        String sha1 = null, sha512 = null;
        if (!directory) {
            try {
                String[] h = hashes(candidate);
                sha1 = h[0];
                sha512 = h[1];
            } catch (IOException e) {
                return new DatapackInfo(candidate, packId, enabledOnServer, false, DatapackKind.UNREADABLE,
                        AssetLayout.NONE, null, 0, fingerprint, null, null, "cannot read file: " + e.getMessage());
            }
        }
        return new DatapackInfo(candidate, packId, enabledOnServer, directory, c.kind, c.layout, c.assetRoot,
                c.assetFiles, fingerprint, sha1, sha512, c.detail);
    }

    private record Classification(DatapackKind kind, AssetLayout layout, String assetRoot, int assetFiles,
                                  String detail) {}

    private Classification classify(SafeZip.Tree tree) throws IOException {
        List<String> names = tree.names();
        String root = SafeZip.wrapperPrefix(names, PACK_MARKERS);
        List<String> rel = SafeZip.under(names, root);
        boolean hasData = rel.stream().anyMatch(n -> n.startsWith("data/"));
        int rootAssets = countAssets(rel, "");
        boolean hasMcmeta = rel.contains("pack.mcmeta");
        String unsafe = tree.skipped() > 0 ? "; skipped " + tree.skipped() + " unsafe entr"
                + (tree.skipped() == 1 ? "y" : "ies") + " (path traversal or symlink)" : "";

        if (!hasData && rootAssets > 0) {
            return new Classification(DatapackKind.RESOURCE_PACK_ONLY, AssetLayout.ROOT_ASSETS, root, rootAssets,
                    "resource pack in a datapacks folder (" + rootAssets + " asset files)" + unsafe);
        }
        if (!hasData && !hasMcmeta) {
            return new Classification(DatapackKind.NOT_A_PACK, AssetLayout.NONE, null, 0,
                    "no data/, assets/ or pack.mcmeta" + unsafe);
        }
        if (rootAssets > 0) {
            return new Classification(DatapackKind.DATAPACK, AssetLayout.ROOT_ASSETS, root, rootAssets,
                    "assets next to data/ (" + rootAssets + " files)" + unsafe);
        }

        // A resource pack in a top-level folder (resourcepack/, resource_pack/, resources/ …).
        Set<String> tops = new TreeSet<>();
        for (String n : rel) {
            int slash = n.indexOf('/');
            if (slash > 0) tops.add(n.substring(0, slash));
        }
        for (String top : tops) {
            String lower = top.toLowerCase(Locale.ROOT);
            if (lower.equals("data") || lower.equals("assets") || lower.equals("__macosx") || top.startsWith(".")) continue;
            String prefix = top + "/";
            // A folder with its own data/ is a bundled sub-datapack, and a folder without
            // assets/ has nothing to merge: neither may hide a real resource-pack folder.
            if (rel.stream().anyMatch(n -> n.startsWith(prefix + "data/"))) continue;
            int assets = countAssets(rel, prefix);
            if (assets > 0) {
                return new Classification(DatapackKind.DATAPACK, AssetLayout.SUBFOLDER, root + prefix, assets,
                        "resource pack folder " + prefix + " (" + assets + " files)" + unsafe);
            }
        }

        // A resource-pack zip inside the datapack, at most two levels deep.
        List<String> nested = new ArrayList<>();
        for (String n : rel) {
            if (!n.toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
            if (n.chars().filter(ch -> ch == '/').count() > 1) continue;
            nested.add(n);
        }
        List<String> notes = new ArrayList<>();
        for (String n : nested) {
            NestedResult r = inspectNested(tree, root + n);
            if (r.assets > 0) {
                return new Classification(DatapackKind.DATAPACK, AssetLayout.NESTED_ZIP, root + n, r.assets,
                        "nested zip \"" + n + "\" (" + r.assets + " files)" + unsafe
                                + (r.note == null ? "" : "; " + r.note));
            }
            if (r.note != null) notes.add(n + ": " + r.note);
        }
        return new Classification(DatapackKind.DATAPACK, AssetLayout.NONE, null, 0,
                "data-only" + unsafe + (notes.isEmpty() ? "" : "; " + String.join("; ", notes)));
    }

    private record NestedResult(int assets, String note) {}

    /** Looks inside one nested zip. Zips inside it are not opened (nesting depth 1). */
    private NestedResult inspectNested(SafeZip.Tree outer, String entry) {
        Path tmp = null;
        try {
            tmp = outer.copyToTemp(entry, tempDir());
            try (SafeZip.Tree inner = SafeZip.open(tmp, limits)) {
                List<String> names = inner.names();
                String root = SafeZip.wrapperPrefix(names, RP_MARKERS);
                int assets = countAssets(SafeZip.under(names, root), "");
                boolean deeper = names.stream().anyMatch(n -> n.toLowerCase(Locale.ROOT).endsWith(".zip"));
                String note = assets == 0 && deeper ? "nested zip inside nested zip ignored" : null;
                return new NestedResult(assets, note);
            }
        } catch (SafeZip.ZipLimitException e) {
            return new NestedResult(0, "skipped: " + e.getMessage());
        } catch (IOException | RuntimeException e) {
            return new NestedResult(0, "unreadable (" + e.getMessage() + ")");
        } finally {
            deleteQuietly(tmp);
        }
    }

    Path tempDir() throws IOException {
        return tempDir != null ? tempDir : Path.of(System.getProperty("java.io.tmpdir"));
    }

    static int countAssets(List<String> rel, String prefix) {
        int n = 0;
        String p = prefix + "assets/";
        for (String name : rel) if (name.startsWith(p) && name.length() > p.length()) n++;
        return n;
    }

    static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // temp file; the OS cleans it up eventually
        }
    }

    /** @return {sha1, sha512} of a file, lowercase hex, read once */
    public static String[] hashes(Path file) throws IOException {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    sha1.update(buf, 0, r);
                    sha512.update(buf, 0, r);
                }
            }
            HexFormat hex = HexFormat.of();
            return new String[]{hex.formatHex(sha1.digest()), hex.formatHex(sha512.digest())};
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // both are mandatory JCA algorithms
        }
    }
}
