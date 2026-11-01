package sh.pcx.packmerger.datapack.modrinth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The parts of a Modrinth project PackMerger uses.
 *
 * @param projectType {@code resourcepack}, {@code datapack}, {@code mod}, …
 */
public record ModrinthProject(String id, String slug, String title, String projectType) {

    /** Parses a project object; throws {@link IllegalArgumentException} if it is not one. */
    public static ModrinthProject parse(JsonElement el) {
        if (el == null || !el.isJsonObject()) throw new IllegalArgumentException("project is not an object");
        JsonObject o = el.getAsJsonObject();
        String id = ModrinthVersion.str(o, "id");
        if (id == null) throw new IllegalArgumentException("project without id");
        return new ModrinthProject(id, ModrinthVersion.str(o, "slug"), ModrinthVersion.str(o, "title"),
                ModrinthVersion.str(o, "project_type"));
    }

    /** @return true for a resource-pack project */
    public boolean isResourcePack() {
        return "resourcepack".equalsIgnoreCase(projectType);
    }
}
