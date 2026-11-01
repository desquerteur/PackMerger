package sh.pcx.packmerger.datapack.modrinth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Decides which resource-pack files belong to a datapack found on Modrinth. Only
 * what the author declared counts; nothing is matched by name.
 *
 * <ol>
 *   <li>files of the datapack's own version marked {@code required-resource-pack}
 *       (and {@code optional-resource-pack} with {@code include-optional});</li>
 *   <li>{@code required} (and, with {@code include-optional}, {@code optional})
 *       dependencies on a project of type {@code resourcepack}: the pinned version,
 *       or else the newest {@code release} (then {@code beta}, never {@code alpha})
 *       for the server's Minecraft version (exact, then {@code major.minor});</li>
 *   <li>dependencies with only a file name are reported as unsupported;
 *       {@code embedded} and {@code incompatible} ones are ignored.</li>
 * </ol>
 *
 * Problems are prefixed with their log category ({@code [missing-dependency]},
 * {@code [unsupported]}) so callers can log them as they are.
 */
public final class ModrinthResolver {

    public static final String MISSING = "[missing-dependency] ";
    public static final String UNSUPPORTED = "[unsupported] ";

    /**
     * @param datapackProjectId the datapack's Modrinth project, or {@code null} if not on Modrinth
     * @param candidates        resource packs to offer for approval
     * @param problems          declared resource packs that cannot be used, with their category prefix
     */
    public record Resolution(String datapackSha1, String datapackProjectId, String datapackVersionId,
                             List<Candidate> candidates, List<String> problems) {
        public Resolution {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        /** @return a resolution for a datapack Modrinth does not know */
        public static Resolution notOnModrinth(String sha1) {
            return new Resolution(sha1, null, null, List.of(), List.of());
        }

        public boolean onModrinth() {
            return datapackVersionId != null;
        }
    }

    /**
     * @param reason e.g. {@code "file_type required-resource-pack"} or {@code "dependency required"}
     */
    public record Candidate(String reason, String projectId, String projectSlug, String projectTitle,
                            String versionId, String versionNumber, ModrinthVersion.VersionFile file) {}

    private final ModrinthClient client;
    private final boolean includeOptional;

    public ModrinthResolver(ModrinthClient client, boolean includeOptional) {
        this.client = client;
        this.includeOptional = includeOptional;
    }

    /** Works out the resource packs of one datapack version found by its hash. */
    public Resolution resolve(String datapackSha1, ModrinthVersion dp, String minecraftVersion) throws ModrinthException {
        List<Candidate> candidates = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        // 1. Resource-pack files uploaded with the datapack's own version.
        ModrinthProject dpProject = null;
        for (ModrinthVersion.VersionFile f : dp.files()) {
            String type = f.fileType();
            boolean wanted = "required-resource-pack".equals(type) || (includeOptional && "optional-resource-pack".equals(type));
            if (!wanted) continue;
            if (!isZip(f)) {
                problems.add(UNSUPPORTED + "resource pack file \"" + f.filename() + "\" is not a zip");
                continue;
            }
            if (dpProject == null && dp.projectId() != null) dpProject = client.project(dp.projectId());
            candidates.add(new Candidate("file_type " + type, dp.projectId(),
                    dpProject == null ? null : dpProject.slug(), dpProject == null ? null : dpProject.title(),
                    dp.id(), dp.versionNumber(), f));
        }

        // 2. Declared dependencies on resource-pack projects.
        for (ModrinthVersion.Dependency d : dp.dependencies()) {
            String type = d.type() == null ? "" : d.type().toLowerCase(Locale.ROOT);
            boolean wanted = type.equals("required") || (includeOptional && type.equals("optional"));
            if (!wanted) continue;
            if (d.versionId() == null && d.projectId() == null) {
                if (d.fileName() != null) {
                    problems.add(UNSUPPORTED + "external dependency \"" + d.fileName() + "\" (not on Modrinth)");
                }
                continue;
            }
            ModrinthVersion version = null;
            String projectId = d.projectId();
            if (d.versionId() != null) {
                version = client.version(d.versionId());
                if (version == null) {
                    problems.add(MISSING + "dependency version " + d.versionId() + " no longer exists on Modrinth");
                    continue;
                }
                if (projectId == null) projectId = version.projectId();
            }
            ModrinthProject project = projectId == null ? null : client.project(projectId);
            if (project == null) {
                problems.add(MISSING + "dependency project " + projectId + " not found on Modrinth");
                continue;
            }
            if (!project.isResourcePack()) continue; // a required mod or datapack is not our business
            String name = project.slug() != null ? project.slug() : project.id();
            if (version == null) {
                version = pickVersion(client.projectVersions(project.id(), minecraftVersion, "minecraft"));
                String mm = majorMinor(minecraftVersion);
                if (version == null && mm != null && !mm.equals(minecraftVersion)) {
                    version = pickVersion(client.projectVersions(project.id(), mm, "minecraft"));
                }
                if (version == null) {
                    problems.add(MISSING + name + ": no release or beta version for " + minecraftVersion);
                    continue;
                }
            } else if (!supports(version, minecraftVersion)) {
                problems.add(MISSING + name + " " + version.versionNumber() + ": no version for " + minecraftVersion);
                continue;
            }
            ModrinthVersion.VersionFile file = version.primaryFile();
            if (file == null || !isZip(file)) {
                problems.add(UNSUPPORTED + name + " " + version.versionNumber() + ": primary file is not a zip");
                continue;
            }
            candidates.add(new Candidate("dependency " + type, project.id(), project.slug(), project.title(),
                    version.id(), version.versionNumber(), file));
        }
        return new Resolution(datapackSha1, dp.projectId(), dp.id(), candidates, problems);
    }

    /** @return the newest release, else the newest beta; never an alpha */
    static ModrinthVersion pickVersion(List<ModrinthVersion> versions) {
        Comparator<ModrinthVersion> newest = Comparator.comparing(
                (ModrinthVersion v) -> v.published() == null ? Instant.EPOCH : v.published()).reversed();
        for (String type : List.of("release", "beta")) {
            ModrinthVersion best = versions.stream().filter(v -> type.equalsIgnoreCase(v.versionType()))
                    .sorted(newest).findFirst().orElse(null);
            if (best != null) return best;
        }
        return null;
    }

    /** @return whether a version lists the server's version, exactly or by {@code major.minor} */
    static boolean supports(ModrinthVersion v, String mc) {
        if (mc == null) return false;
        if (v.gameVersions().contains(mc)) return true;
        String mm = majorMinor(mc);
        return mm != null && v.gameVersions().contains(mm);
    }

    /** @return {@code "26.2"} for {@code "26.2.1"}, the input for two-part versions, {@code null} for others */
    static String majorMinor(String mc) {
        if (mc == null) return null;
        String[] p = mc.split("\\.");
        return p.length >= 2 ? p[0] + "." + p[1] : null;
    }

    private static boolean isZip(ModrinthVersion.VersionFile f) {
        return f.filename() != null && f.filename().toLowerCase(Locale.ROOT).endsWith(".zip");
    }
}
