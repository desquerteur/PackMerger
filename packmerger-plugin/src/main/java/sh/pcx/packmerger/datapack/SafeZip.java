package sh.pcx.packmerger.datapack;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Safe, bounded reading of zip files and pack folders that come from outside the
 * plugin (datapacks, downloaded resource packs). Holds no Bukkit references.
 *
 * <ul>
 *   <li>Entry names are normalised by {@link #safeName}; absolute paths, drive
 *       letters, {@code ..} segments, NUL/control characters and colons are
 *       rejected, so nothing can be written outside the pack root.</li>
 *   <li>Symbolic links (unix-mode zip entries, or links inside a folder) are skipped:
 *       a link could otherwise pull arbitrary server files into a public pack.</li>
 *   <li>{@link ZipLimits} are enforced on bytes actually read; a header that lies
 *       about an entry's size does not help a zip bomb.</li>
 * </ul>
 *
 * <p>Zips are read with {@code java.util.zip}; because it does not expose the unix
 * mode that marks symlink entries, {@link #symlinkEntries} reads that from the
 * central directory itself (no extra library, so the shaded jar stays small).</p>
 */
public final class SafeZip {

    private SafeZip() {}

    /** A limit from {@link ZipLimits} was exceeded. The message is meant for operators. */
    public static final class ZipLimitException extends IOException {
        public ZipLimitException(String message) {
            super(message);
        }
    }

    /** Receives one file of a {@link Tree}: its pack-relative name and a bounded stream. */
    public interface EntryVisitor {
        void visit(String name, InputStream in) throws IOException;
    }

    // -------------------------------------------------------------------------
    // Names
    // -------------------------------------------------------------------------

    /**
     * Normalises a zip entry or relative file name to forward slashes without empty
     * or {@code .} segments.
     *
     * @return the normalised name, or {@code null} if the name is unsafe or empty
     */
    public static String safeName(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7f || c == ':') return null;
        }
        String n = raw.replace('\\', '/');
        if (n.startsWith("/")) return null;
        StringBuilder out = new StringBuilder(n.length());
        for (String segment : n.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) return null;
            if (!out.isEmpty()) out.append('/');
            out.append(segment);
        }
        return out.isEmpty() ? null : out.toString();
    }

    // -------------------------------------------------------------------------
    // Trees
    // -------------------------------------------------------------------------

    /**
     * The files of one zip or folder, by safe name. All streams opened from one tree
     * share its total-bytes budget.
     */
    public interface Tree extends Closeable {
        /** @return every readable file's safe name, sorted (folders and symlinks excluded) */
        List<String> names();

        /** @return {@code true} if {@code name} is one of {@link #names()} */
        boolean contains(String name);

        /** @return entries skipped because their name was unsafe or they were symlinks */
        int skipped();

        /** @return a stream that enforces the entry, total and ratio limits */
        InputStream open(String name) throws IOException;

        /**
         * Copies one file to a new temp file in {@code tempDir}, bounded by the entry
         * limit and counted against the total. The caller deletes the file.
         */
        default Path copyToTemp(String name, Path tempDir) throws IOException {
            Files.createDirectories(tempDir);
            Path tmp = Files.createTempFile(tempDir, "pm-nested-", ".zip");
            try (InputStream in = open(name); OutputStream out = Files.newOutputStream(tmp)) {
                in.transferTo(out);
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
            return tmp;
        }
    }

    /**
     * Opens a zip file or a folder as a {@link Tree}.
     *
     * @throws ZipLimitException if the file is too large or has too many entries
     * @throws IOException       if the zip cannot be read (corrupt or still being written)
     */
    public static Tree open(Path source, ZipLimits limits) throws IOException {
        return open(source, limits, new AtomicLong());
    }

    /** Like {@link #open(Path, ZipLimits)}, sharing a total-bytes counter with a parent tree. */
    static Tree open(Path source, ZipLimits limits, AtomicLong totalRead) throws IOException {
        if (Files.isDirectory(source)) return new DirTree(source, limits, totalRead);
        return new ZipTree(source, limits, totalRead);
    }

    /**
     * Visits every file of a zip or folder under {@code prefix} (stripped from the
     * name passed to the visitor), enforcing every limit.
     */
    public static void forEach(Path source, String prefix, ZipLimits limits, EntryVisitor visitor)
            throws IOException {
        String p = prefix == null ? "" : prefix;
        try (Tree tree = open(source, limits)) {
            for (String name : tree.names()) {
                if (!name.startsWith(p) || name.length() == p.length()) continue;
                try (InputStream in = tree.open(name)) {
                    visitor.visit(name.substring(p.length()), in);
                }
            }
        }
    }

    private static final class ZipTree implements Tree {
        private final ZipFile zip;
        private final ZipLimits limits;
        private final AtomicLong totalRead;
        private final Map<String, ZipEntry> entries = new TreeMap<>();
        private int skipped;

        ZipTree(Path file, ZipLimits limits, AtomicLong totalRead) throws IOException {
            this.limits = limits;
            this.totalRead = totalRead;
            long size = Files.size(file);
            if (size > limits.maxFileBytes()) {
                throw new ZipLimitException("file is " + ZipLimits.describe(size) + "; the limit is "
                        + ZipLimits.describe(limits.maxFileBytes()) + " (datapacks.limits.max-file-mb)");
            }
            this.zip = new ZipFile(file.toFile());
            try {
                if (zip.size() > limits.maxEntries()) {
                    throw new ZipLimitException("more than " + limits.maxEntries()
                            + " entries (datapacks.limits.max-entries)");
                }
                Set<String> links = symlinkEntries(file);
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    String name = safeName(e.getName());
                    if (name == null || links.contains(e.getName())) {
                        skipped++;
                        continue;
                    }
                    entries.putIfAbsent(name, e);
                }
            } catch (IOException | RuntimeException e) {
                zip.close();
                throw e;
            }
        }

        @Override public List<String> names() { return List.copyOf(entries.keySet()); }
        @Override public boolean contains(String name) { return entries.containsKey(name); }
        @Override public int skipped() { return skipped; }

        @Override
        public InputStream open(String name) throws IOException {
            ZipEntry e = entries.get(name);
            if (e == null) throw new IOException("no such entry: " + name);
            return new BoundedStream(zip.getInputStream(e), name, e.getCompressedSize(), limits, totalRead);
        }

        @Override public void close() throws IOException { zip.close(); }
    }

    private static final class DirTree implements Tree {
        private final Path base;
        private final ZipLimits limits;
        private final AtomicLong totalRead;
        private final Map<String, Path> files = new TreeMap<>();
        private int skipped;

        DirTree(Path base, ZipLimits limits, AtomicLong totalRead) throws IOException {
            this.base = base;
            this.limits = limits;
            this.totalRead = totalRead;
            int[] count = {0};
            Path realBase = base.toRealPath();
            // Links are not followed: a symlinked file or folder shows up as a link and is skipped.
            // NTFS junctions and other reparse points look like plain folders to walkFileTree
            // (isOther() is their only mark), so folders that are "other" or resolve outside
            // the pack are skipped too.
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (dir.equals(base)) return FileVisitResult.CONTINUE;
                    if (++count[0] > limits.maxEntries()) {
                        throw new ZipLimitException("more than " + limits.maxEntries()
                                + " files and folders (datapacks.limits.max-entries)");
                    }
                    if (attrs.isSymbolicLink() || attrs.isOther() || !isInside(dir, realBase)) {
                        skipped++;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (++count[0] > limits.maxEntries()) {
                        throw new ZipLimitException("more than " + limits.maxEntries()
                                + " files and folders (datapacks.limits.max-entries)");
                    }
                    if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) {
                        skipped++;
                        return FileVisitResult.CONTINUE;
                    }
                    String name = safeName(base.relativize(file).toString());
                    if (name == null) {
                        skipped++;
                    } else {
                        files.putIfAbsent(name, file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    skipped++;
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        /** @return {@code true} if {@code dir}, with every link and junction resolved, is inside {@code realBase} */
        private static boolean isInside(Path dir, Path realBase) {
            try {
                return dir.toRealPath().startsWith(realBase);
            } catch (IOException e) {
                return false;
            }
        }

        @Override public List<String> names() { return List.copyOf(files.keySet()); }
        @Override public boolean contains(String name) { return files.containsKey(name); }
        @Override public int skipped() { return skipped; }

        @Override
        public InputStream open(String name) throws IOException {
            Path p = files.get(name);
            if (p == null) throw new IOException("no such file: " + name);
            return new BoundedStream(Files.newInputStream(p), name, -1, limits, totalRead);
        }

        @Override public void close() { }
    }

    /**
     * Counts the bytes actually delivered and fails as soon as the entry limit, the
     * shared total, or (past {@link ZipLimits#RATIO_CHECK_THRESHOLD}) the compression
     * ratio is exceeded.
     */
    private static final class BoundedStream extends FilterInputStream {
        private final String name;
        private final long compressedSize;
        private final ZipLimits limits;
        private final AtomicLong totalRead;
        private long read;

        BoundedStream(InputStream in, String name, long compressedSize, ZipLimits limits, AtomicLong totalRead) {
            super(in);
            this.name = name;
            this.compressedSize = compressedSize;
            this.limits = limits;
            this.totalRead = totalRead;
        }

        private void count(long n) throws ZipLimitException {
            if (n <= 0) return;
            read += n;
            long total = totalRead.addAndGet(n);
            if (read > limits.maxEntryBytes()) {
                throw new ZipLimitException(name + " is larger than " + ZipLimits.describe(limits.maxEntryBytes())
                        + " (datapacks.limits.max-entry-mb)");
            }
            if (total > limits.maxTotalUncompressedBytes()) {
                throw new ZipLimitException("more than " + ZipLimits.describe(limits.maxTotalUncompressedBytes())
                        + " uncompressed (datapacks.limits.max-uncompressed-mb)");
            }
            if (compressedSize > 0 && read > ZipLimits.RATIO_CHECK_THRESHOLD
                    && read / compressedSize > limits.maxCompressionRatio()) {
                throw new ZipLimitException(name + " expands more than " + limits.maxCompressionRatio()
                        + ":1 (datapacks.limits.max-compression-ratio); possible zip bomb");
            }
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            count(n);
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = super.skip(n);
            count(s);
            return s;
        }
    }

    // -------------------------------------------------------------------------
    // Symlink entries (unix mode in the central directory)
    // -------------------------------------------------------------------------

    private static final int EOCD = 0x06054b50;
    private static final int ZIP64_LOCATOR = 0x07064b50;
    private static final int ZIP64_EOCD = 0x06064b50;
    private static final int CEN = 0x02014b50;
    /** Largest central directory read when looking for symlinks. */
    private static final long MAX_CEN_BYTES = 64 * ZipLimits.MIB;

    /**
     * Names (as {@link ZipFile} decodes them) of entries that a unix zip tool stored as
     * symbolic links: "version made by" host 3 and {@code S_IFLNK} in the external
     * attributes. {@code java.util.zip} does not expose these fields, so the central
     * directory is read here; on anything unexpected the result is empty (the entry is
     * then read as the small file holding the link target, which is harmless because
     * nothing is ever extracted to disk).
     */
    static Set<String> symlinkEntries(Path file) {
        Set<String> out = new HashSet<>();
        try (SeekableByteChannel ch = Files.newByteChannel(file)) {
            long size = ch.size();
            int tailLen = (int) Math.min(size, 22 + 0xFFFF);
            ByteBuffer tail = read(ch, size - tailLen, tailLen);
            int eocd = -1;
            for (int i = tailLen - 22; i >= 0; i--) {
                if (tail.getInt(i) == EOCD) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) return out;
            long eocdPos = size - tailLen + eocd;
            long cenSize = Integer.toUnsignedLong(tail.getInt(eocd + 12));
            long cenEnd = eocdPos;
            if (cenSize == 0xFFFFFFFFL && eocdPos >= 20) {
                ByteBuffer loc = read(ch, eocdPos - 20, 20);
                if (loc.getInt(0) != ZIP64_LOCATOR) return out;
                long z64 = loc.getLong(8);
                ByteBuffer rec = read(ch, z64, 56);
                if (rec.getInt(0) != ZIP64_EOCD) return out;
                cenSize = rec.getLong(40);
                cenEnd = z64;
            }
            if (cenSize <= 0 || cenSize > MAX_CEN_BYTES || cenSize > cenEnd) return out;
            ByteBuffer cen = read(ch, cenEnd - cenSize, (int) cenSize);
            int p = 0;
            while (p + 46 <= cen.limit() && cen.getInt(p) == CEN) {
                int madeBy = Short.toUnsignedInt(cen.getShort(p + 4));
                int nameLen = Short.toUnsignedInt(cen.getShort(p + 28));
                int extraLen = Short.toUnsignedInt(cen.getShort(p + 30));
                int commentLen = Short.toUnsignedInt(cen.getShort(p + 32));
                int external = cen.getInt(p + 38);
                if (p + 46 + nameLen > cen.limit()) break;
                if ((madeBy >> 8) == 3 && ((external >>> 16) & 0170000) == 0120000) {
                    byte[] name = new byte[nameLen];
                    cen.get(p + 46, name);
                    out.add(new String(name, StandardCharsets.UTF_8));
                }
                p += 46 + nameLen + extraLen + commentLen;
            }
        } catch (IOException | RuntimeException e) {
            out.clear();
        }
        return out;
    }

    private static ByteBuffer read(SeekableByteChannel ch, long pos, int len) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        ch.position(pos);
        while (b.hasRemaining() && ch.read(b) > 0) { }
        b.flip();
        return b;
    }

    // -------------------------------------------------------------------------
    // Small shared helpers
    // -------------------------------------------------------------------------

    /**
     * @return the prefix under which the pack starts: {@code ""} when one of
     *         {@code markers} is at the root, {@code "<folder>/"} when exactly one
     *         top-level folder holds a marker and no other folder exists next to it
     *         (loose files such as a readme are allowed), else {@code ""}
     */
    static String wrapperPrefix(List<String> names, List<String> markers) {
        for (String n : names) {
            if (hasMarker(n, "", markers)) return "";
        }
        List<String> tops = new ArrayList<>();
        List<String> wrapped = new ArrayList<>();
        for (String n : names) {
            int slash = n.indexOf('/');
            if (slash < 0) continue;
            String top = n.substring(0, slash);
            if (top.equalsIgnoreCase("__MACOSX") || top.startsWith(".")) continue;
            if (!tops.contains(top)) tops.add(top);
            if (!wrapped.contains(top) && hasMarker(n, top + "/", markers)) wrapped.add(top);
        }
        return tops.size() == 1 && wrapped.size() == 1 ? wrapped.get(0) + "/" : "";
    }

    private static boolean hasMarker(String name, String prefix, List<String> markers) {
        if (!name.startsWith(prefix)) return false;
        String rest = name.substring(prefix.length());
        for (String m : markers) {
            if (m.endsWith("/") ? rest.startsWith(m) : rest.equals(m)) return true;
        }
        return false;
    }

    /** Moves {@code from} over {@code to}, atomically where the file system allows. */
    static void moveAtomic(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** @return the names under {@code prefix}, with the prefix stripped, in order */
    static List<String> under(List<String> names, String prefix) {
        if (prefix.isEmpty()) return names;
        List<String> out = new ArrayList<>();
        for (String n : names) {
            if (n.startsWith(prefix) && n.length() > prefix.length()) out.add(n.substring(prefix.length()));
        }
        return Collections.unmodifiableList(out);
    }
}
