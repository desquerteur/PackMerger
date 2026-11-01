package sh.pcx.packmerger.merge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class MergeInputsTest {

    @TempDir Path packs;

    private static final List<String> SETTINGS = List.of("model-policy=WHOLE_FILE", "compression-level=6",
            "strip-junk-files=true", "plugin=1.4.0", "minecraft=26.2");

    private Function<String, File> resolver() {
        return name -> packs.resolve(name).toFile();
    }

    private String fp(List<String> order, List<String> settings) {
        return MergeInputs.fingerprint(order, resolver(), packs.toFile(), settings);
    }

    private void write(String name, String content, long mtime) throws IOException {
        Path p = packs.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        Files.setLastModifiedTime(p, FileTime.fromMillis(mtime));
    }

    @Test
    void sameInputs_sameFingerprint() throws IOException {
        write("a.zip", "aaa", 1000);
        write(".datapack-packs/dp-x.zip", "xx", 2000);
        assertEquals(fp(List.of("a.zip", ".datapack-packs/dp-x.zip"), SETTINGS),
                fp(List.of("a.zip", ".datapack-packs/dp-x.zip"), SETTINGS));
    }

    @Test
    void packChange_changesFingerprint() throws IOException {
        write("a.zip", "aaa", 1000);
        String before = fp(List.of("a.zip"), SETTINGS);
        write("a.zip", "aaaa", 1000);
        assertNotEquals(before, fp(List.of("a.zip"), SETTINGS));
        String size = fp(List.of("a.zip"), SETTINGS);
        Files.setLastModifiedTime(packs.resolve("a.zip"), FileTime.fromMillis(5000));
        assertNotEquals(size, fp(List.of("a.zip"), SETTINGS));
    }

    @Test
    void addAndRemove_changeFingerprint() throws IOException {
        write("a.zip", "aaa", 1000);
        write("b.zip", "bbb", 1000);
        String one = fp(List.of("a.zip"), SETTINGS);
        String two = fp(List.of("a.zip", "b.zip"), SETTINGS);
        assertNotEquals(one, two);
        Files.delete(packs.resolve("b.zip"));
        assertNotEquals(two, fp(List.of("a.zip", "b.zip"), SETTINGS));
    }

    @Test
    void orderChange_changesFingerprint() throws IOException {
        write("a.zip", "aaa", 1000);
        write("b.zip", "bbb", 1000);
        assertNotEquals(fp(List.of("a.zip", "b.zip"), SETTINGS), fp(List.of("b.zip", "a.zip"), SETTINGS));
    }

    @Test
    void settingsChange_changesFingerprint() throws IOException {
        write("a.zip", "aaa", 1000);
        String base = fp(List.of("a.zip"), SETTINGS);
        assertNotEquals(base, fp(List.of("a.zip"), List.of("model-policy=DEEP", "compression-level=6",
                "strip-junk-files=true", "plugin=1.4.0", "minecraft=26.2")));
        assertNotEquals(base, fp(List.of("a.zip"), List.of("model-policy=WHOLE_FILE", "compression-level=9",
                "strip-junk-files=true", "plugin=1.4.0", "minecraft=26.2")));
        assertNotEquals(base, fp(List.of("a.zip"), List.of("model-policy=WHOLE_FILE", "compression-level=6",
                "strip-junk-files=true", "plugin=1.4.0", "minecraft=26.3")));
    }

    @Test
    void customMcmetaOverride_changesFingerprint() throws IOException {
        write("a.zip", "aaa", 1000);
        String base = fp(List.of("a.zip"), SETTINGS);
        write("pack.mcmeta", "{}", 1000);
        assertNotEquals(base, fp(List.of("a.zip"), SETTINGS));
    }

    @Test
    void sidecar_isNextToTheOutput() {
        File out = new File("output", "merged-pack.zip");
        assertEquals(new File("output", "merged-pack.zip.inputs"), MergeInputs.sidecar(out));
    }
}
