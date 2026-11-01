package sh.pcx.packmerger.datapack;

import java.nio.file.Path;

/**
 * Result of inspecting one datapack candidate. Immutable; produced by
 * {@link DatapackInspector#inspect}.
 *
 * @param path            the datapack zip or folder
 * @param packId          the server's id for it ({@code file/<name>})
 * @param enabledOnServer whether the server reports it enabled
 * @param directory       {@code true} for an extracted (folder) datapack
 * @param kind            what it is
 * @param layout          where its resource-pack part is ({@link AssetLayout#NONE} when none)
 * @param assetRoot       the resource pack's root inside the source: a name prefix
 *                        ({@code ""} or {@code "Wrapper/"} for root assets,
 *                        {@code "resourcepack/"} for a subfolder) or, for a nested zip,
 *                        the entry name of that zip; {@code null} for {@code NONE}
 * @param assetFiles      number of {@code assets/} files found (0 for {@code NONE})
 * @param fingerprint     cheap change fingerprint (size + mtime, or folder walk)
 * @param sha1            SHA-1 of the zip file, {@code null} for folders or unreadable files
 * @param sha512          SHA-512 of the zip file, {@code null} for folders or unreadable files
 * @param detail          a short operator-facing description or problem
 */
public record DatapackInfo(
        Path path, String packId, boolean enabledOnServer, boolean directory,
        DatapackKind kind, AssetLayout layout, String assetRoot, int assetFiles,
        String fingerprint, String sha1, String sha512, String detail) {

    /** @return {@code true} when there is a resource-pack part to stage */
    public boolean hasAssets() {
        return (kind == DatapackKind.DATAPACK || kind == DatapackKind.RESOURCE_PACK_ONLY)
                && layout != AssetLayout.NONE;
    }

    /** @return the file or folder name */
    public String fileName() {
        return path.getFileName().toString();
    }

    /** @return a copy with a different enabled state (the scan knows it, the inspector does not care) */
    public DatapackInfo withEnabled(String newPackId, boolean enabled) {
        return new DatapackInfo(path, newPackId, enabled, directory, kind, layout, assetRoot, assetFiles,
                fingerprint, sha1, sha512, detail);
    }
}
