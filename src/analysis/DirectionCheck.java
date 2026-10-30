import java.util.*;

/**
 * Effect-direction check across the datasets of one disease in a cross-dataset run.
 *
 * <p>At an aligned locus where two datasets of the same disease are both genome-wide significant and report the
 * same best SNP, their effects should point the same way once the alleles are aligned. A dataset whose effect and
 * other allele columns are swapped reverses every effect, which leaves single-dataset fine-mapping unchanged but
 * reverses effect directions, joint fine-mapping and the ranking's direction feature.
 *
 * <p>Two swapped datasets agree with each other, so a per-dataset agreement score can miss them. Instead each
 * disease's datasets are given a sign (+1 or -1, the first dataset fixed at +1) that maximizes the number of
 * agreeing comparisons; datasets are split only when this explains the data clearly better than "all the same"
 * (agreement at least {@link #MIN_AFTER} after, at most {@link #MAX_BEFORE} before, at least {@link #MIN_LOCI}
 * comparisons between the two groups). The smaller group is flagged as likely reversed; when both groups are the
 * same size the split is reported as ambiguous, to be settled with a known variant. Loci in the MHC are left out.
 */
public final class DirectionCheck {
    static final int MIN_LOCI = 10;
    static final double MIN_AFTER = 0.9, MAX_BEFORE = 0.7;
    static final double GWS = 5e-8;
    static final int MAX_EXACT = 16;   // exhaustive search up to this many datasets per disease

    private DirectionCheck() {}

    public static class Pair { public String a, b, disease; public int loci, agree; }
    public static class Dataset { public String id, disease; public int loci, agree; public boolean flagged, ambiguous; }
    public static class Split {
        public String disease; public List<String> groupA = new ArrayList<>(), groupB = new ArrayList<>();
        public int comparisons, agreeBefore, agreeAfter, crossLoci; public boolean ambiguous;
    }
    public static class Result {
        public List<Pair> pairs = new ArrayList<>(); public List<Dataset> datasets = new ArrayList<>(); public List<Split> splits = new ArrayList<>();
    }

    public static Result run(MultiLocusResult mlr) {
        Result r = new Result();
        Map<String, String> dis = new LinkedHashMap<>();
        for (MultiLocusResult.DatasetInfo d : mlr.datasets) dis.put(d.id, GeneConstellationBuilder.diseaseGroupOf(d.id, d.diseaseName));
        List<String> ids = new ArrayList<>(dis.keySet());
        Map<String, Dataset> per = new LinkedHashMap<>();
        for (String id : ids) { Dataset d = new Dataset(); d.id = id; d.disease = dis.get(id); per.put(id, d); }
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                String a = ids.get(i), b = ids.get(j);
                if (!dis.get(a).equals(dis.get(b))) continue;
                Pair p = new Pair(); p.a = a; p.b = b; p.disease = dis.get(a);
                for (MultiLocusResult.LocusRow row : mlr.loci) {
                    if (row.mhc) continue;
                    Integer s = agreement(row.cells.get(a), row.cells.get(b));
                    if (s == null) continue;
                    p.loci++;
                    if (s > 0) p.agree++;
                }
                if (p.loci == 0) continue;
                r.pairs.add(p);
                for (String x : new String[]{a, b}) { per.get(x).loci += p.loci; per.get(x).agree += p.agree; }
            }
        }
        for (String disease : new LinkedHashSet<>(dis.values())) {
            List<String> members = new ArrayList<>();
            for (String id : ids) if (dis.get(id).equals(disease) && per.get(id).loci > 0) members.add(id);
            if (members.size() < 3 || members.size() > MAX_EXACT) continue;
            List<Pair> dp = new ArrayList<>();
            for (Pair p : r.pairs) if (p.disease.equals(disease)) dp.add(p);
            int total = 0, before = 0;
            for (Pair p : dp) { total += p.loci; before += p.agree; }
            int bestMask = 0, best = before;
            for (int mask = 1; mask < (1 << (members.size() - 1)); mask++) {   // member 0 stays in group A
                int score = 0;
                for (Pair p : dp) {
                    boolean flipA = flipped(mask, members.indexOf(p.a)), flipB = flipped(mask, members.indexOf(p.b));
                    score += flipA == flipB ? p.agree : p.loci - p.agree;
                }
                if (score > best) { best = score; bestMask = mask; }
            }
            if (bestMask == 0 || total == 0) continue;
            Split s = new Split(); s.disease = disease; s.comparisons = total; s.agreeBefore = before; s.agreeAfter = best;
            for (int k = 0; k < members.size(); k++) (flipped(bestMask, k) ? s.groupB : s.groupA).add(members.get(k));
            for (Pair p : dp) if (s.groupA.contains(p.a) != s.groupA.contains(p.b)) s.crossLoci += p.loci;
            if ((double) best / total < MIN_AFTER || (double) before / total > MAX_BEFORE || s.crossLoci < MIN_LOCI) continue;
            s.ambiguous = s.groupA.size() == s.groupB.size();
            List<String> smaller = s.groupA.size() < s.groupB.size() ? s.groupA : s.groupB;
            for (String id : s.ambiguous ? concat(s.groupA, s.groupB) : smaller) {
                per.get(id).flagged = !s.ambiguous; per.get(id).ambiguous = s.ambiguous;
            }
            r.splits.add(s);
        }
        for (Dataset d : per.values()) if (d.loci > 0) r.datasets.add(d);
        return r;
    }

    private static boolean flipped(int mask, int k) { return k > 0 && ((mask >> (k - 1)) & 1) == 1; }

    private static List<String> concat(List<String> a, List<String> b) { List<String> c = new ArrayList<>(a); c.addAll(b); return c; }

    /** +1 same direction, -1 opposite, null when the two cells are not comparable. */
    static Integer agreement(MultiLocusResult.DatasetLocusStat x, MultiLocusResult.DatasetLocusStat y) {
        if (x == null || y == null || !(x.bestP < GWS) || !(y.bestP < GWS) || x.bestPos <= 0 || x.bestPos != y.bestPos) return null;
        double ex = effect(x), ey = effect(y);
        if (Double.isNaN(ex) || Double.isNaN(ey) || ex == 0 || ey == 0) return null;
        String xa = up(x.ea), xn = up(x.nea), ya = up(y.ea), yn = up(y.nea);
        int sign;
        if (xa.equals(ya) && xn.equals(yn)) sign = 1;
        else if (xa.equals(yn) && xn.equals(ya)) sign = -1;
        else return null;
        return Math.signum(ex) == sign * Math.signum(ey) ? 1 : -1;
    }

    private static double effect(MultiLocusResult.DatasetLocusStat s) {
        if (!Double.isNaN(s.beta)) return s.beta;
        if (!Double.isNaN(s.or) && s.or > 0) return Math.log(s.or);
        return Double.NaN;
    }

    private static String up(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT); }

    public static String toJson(Result r) {
        StringBuilder j = new StringBuilder("{\"min_loci\":").append(MIN_LOCI).append(",\"datasets\":[");
        for (int i = 0; i < r.datasets.size(); i++) {
            Dataset d = r.datasets.get(i);
            if (i > 0) j.append(',');
            j.append("{\"id\":\"").append(esc(d.id)).append("\",\"disease\":\"").append(esc(d.disease)).append("\",\"loci\":").append(d.loci)
             .append(",\"agree\":").append(d.agree).append(",\"flagged\":").append(d.flagged).append(",\"ambiguous\":").append(d.ambiguous).append('}');
        }
        j.append("],\"splits\":[");
        for (int i = 0; i < r.splits.size(); i++) {
            Split s = r.splits.get(i);
            if (i > 0) j.append(',');
            j.append("{\"disease\":\"").append(esc(s.disease)).append("\",\"group_a\":").append(strs(s.groupA)).append(",\"group_b\":").append(strs(s.groupB))
             .append(",\"comparisons\":").append(s.comparisons).append(",\"agree_before\":").append(s.agreeBefore).append(",\"agree_after\":").append(s.agreeAfter)
             .append(",\"cross_loci\":").append(s.crossLoci).append(",\"ambiguous\":").append(s.ambiguous).append('}');
        }
        j.append("],\"pairs\":[");
        for (int i = 0; i < r.pairs.size(); i++) {
            Pair p = r.pairs.get(i);
            if (i > 0) j.append(',');
            j.append("{\"a\":\"").append(esc(p.a)).append("\",\"b\":\"").append(esc(p.b)).append("\",\"disease\":\"").append(esc(p.disease))
             .append("\",\"loci\":").append(p.loci).append(",\"agree\":").append(p.agree).append('}');
        }
        return j.append("]}").toString();
    }

    private static String strs(List<String> v) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < v.size(); i++) { if (i > 0) b.append(','); b.append('"').append(esc(v.get(i))).append('"'); }
        return b.append(']').toString();
    }

    private static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
