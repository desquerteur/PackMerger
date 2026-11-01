package sh.pcx.packmerger.datapack;

/** What a file or folder in a datapacks folder turned out to be. */
public enum DatapackKind {
    /** Has {@code data/} (or a datapack {@code pack.mcmeta} without root assets). */
    DATAPACK,
    /** Has {@code assets/} but no {@code data/}: a resource pack put into {@code datapacks/}. */
    RESOURCE_PACK_ONLY,
    /** Neither {@code data/}, {@code assets/} nor {@code pack.mcmeta}. */
    NOT_A_PACK,
    /** The zip cannot be read (corrupt, or still being written). */
    UNREADABLE,
    /** A {@link ZipLimits} limit was exceeded. */
    LIMIT_EXCEEDED
}
