package sh.pcx.packmerger.datapack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Builders for fixture zips and folders written by the tests (no binary fixtures in the repo). */
final class Fixtures {

    private Fixtures() {}

    static final String DATA_MCMETA = "{\"pack\":{\"description\":\"a datapack\",\"pack_format\":94}}";
    static final String RP_MCMETA = "{\"pack\":{\"description\":\"a resource pack\",\"pack_format\":88}}";

    /** An ordered name → content map. */
    static Map<String, Object> entries(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static byte[] bytes(Object content) {
        return content instanceof byte[] b ? b : String.valueOf(content).getBytes(StandardCharsets.UTF_8);
    }

    /** Writes a zip; values are Strings or byte[] (nested zips). */
    static Path zip(Path file, Map<String, Object> entries) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, zipBytes(entries));
        return file;
    }

    static byte[] zipBytes(Map<String, Object> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, Object> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(bytes(e.getValue()));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /**
     * A zip with one symlink entry (unix mode 0120777, as {@code zip -y} writes it) next to
     * the given entries. Written with java.util.zip, then the link's central-directory
     * record is patched ("version made by" host 3 = unix, external attributes = mode
     * &lt;&lt; 16). Test code must not use commons-compress: the shadow plugin's minimize
     * step also scans test classes and would ship the library's dependencies.
     */
    static Path zipWithSymlink(Path file, String linkName, String target, Map<String, Object> entries) throws IOException {
        Map<String, Object> all = new LinkedHashMap<>(entries);
        all.put(linkName, target);
        byte[] zip = zipBytes(all);
        ByteBuffer buf = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
        byte[] name = linkName.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i + 46 <= zip.length; i++) {
            if (buf.getInt(i) != 0x02014b50) continue;
            int nameLen = Short.toUnsignedInt(buf.getShort(i + 28));
            if (nameLen == name.length && Arrays.equals(Arrays.copyOfRange(zip, i + 46, i + 46 + nameLen), name)) {
                buf.put(i + 5, (byte) 3);
                buf.putInt(i + 38, 0120777 << 16);
            }
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, zip);
        return file;
    }

    /** Writes a folder; values are Strings or byte[]. */
    static Path dir(Path dir, Map<String, Object> files) throws IOException {
        for (Map.Entry<String, Object> e : files.entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, bytes(e.getValue()));
        }
        Files.createDirectories(dir);
        return dir;
    }

    /** Reads a zip into a sorted name → content map. */
    static Map<String, String> read(Path zip) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                out.put(e.getName(), new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /** A typical hybrid datapack: data/ plus assets/ at the root. */
    static Map<String, Object> hybrid() {
        return entries(
                "pack.mcmeta", DATA_MCMETA,
                "data/terra/worldgen/x.json", "{}",
                "assets/terra/textures/block/rock.png", "PNG",
                "assets/terra/lang/en_us.json", "{\"a\":\"b\"}",
                "pack.png", "ICON");
    }
}
