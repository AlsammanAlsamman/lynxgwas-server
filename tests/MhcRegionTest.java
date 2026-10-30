import java.util.*;

/**
 * Tests for the shared MHC window (MhcRegion) and its use in the cross-dataset views.
 *
 *   1. Window edges on GRCh37 and GRCh38 (the GRCh38 ends are the liftOver of the GRCh37 ones),
 *      "chr" prefix, other chromosomes.
 *   2. A cross-dataset run whose only multi-disease locus is a PBX2-like locus at chr6:32.15 Mb:
 *      the locus is flagged, and the Gene Constellation gives it no node and no edge but lists it,
 *      the region view leaves it out, and the Serpent Plot draws it (flagged) while its PERMANOVA
 *      equals the one computed on the same run without that locus.
 *   3. The flag is recomputed when a saved run is read back, so old runs follow the current window.
 */
public class MhcRegionTest {

    static int failures = 0;

    public static void main(String[] args) {
        // 1. window edges
        check("GRCh37 start inside", MhcRegion.contains("6", 25_000_000L, "GRCh37"));
        check("GRCh37 end inside", MhcRegion.contains("6", 34_000_000L, "GRCh37"));
        check("GRCh37 just before", !MhcRegion.contains("6", 24_999_999L, "GRCh37"));
        check("GRCh37 just after", !MhcRegion.contains("6", 34_000_001L, "GRCh37"));
        check("GRCh38 end inside", MhcRegion.contains("chr6", 34_032_223L, "GRCh38"));
        check("GRCh38 just after", !MhcRegion.contains("chr6", 34_032_224L, "GRCh38"));
        check("GRCh38 start", MhcRegion.contains("6", 24_999_772L, "GRCh38") && !MhcRegion.contains("6", 24_999_771L, "GRCh38"));
        check("PBX2 (chr6:32,152,510) inside", MhcRegion.overlaps("chr6", 32_152_510L, 32_157_979L, "GRCh37"));
        check("edge locus 26.4 Mb inside (old window missed it)", MhcRegion.contains("6", 26_396_857L, "GRCh37"));
        check("chr16 never", !MhcRegion.overlaps("16", 25_000_000L, 34_000_000L, "GRCh37"));
        check("null build = GRCh37", MhcRegion.contains("6", 34_000_000L, null));
        check("overlap across the start", MhcRegion.overlaps("6", 24_000_000L, 25_000_000L, "GRCh37"));
        check("label", MhcRegion.label("GRCh37").equals("chr6:25,000,000-34,000,000 (GRCh37)"));

        // 2. cross-dataset views
        MultiLocusResult withMhc = corpus(true), withoutMhc = corpus(false);
        int flagged = 0;
        for (MultiLocusResult.LocusRow l : withMhc.loci) if (l.mhc) flagged++;
        check("exactly one locus flagged", flagged == 1);
        check("lociOutsideMhc drops it", withMhc.lociOutsideMhc().size() == withMhc.loci.size() - 1);

        GeneConstellationResult g = GeneConstellationBuilder.build(withMhc, 5e-8);
        boolean pbx2Node = false;
        for (GeneConstellationResult.GeneEntry e : g.genes) if (e.gene.equals("PBX2")) pbx2Node = true;
        boolean pbx2Edge = false;
        for (GeneConstellationResult.GeneEdge e : g.edges) if (e.geneA.equals("PBX2") || e.geneB.equals("PBX2")) pbx2Edge = true;
        check("constellation: no PBX2 node", !pbx2Node);
        check("constellation: no PBX2 edge", !pbx2Edge);
        check("constellation: PBX2 listed as MHC", g.mhcGenes.contains("PBX2") && g.mhcLociCount == 1);
        check("constellation: gene count equals the run without it",
            g.genes.size() == GeneConstellationBuilder.build(withoutMhc, 5e-8).genes.size());
        String gj = g.toJson();
        check("constellation JSON lists mhc_genes", gj.contains("\"mhc_genes\":[\"PBX2\"]") && gj.contains("\"mhc_loci_count\":1"));

        boolean pbx2Region = false;
        for (GeneConstellationResult.RegionEntry r : RegionConstellationBuilder.build(withMhc, 5e-8))
            if ("PBX2".equals(r.nearestGene)) pbx2Region = true;
        check("region view: no PBX2 region", !pbx2Region);

        SerpentPlotBuilder.Result s = SerpentPlotBuilder.build(withMhc, 999);
        SerpentPlotBuilder.Result s0 = SerpentPlotBuilder.build(withoutMhc, 999);
        check("serpent: MHC locus still drawn", s.loci.size() == s0.loci.size() + 1 && s.nMhcLoci == 1);
        boolean drawnFlagged = false;
        for (SerpentPlotBuilder.Locus L : s.loci) if (L.gene.equals("PBX2")) drawnFlagged = L.mhc;
        check("serpent: drawn locus is flagged", drawnFlagged);
        close("serpent: Disease PERMANOVA R2 unchanged by the MHC locus", analysis(s, "Disease").r2, analysis(s0, "Disease").r2, 1e-12);
        close("serpent: Disease pseudo-F unchanged", analysis(s, "Disease").pseudoF, analysis(s0, "Disease").pseudoF, 1e-9);
        String sj = SerpentPlotBuilder.toJson(s);
        check("serpent JSON carries the flag", sj.contains("\"mhc\":true") && sj.contains("\"n_mhc_loci\":1"));

        // 3. a saved run is re-flagged on reading
        MultiLocusResult back = MultiLocusResult.fromJson(withMhc.toJson());
        int flaggedBack = 0;
        for (MultiLocusResult.LocusRow l : back.loci) if (l.mhc) flaggedBack++;
        check("persisted run re-flags the MHC locus", flaggedBack == 1 && "GRCh37".equals(back.genomeBuild));

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }

    static SerpentPlotBuilder.CategoryAnalysis analysis(SerpentPlotBuilder.Result r, String cat) {
        for (SerpentPlotBuilder.CategoryAnalysis a : r.analyses) if (a.category.equals(cat)) return a;
        throw new IllegalStateException("no category " + cat);
    }

    /** Six datasets of two diseases. Each disease has its own loci; the PBX2 locus is significant in all six. */
    static MultiLocusResult corpus(boolean includeMhc) {
        MultiLocusResult r = new MultiLocusResult();
        String[] ids = {"a1", "a2", "a3", "b1", "b2", "b3"};
        for (String id : ids) {
            MultiLocusResult.DatasetInfo d = new MultiLocusResult.DatasetInfo(id, id, "Beta");
            d.diseaseName = id.startsWith("a") ? "alpha" : "beta";
            r.datasets.add(d);
        }
        int idx = 0;
        for (int k = 0; k < 8; k++) {
            boolean alpha = k % 2 == 0;
            r.loci.add(row(idx++, "1", 10_000_000L + k * 5_000_000L, "G" + k, ids, id -> id.startsWith(alpha ? "a" : "b") ? 1e-12 : 0.3));
        }
        if (includeMhc) r.loci.add(row(idx++, "6", 32_150_000L, "PBX2", ids, id -> 1e-20));
        r.loci.add(row(idx, "9", 5_000_000L, "SHARED", ids, id -> id.endsWith("1") ? 1e-9 : 0.2));
        r.markMhc();
        return r;
    }

    interface PFn { double p(String id); }

    static MultiLocusResult.LocusRow row(int index, String chr, long start, String gene, String[] ids, PFn pf) {
        MultiLocusResult.LocusRow row = new MultiLocusResult.LocusRow();
        row.index = index; row.chr = chr; row.start = start; row.end = start + 20_000; row.sizeBp = 20_001;
        row.nearestGene = gene;
        double best = Double.POSITIVE_INFINITY;
        for (String id : ids) {
            MultiLocusResult.DatasetLocusStat s = new MultiLocusResult.DatasetLocusStat();
            s.bestP = pf.p(id); s.bestPos = start + 10_000; s.bestSnpId = gene + "_snp"; s.beta = 0.1;
            row.cells.put(id, s);
            if (s.bestP < best) { best = s.bestP; row.overallBestDatasetId = id; }
        }
        row.overallBestP = best;
        return row;
    }

    static void check(String name, boolean ok) {
        if (ok) System.out.println("PASS: " + name);
        else { System.out.println("FAIL: " + name); failures++; }
    }

    static void close(String name, double got, double want, double tol) {
        boolean ok = !Double.isNaN(got) && Math.abs(got - want) <= tol;
        if (ok) System.out.println("PASS: " + name + " (" + got + ")");
        else { System.out.println("FAIL: " + name + " got " + got + " want " + want); failures++; }
    }
}
