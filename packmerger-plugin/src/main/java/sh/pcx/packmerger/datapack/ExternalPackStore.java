package sh.pcx.packmerger.datapack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.GsonBuilder;
import sh.pcx.packmerger.datapack.modrinth.ModrinthResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Approval state of external resource packs found for datapacks
 * ({@code plugins/PackMerger/external-sources.json}). Kept out of config.yml
 * because saving config.yml through Bukkit drops its comments.
 *
 * <p>An approval binds to the exact file (its SHA-512): a new version of the pack
 * is a new {@link State#PENDING} entry, unless its project is trusted or
 * auto-approve is on. Rejected entries stay, so the same file is not asked again.
 * Every change is written at once (temp file + atomic move). A corrupt file is
 * renamed to {@code .broken-<timestamp>} and the store starts empty; a single
 * unreadable entry is skipped (and reported) while the others are kept.</p>
 */
public final class ExternalPackStore {

    public enum State { PENDING, APPROVED, REJECTED }

    /**
     * One external pack.
     *
     * @param id       {@code mr-} + the first 8 hex of the file's SHA-512 (longer on a clash)
     * @param datapack the datapack it belongs to (path relative to the server folder)
     * @param reason   why it belongs to the datapack ({@code dependency required}, …)
     */
    public record Entry(String id, String provider, String datapack, String datapackSha1,
                        String projectId, String projectSlug, String versionId, String versionNumber,
                        String fileName, String url, long size, String sha1, String sha512,
                        String reason, State state, String decidedBy, Instant decidedAt,
                        Instant firstSeen, Instant lastSeen) {

        Entry with(State newState, String by, Instant at) {
            return new Entry(id, provider, datapack, datapackSha1, projectId, projectSlug, versionId, versionNumber,
                    fileName, url, size, sha1, sha512, reason, newState, by, at, firstSeen, lastSeen);
        }

        Entry seen(String newDatapack, Instant at) {
            return new Entry(id, provider, newDatapack, datapackSha1, projectId, projectSlug, versionId, versionNumber,
                    fileName, url, size, sha1, sha512, reason, state, decidedBy, decidedAt, firstSeen, at);
        }

        /** @return the project slug, or its id if it has none */
        public String projectName() {
            return projectSlug != null ? projectSlug : projectId;
        }
    }

    private final Path file;
    private final Supplier<Instant> clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private String loadProblem;

    public ExternalPackStore(Path file) {
        this(file, Instant::now);
    }

    ExternalPackStore(Path file, Supplier<Instant> clock) {
        this.file = file;
        this.clock = clock;
    }

    /** Reads the file. A corrupt file is moved aside and the store starts empty ({@link #loadProblem()}). */
    public synchronized void load() {
        entries.clear();
        loadProblem = null;
        if (!Files.isRegularFile(file)) return;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            JsonObject list = root.getAsJsonObject().getAsJsonObject("entries");
            List<String> bad = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : list.entrySet()) {
                // One bad entry costs only itself (it is asked for again), not every decision.
                try {
                    entries.put(e.getKey(), fromJson(e.getKey(), e.getValue().getAsJsonObject()));
                } catch (RuntimeException entryError) {
                    bad.add(e.getKey() + " (" + entryError.getMessage() + ")");
                }
            }
            if (!bad.isEmpty()) {
                loadProblem = file.getFileName() + ": skipped " + bad.size() + " unreadable entr"
                        + (bad.size() == 1 ? "y" : "ies") + ": " + String.join(", ", bad);
            }
        } catch (IOException | RuntimeException e) {
            entries.clear();
            Path broken = file.resolveSibling(file.getFileName() + ".broken-" + clock.get().toEpochMilli());
            try {
                Files.move(file, broken);
                loadProblem = file.getFileName() + " was unreadable (" + e.getMessage() + "); moved to "
                        + broken.getFileName() + " and started empty";
            } catch (IOException moveFailed) {
                loadProblem = file.getFileName() + " is unreadable and could not be moved aside: " + moveFailed.getMessage();
            }
        }
    }

    /** @return why the last {@link #load()} started empty, or {@code null} */
    public synchronized String loadProblem() {
        return loadProblem;
    }

    /**
     * Adds a found pack or refreshes an existing one ({@code lastSeen}, datapack path).
     * The state of an existing entry is never changed here.
     *
     * @param autoApprove approve a new entry right away (trusted project or auto-approve)
     * @return the stored entry
     * @throws IllegalArgumentException if the file has no valid SHA-512 (nothing is stored)
     */
    public synchronized Entry upsert(ModrinthResolver.Resolution r, ModrinthResolver.Candidate c,
                                     String datapackLabel, boolean autoApprove) {
        String sha512 = c.file().sha512() == null ? "" : c.file().sha512().toLowerCase(Locale.ROOT);
        if (!sha512.matches("[0-9a-f]{128}")) {
            throw new IllegalArgumentException("no valid sha512 for " + c.file().filename());
        }
        Instant now = clock.get();
        String id = idFor(sha512);
        Entry existing = entries.get(id);
        if (existing != null) {
            Entry updated = existing.seen(datapackLabel, now);
            entries.put(id, updated);
            save();
            return updated;
        }
        Entry e = new Entry(id, "modrinth", datapackLabel, r.datapackSha1(), c.projectId(), c.projectSlug(),
                c.versionId(), c.versionNumber(), c.file().filename(), c.file().url(), c.file().size(),
                c.file().sha1() == null ? null : c.file().sha1().toLowerCase(Locale.ROOT), sha512, c.reason(),
                autoApprove ? State.APPROVED : State.PENDING, autoApprove ? "auto-approve" : null,
                autoApprove ? now : null, now, now);
        entries.put(id, e);
        save();
        return e;
    }

    /** @return the id for a file: {@code mr-} + 8 hex, longer if another file already has that prefix */
    private String idFor(String sha512) {
        for (int len = 8; len < sha512.length(); len += 4) {
            String id = "mr-" + sha512.substring(0, len);
            Entry e = entries.get(id);
            if (e == null || e.sha512().equals(sha512)) return id;
        }
        return "mr-" + sha512;
    }

    public synchronized Optional<Entry> get(String id) {
        return Optional.ofNullable(entries.get(id == null ? null : id.toLowerCase(Locale.ROOT)));
    }

    /** @return {@code false} if the id is unknown */
    public synchronized boolean approve(String id, String by) {
        return decide(id, State.APPROVED, by);
    }

    /** @return {@code false} if the id is unknown */
    public synchronized boolean reject(String id, String by) {
        return decide(id, State.REJECTED, by);
    }

    private boolean decide(String id, State state, String by) {
        Entry e = entries.get(id == null ? null : id.toLowerCase(Locale.ROOT));
        if (e == null) return false;
        entries.put(e.id(), e.with(state, by, clock.get()));
        save();
        return true;
    }

    public synchronized List<Entry> list() {
        return List.copyOf(entries.values());
    }

    // -------------------------------------------------------------------------
    // JSON
    // -------------------------------------------------------------------------

    private void save() {
        JsonObject list = new JsonObject();
        for (Entry e : entries.values()) list.add(e.id(), toJson(e));
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("entries", list);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls()
                    .create().toJson(root), StandardCharsets.UTF_8);
            SafeZip.moveAtomic(tmp, file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("cannot write " + file.getFileName(), e);
        }
    }

    private static JsonObject toJson(Entry e) {
        JsonObject o = new JsonObject();
        o.addProperty("provider", e.provider());
        o.addProperty("datapack", e.datapack());
        o.addProperty("datapackSha1", e.datapackSha1());
        o.addProperty("projectId", e.projectId());
        o.addProperty("projectSlug", e.projectSlug());
        o.addProperty("versionId", e.versionId());
        o.addProperty("versionNumber", e.versionNumber());
        o.addProperty("fileName", e.fileName());
        o.addProperty("url", e.url());
        o.addProperty("size", e.size());
        o.addProperty("sha1", e.sha1());
        o.addProperty("sha512", e.sha512());
        o.addProperty("reason", e.reason());
        o.addProperty("state", e.state().name());
        o.addProperty("decidedBy", e.decidedBy());
        o.addProperty("decidedAt", e.decidedAt() == null ? null : e.decidedAt().toString());
        o.addProperty("firstSeen", e.firstSeen() == null ? null : e.firstSeen().toString());
        o.addProperty("lastSeen", e.lastSeen() == null ? null : e.lastSeen().toString());
        return o;
    }

    private static Entry fromJson(String id, JsonObject o) {
        String sha512 = s(o, "sha512");
        if (sha512 == null || !sha512.matches("[0-9a-fA-F]{128}")) throw new IllegalArgumentException(id + ": no valid sha512");
        return new Entry(id, s(o, "provider"), s(o, "datapack"), s(o, "datapackSha1"), s(o, "projectId"),
                s(o, "projectSlug"), s(o, "versionId"), s(o, "versionNumber"), s(o, "fileName"), s(o, "url"),
                o.has("size") && !o.get("size").isJsonNull() ? o.get("size").getAsLong() : -1,
                s(o, "sha1"), sha512.toLowerCase(Locale.ROOT), s(o, "reason"), State.valueOf(s(o, "state")),
                s(o, "decidedBy"), instant(o, "decidedAt"), instant(o, "firstSeen"), instant(o, "lastSeen"));
    }

    private static String s(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static Instant instant(JsonObject o, String key) {
        String v = s(o, key);
        return v == null ? null : Instant.parse(v);
    }

    /** @return entries in a given state, in insertion order */
    public synchronized List<Entry> inState(State state) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.values()) if (e.state() == state) out.add(e);
        return out;
    }
}
