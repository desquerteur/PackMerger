package sh.pcx.packmerger.datapack;

/**
 * Safety limits for reading datapacks and downloaded resource packs through
 * {@link SafeZip}. Every byte limit is enforced on bytes actually read, so a zip
 * whose headers lie about entry sizes cannot get past it.
 *
 * @param maxFileBytes              largest zip file that is opened at all
 * @param maxTotalUncompressedBytes bytes that may be read from one source in total
 * @param maxEntryBytes             bytes that may be read from one entry
 * @param maxEntries                entries (files and folders) one source may have
 * @param maxCompressionRatio       uncompressed:compressed ratio allowed per entry
 *                                  (checked once an entry has produced more than 1 MiB)
 */
public record ZipLimits(long maxFileBytes, long maxTotalUncompressedBytes, long maxEntryBytes,
                        int maxEntries, int maxCompressionRatio) {

    public static final long MIB = 1024L * 1024L;

    /** Entries smaller than this are never rejected for their compression ratio. */
    public static final long RATIO_CHECK_THRESHOLD = MIB;

    private static final long DEFAULT_FILE = 200 * MIB;
    private static final long DEFAULT_TOTAL = 512 * MIB;
    private static final long DEFAULT_ENTRY = 64 * MIB;
    private static final int DEFAULT_ENTRIES = 50_000;
    private static final int DEFAULT_RATIO = 100;

    /** The config defaults: 200 MiB file, 512 MiB total, 64 MiB entry, 50 000 entries, 100:1. */
    public static final ZipLimits DEFAULTS =
            new ZipLimits(DEFAULT_FILE, DEFAULT_TOTAL, DEFAULT_ENTRY, DEFAULT_ENTRIES, DEFAULT_RATIO);

    /** Non-positive values (a typo in config.yml) fall back to the defaults. */
    public ZipLimits {
        if (maxFileBytes <= 0) maxFileBytes = DEFAULT_FILE;
        if (maxTotalUncompressedBytes <= 0) maxTotalUncompressedBytes = DEFAULT_TOTAL;
        if (maxEntryBytes <= 0) maxEntryBytes = DEFAULT_ENTRY;
        if (maxEntries <= 0) maxEntries = DEFAULT_ENTRIES;
        if (maxCompressionRatio <= 0) maxCompressionRatio = DEFAULT_RATIO;
    }

    /** Builds limits from the megabyte values used in config.yml. */
    public static ZipLimits ofMegabytes(long fileMb, long totalMb, long entryMb, int entries, int ratio) {
        return new ZipLimits(fileMb * MIB, totalMb * MIB, entryMb * MIB, entries, ratio);
    }

    /** @return a readable size such as {@code "64 MiB"} or {@code "1.5 MiB"} */
    public static String describe(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < MIB) return String.format("%.1f KiB", bytes / 1024.0);
        double mib = bytes / (double) MIB;
        return mib == Math.rint(mib) ? (long) mib + " MiB" : String.format("%.1f MiB", mib);
    }
}
