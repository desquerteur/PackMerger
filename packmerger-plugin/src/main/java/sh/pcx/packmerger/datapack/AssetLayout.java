package sh.pcx.packmerger.datapack;

/** Where a datapack keeps its resource-pack part, as found by {@link DatapackInspector}. */
public enum AssetLayout {
    /** No resource pack inside (a normal, data-only datapack). */
    NONE,
    /** {@code assets/} next to {@code data/} ("hybrid" pack), or a plain resource pack. */
    ROOT_ASSETS,
    /** A top-level folder (e.g. {@code resourcepack/}) holding a resource pack. */
    SUBFOLDER,
    /** A resource-pack zip stored inside the datapack (one level deep). */
    NESTED_ZIP
}
