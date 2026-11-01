package sh.pcx.packmerger.merge;

import sh.pcx.packmerger.config.ConfigManager;
import sh.pcx.packmerger.discovery.PluginPackStager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/**
 * A fingerprint of everything a merge reads, computed before merging. When it equals
 * the fingerprint stored next to the live output ({@code <output>.inputs}), an
 * automatic merge (watcher, rescan, startup) is skipped: the result would be the same
 * bytes. {@code /pm merge} and {@code /pm rebuild} always merge.
 *
 * <p>Cheap: packs are fingerprinted by size and mtime (folders by a walk), as the
 * plugin-pack stager does; nothing is read or hashed.</p>
 */
public final class MergeInputs {

    private MergeInputs() {}

    /**
     * @param order         the merge order (highest priority first)
     * @param resolve       pack name → file or folder
     * @param packsFolder   {@code packs/} (for the custom pack.mcmeta / pack.png overrides)
     * @param cfg           config (merge settings that change the output)
     * @param pluginVersion PackMerger's version (a new version may merge differently)
     * @param mcVersion     the running Minecraft version (the generated pack.mcmeta depends on it)
     */
    public static String fingerprint(List<String> order, Function<String, File> resolve, File packsFolder,
                                     ConfigManager cfg, String pluginVersion, String mcVersion) {
        return fingerprint(order, resolve, packsFolder, List.of(
                "model-policy=" + cfg.getModelPolicy(),
                "compression-level=" + cfg.getCompressionLevel(),
                "strip-junk-files=" + cfg.isStripJunkFiles(),
                // Not part of the zip, but only a merge runs them: Bedrock conversion and copies.
                "bedrock=" + cfg.isBedrockEnabled(),
                "output-copy-to=" + cfg.getOutputCopyTo(),
                "plugin=" + pluginVersion,
                "minecraft=" + mcVersion));
    }

    /** Pure form: {@code settings} are opaque strings that change the output when they change. */
    public static String fingerprint(List<String> order, Function<String, File> resolve, File packsFolder,
                                     List<String> settings) {
        StringBuilder sb = new StringBuilder("v1\n");
        for (String name : order) {
            File f = resolve.apply(name);
            sb.append("pack ").append(name).append(" @ ").append(f == null ? "?" : f.getAbsolutePath())
                    .append(" = ").append(f == null ? "absent" : PluginPackStager.fingerprint(f)).append('\n');
        }
        if (packsFolder != null) {
            sb.append("custom pack.mcmeta = ").append(PluginPackStager.fingerprint(new File(packsFolder, "pack.mcmeta"))).append('\n');
            sb.append("custom pack.png = ").append(PluginPackStager.fingerprint(new File(packsFolder, "pack.png"))).append('\n');
        }
        for (String s : settings) sb.append("setting ").append(s).append('\n');
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the sidecar holding the inputs fingerprint of an output zip */
    public static File sidecar(File outputZip) {
        return new File(outputZip.getParentFile(), outputZip.getName() + ".inputs");
    }
}
