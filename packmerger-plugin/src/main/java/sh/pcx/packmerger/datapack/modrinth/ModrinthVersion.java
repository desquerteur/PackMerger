package sh.pcx.packmerger.datapack.modrinth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The parts of a Modrinth API v2 version object PackMerger uses. Unknown fields are
 * ignored; missing ones become {@code null} / empty.
 */
public record ModrinthVersion(String id, String projectId, String versionNumber, String versionType,
                              List<String> gameVersions, List<String> loaders, Instant published,
                              List<Dependency> dependencies, List<VersionFile> files) {

    /**
     * @param type {@code required}, {@code optional}, {@code incompatible} or {@code embedded}
     */
    public record Dependency(String versionId, String projectId, String fileName, String type) {}

    /**
     * @param sha1     lowercase hex, or {@code null} if absent or not 40 hex digits
     * @param sha512   lowercase hex, or {@code null} if absent or not 128 hex digits
     * @param fileType {@code required-resource-pack}, {@code optional-resource-pack}, … or {@code null}
     */
    public record VersionFile(String url, String filename, boolean primary, long size,
                              String sha1, String sha512, String fileType) {}

    public ModrinthVersion {
        gameVersions = gameVersions == null ? List.of() : List.copyOf(gameVersions);
        loaders = loaders == null ? List.of() : List.copyOf(loaders);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        files = files == null ? List.of() : List.copyOf(files);
    }

    /** @return the primary file, or the first file if none is marked primary, or {@code null} */
    public VersionFile primaryFile() {
        for (VersionFile f : files) if (f.primary()) return f;
        return files.isEmpty() ? null : files.get(0);
    }

    /** Parses a version object; throws {@link IllegalArgumentException} if it is not one. */
    public static ModrinthVersion parse(JsonElement el) {
        if (el == null || !el.isJsonObject()) throw new IllegalArgumentException("version is not an object");
        JsonObject o = el.getAsJsonObject();
        String id = str(o, "id");
        if (id == null) throw new IllegalArgumentException("version without id");
        List<Dependency> deps = new ArrayList<>();
        for (JsonElement d : arr(o, "dependencies")) {
            if (!d.isJsonObject()) continue;
            JsonObject dj = d.getAsJsonObject();
            deps.add(new Dependency(str(dj, "version_id"), str(dj, "project_id"), str(dj, "file_name"),
                    str(dj, "dependency_type")));
        }
        List<VersionFile> files = new ArrayList<>();
        for (JsonElement f : arr(o, "files")) {
            if (!f.isJsonObject()) continue;
            JsonObject fj = f.getAsJsonObject();
            JsonObject hashes = fj.has("hashes") && fj.get("hashes").isJsonObject() ? fj.getAsJsonObject("hashes") : new JsonObject();
            files.add(new VersionFile(str(fj, "url"), str(fj, "filename"),
                    fj.has("primary") && fj.get("primary").isJsonPrimitive() && fj.get("primary").getAsBoolean(),
                    fj.has("size") && fj.get("size").isJsonPrimitive() ? fj.get("size").getAsLong() : -1,
                    hex(str(hashes, "sha1"), 40), hex(str(hashes, "sha512"), 128), str(fj, "file_type")));
        }
        Instant published = null;
        String date = str(o, "date_published");
        if (date != null) {
            try {
                published = Instant.parse(date);
            } catch (RuntimeException ignored) {
                // Unparseable date: sorts as oldest.
            }
        }
        return new ModrinthVersion(id, str(o, "project_id"), str(o, "version_number"), str(o, "version_type"),
                strings(o, "game_versions"), strings(o, "loaders"), published, deps, files);
    }

    /**
     * @return the hash in lowercase if it is exactly {@code length} hex digits, else
     *         {@code null} (a file without a usable SHA-512 is never offered or stored)
     */
    static String hex(String hash, int length) {
        if (hash == null || hash.length() != length) return null;
        for (int i = 0; i < length; i++) {
            if (Character.digit(hash.charAt(i), 16) < 0) return null;
        }
        return hash.toLowerCase(java.util.Locale.ROOT);
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : arr(o, key)) if (e.isJsonPrimitive()) out.add(e.getAsString());
        return out;
    }
}
