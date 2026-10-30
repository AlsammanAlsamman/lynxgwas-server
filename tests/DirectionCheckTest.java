import java.util.*;

/**
 * Tests for DirectionCheck: in a run of three datasets of one disease sharing twelve lead SNPs, a dataset reported
 * for the other allele (alleles swapped, effects negated) is flagged and the others are not; a dataset with the
 * same alleles listed in the other order but the same true effect is not flagged; MHC loci and loci that are not
 * genome-wide significant in both datasets are ignored; and too few shared loci never flag.
 */
public class DirectionCheckTest {
    static int failures = 0;

    public static void main(String[] args) {
        // c: alleles swapped AND effect negated relative to a and b, i.e. reported for the other allele as if it
        // were the effect allele (the error); d: alleles listed the other way round with the effect negated, i.e.
        // the same true effect written differently (not an error)
        MultiLocusResult r = run(12, true);
        DirectionCheck.Result dc = DirectionCheck.run(r);
        check("a not flagged", !flagged(dc, "a"));
        check("b not flagged", !flagged(dc, "b"));
        check("c flagged", flagged(dc, "c"));
        check("d (same effect, alleles listed the other way) not flagged", !flagged(dc, "d"));
        check("pair a-c: 0 of 13 agree (12 loci + the one weak only in b)", pair(dc, "a", "c")[0] == 13 && pair(dc, "a", "c")[1] == 0);
        check("pair a-d: 13 of 13 agree", pair(dc, "a", "d")[0] == 13 && pair(dc, "a", "d")[1] == 13);
        check("MHC locus and the locus weak in b ignored for a-b", pair(dc, "a", "b")[0] == 12);
        check("other disease never compared", pair(dc, "a", "z")[0] == -1);
        DirectionCheck.Result few = DirectionCheck.run(run(3, false));   // 3 loci x 3 partners = 9 comparisons, below 10
        check("too few shared loci: nothing flagged", !flagged(few, "c"));
        check("JSON lists the flag", DirectionCheck.toJson(dc).contains("\"id\":\"c\",\"disease\":\"ibd\",\"loci\":38,\"agree\":0,\"flagged\":true"));
        check("one split for ibd, c alone", dc.splits.size() == 1 && dc.splits.get(0).groupB.equals(Collections.singletonList("c")));

        // two swapped datasets agree with each other: a per-dataset score would miss them, the split does not;
        // two against two cannot say which side is swapped, so both are reported as ambiguous
        MultiLocusResult two = new MultiLocusResult();
        for (String id : new String[]{"p", "q", "s", "t"}) { MultiLocusResult.DatasetInfo d = new MultiLocusResult.DatasetInfo(id, id, "Beta"); d.diseaseName = "ibd"; two.datasets.add(d); }
        Random rnd = new Random(5);
        for (int k = 0; k < 12; k++) {
            MultiLocusResult.LocusRow row = new MultiLocusResult.LocusRow();
            row.index = k; row.chr = "2"; row.start = 1_000_000L * (k + 1); row.end = row.start + 1000;
            double beta = (rnd.nextBoolean() ? 1 : -1) * (0.1 + rnd.nextDouble());
            row.cells.put("p", cell(row.start, "A", "G", beta, 1e-10)); row.cells.put("q", cell(row.start, "A", "G", beta, 1e-10));
            row.cells.put("s", cell(row.start, "A", "G", -beta, 1e-10)); row.cells.put("t", cell(row.start, "A", "G", -beta, 1e-10));
            two.loci.add(row);
        }
        two.markMhc();
        DirectionCheck.Result dt = DirectionCheck.run(two);
        boolean amb = true;
        for (DirectionCheck.Dataset d : dt.datasets) amb &= d.ambiguous && !d.flagged;
        check("two against two: split found, reported as ambiguous", dt.splits.size() == 1 && dt.splits.get(0).ambiguous && amb);
        two.datasets.add(new MultiLocusResult.DatasetInfo("u", "u", "Beta")); two.datasets.get(4).diseaseName = "ibd";
        for (MultiLocusResult.LocusRow row : two.loci) row.cells.put("u", cell(row.start, "A", "G", row.cells.get("p").beta, 1e-10));
        DirectionCheck.Result d3 = DirectionCheck.run(two);
        check("three against two: the two are flagged", flagged(d3, "s") && flagged(d3, "t") && !flagged(d3, "p") && !flagged(d3, "u"));
        System.out.println(failures == 0 ? "PASS: all DirectionCheck tests passed" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }

    static boolean flagged(DirectionCheck.Result r, String id) {
        for (DirectionCheck.Dataset d : r.datasets) if (d.id.equals(id)) return d.flagged;
        return false;
    }

    static int[] pair(DirectionCheck.Result r, String a, String b) {
        for (DirectionCheck.Pair p : r.pairs) if ((p.a.equals(a) && p.b.equals(b)) || (p.a.equals(b) && p.b.equals(a))) return new int[]{p.loci, p.agree};
        return new int[]{-1, -1};
    }

    static MultiLocusResult run(int nLoci, boolean extras) {
        MultiLocusResult r = new MultiLocusResult();
        for (String id : new String[]{"a", "b", "c", "d", "z"}) {
            MultiLocusResult.DatasetInfo d = new MultiLocusResult.DatasetInfo(id, id, "Beta");
            d.diseaseName = id.equals("z") ? "t2d" : "ibd";
            r.datasets.add(d);
        }
        Random rnd = new Random(3);
        for (int k = 0; k < nLoci + (extras ? 2 : 0); k++) {
            MultiLocusResult.LocusRow row = new MultiLocusResult.LocusRow();
            row.index = k;
            boolean mhc = extras && k == nLoci;            // one MHC locus
            boolean weak = extras && k == nLoci + 1;       // one locus not significant in b
            row.chr = mhc ? "6" : "1"; row.start = mhc ? 30_000_000L : 1_000_000L * (k + 1); row.end = row.start + 50_000;
            double beta = (rnd.nextBoolean() ? 1 : -1) * (0.05 + rnd.nextDouble());
            row.cells.put("a", cell(row.start + 100, "A", "G", beta, 1e-12));
            row.cells.put("b", cell(row.start + 100, "A", "G", beta * 0.9, weak ? 1e-4 : 1e-10));
            row.cells.put("c", cell(row.start + 100, "G", "A", beta, 1e-11));     // swapped alleles, same sign: wrong
            row.cells.put("d", cell(row.start + 100, "G", "A", -beta, 1e-9));     // swapped alleles, negated: correct
            row.cells.put("z", cell(row.start + 100, "A", "G", -beta, 1e-12));    // another disease
            r.loci.add(row);
        }
        r.markMhc();
        return r;
    }

    static MultiLocusResult.DatasetLocusStat cell(long pos, String ea, String nea, double beta, double p) {
        MultiLocusResult.DatasetLocusStat s = new MultiLocusResult.DatasetLocusStat();
        s.bestPos = pos; s.bestChr = "1"; s.ea = ea; s.nea = nea; s.beta = beta; s.bestP = p; s.bestSnpId = "snp" + pos;
        return s;
    }

    static void check(String name, boolean ok) {
        if (ok) System.out.println("PASS: " + name);
        else { System.out.println("FAIL: " + name); failures++; }
    }
}
