package sh.pcx.packmerger.commands;

import sh.pcx.packmerger.merge.MergeProvenance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure helper behind {@code /pm conflicts}: the files of the last merge where packs
 * really disagree ({@link MergeProvenance.FileRecord#isConflict()}), with datapack
 * aliases tagged {@code [dp]}. No Bukkit or Adventure types, so it is unit-tested
 * without a server; the command formats the result through messages_en.yml.
 */
public final class ConflictRenderer {

    private ConflictRenderer() {}

    /**
     * One conflicting file.
     *
     * @param path   the pack-relative path
     * @param winner the pack whose version is in the merged pack (tagged)
     * @param losers the other contributors, highest priority first (tagged)
     * @param note   the first strategy warning, or {@code null}
     */
    public record Conflict(String path, String winner, List<String> losers, String note) {}

    /**
     * @param prov            the last merge's provenance (may be {@code null})
     * @param datapackAliases aliases to tag with {@code [dp]}
     * @param packFilter      only conflicts this pack took part in, or {@code null} for all
     * @return the conflicts, sorted by path
     */
    public static List<Conflict> conflicts(MergeProvenance prov, Set<String> datapackAliases, String packFilter) {
        List<Conflict> out = new ArrayList<>();
        if (prov == null) return out;
        List<MergeProvenance.FileRecord> records = new ArrayList<>(prov.files().values());
        records.sort(Comparator.comparing(MergeProvenance.FileRecord::path));
        for (MergeProvenance.FileRecord rec : records) {
            if (!rec.isConflict()) continue;
            if (packFilter != null && !rec.contributors().contains(packFilter)) continue;
            List<String> losers = new ArrayList<>();
            List<String> contributors = rec.contributors();
            for (int i = contributors.size() - 1; i >= 0; i--) {
                String c = contributors.get(i);
                if (c.equals(rec.winner())) continue;
                String tagged = tag(c, datapackAliases);
                if (!losers.contains(tagged)) losers.add(tagged);
            }
            String note = rec.warned() && !rec.notes().isEmpty() ? rec.notes().get(0) : null;
            out.add(new Conflict(rec.path(), tag(rec.winner(), datapackAliases), losers, note));
        }
        return out;
    }

    /**
     * The one-line log summary after a merge, only when a datapack alias took part in a
     * conflict, e.g. {@code "4 file(s) differ between packs; dp-terralith lost 3"}.
     *
     * @return the summary, or {@code null} when no datapack alias is involved
     */
    public static String datapackSummary(MergeProvenance prov, Set<String> datapackAliases) {
        if (prov == null || datapackAliases.isEmpty()) return null;
        int total = 0;
        boolean involved = false;
        Map<String, Integer> lost = new LinkedHashMap<>();
        for (MergeProvenance.FileRecord rec : prov.files().values()) {
            if (!rec.isConflict()) continue;
            total++;
            for (String c : rec.contributors().stream().distinct().toList()) {
                if (!datapackAliases.contains(c)) continue;
                involved = true;
                if (!c.equals(rec.winner())) lost.merge(c, 1, Integer::sum);
            }
        }
        if (!involved) return null;
        StringBuilder sb = new StringBuilder(total + " file(s) differ between packs");
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(lost.entrySet());
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        for (int i = 0; i < entries.size() && i < 3; i++) {
            sb.append(i == 0 ? "; " : ", ").append(entries.get(i).getKey()).append(" lost ").append(entries.get(i).getValue());
        }
        if (entries.size() > 3) sb.append(", …");
        return sb.append(" (see /pm conflicts)").toString();
    }

    private static String tag(String pack, Set<String> datapackAliases) {
        return datapackAliases.contains(pack) ? "[dp] " + pack : pack;
    }
}
