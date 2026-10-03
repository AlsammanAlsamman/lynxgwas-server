import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Causal-SNP ranking: the per-SNP features LYNXgwas computes for a locus and the logistic model that
 * combines them into one score.
 *
 * The same feature definitions feed both training (offline, against an independent fine-mapping truth
 * set) and scoring in the app, so the model sees exactly what it was trained on. The model is a plain
 * standardised logistic regression stored as JSON (feature means, SDs and coefficients), so every score
 * can be broken down into per-feature contributions.
 *
 * Feature sources: the locus's latest SuSiE / ABF / COJO runs, a joint (mvSuSiE) run that pooled this
 * dataset's locus, the locus GWAS itself, CADD v1.6 PHRED (GRCh37, tabix-indexed), 1000 Genomes EUR LD
 * scores (HapMap3, nearest SNP within 100 kb), Roadmap histone peaks in the disease's reference tissue,
 * the gene annotation (coding / UTR / distance to the nearest transcription start) and the locus's MAGMA
 * gene test.
 */
public final class SnpRankModel {

    /** Feature order of the model and of result.tsv. */
    public static final String[] FEATURES = {
        "susie_pip", "in_susie_cs", "abf_pip", "cojo_selected", "logp_rel",
        "joint_pip", "has_joint", "cadd_phred", "ld_score_log",
        "h3k27ac", "h3k4me1", "h3k4me3", "coding", "utr", "log_tss_dist", "magma_gene_rel"
    };

    /** Short human-readable label per feature, for the viewer's contribution breakdown. */
    public static final Map<String, String> LABEL = new LinkedHashMap<>();
    static {
        String[][] l = {{"susie_pip", "SuSiE PIP"}, {"in_susie_cs", "in SuSiE set"}, {"abf_pip", "ABF PIP"},
            {"cojo_selected", "COJO signal"}, {"logp_rel", "relative significance"}, {"joint_pip", "joint (mvSuSiE) PIP"},
            {"has_joint", "joint run available"}, {"cadd_phred", "CADD"}, {"ld_score_log", "LD score"},
            {"h3k27ac", "H3K27ac peak"}, {"h3k4me1", "H3K4me1 peak"}, {"h3k4me3", "H3K4me3 peak"},
            {"coding", "coding"}, {"utr", "UTR"}, {"log_tss_dist", "distance to TSS"}, {"magma_gene_rel", "MAGMA gene"}};
        for (String[] p : l) LABEL.put(p[0], p[1]);
    }

    private SnpRankModel() {}

    // ── Model ─────────────────────────────────────────────────────────────

    public static final class Model {
        public String version = "", trainedOn = "", type = "logistic";
        public double intercept;
        public final Map<String, double[]> coef = new LinkedHashMap<>();   // feature -> {mean, sd, coef, logFloor or NaN}

        /** Conditional logit: scores are a softmax over the SNPs of one locus (probability each is its causal SNP). */
        public boolean conditional() { return "conditional_logit".equals(type); }

        private static double value(Map<String, Double> x, String name, double[] m) {
            double v = x.getOrDefault(name, 0.0);
            return Double.isNaN(m[3]) ? v : Math.log10(Math.max(v, m[3]));
        }

        public double linear(Map<String, Double> x) {
            double s = intercept;
            for (Map.Entry<String, double[]> e : coef.entrySet()) {
                double[] m = e.getValue();
                s += m[2] * (value(x, e.getKey(), m) - m[0]) / (m[1] > 0 ? m[1] : 1);
            }
            return s;
        }

        /** Per-feature contribution to the linear predictor, relative to an average SNP of the training data. */
        public Map<String, Double> contributions(Map<String, Double> x) {
            Map<String, Double> c = new LinkedHashMap<>();
            for (Map.Entry<String, double[]> e : coef.entrySet()) {
                double[] m = e.getValue();
                c.put(e.getKey(), m[2] * (value(x, e.getKey(), m) - m[0]) / (m[1] > 0 ? m[1] : 1));
            }
            return c;
        }
    }

    /** Loads the model JSON written by the training script, or null if there is none yet. */
    public static Model load(File f) throws IOException {
        if (f == null || !f.isFile()) return null;
        Map<String, Object> o = MiniJson.asObject(MiniJson.parse(new String(Files.readAllBytes(f.toPath()), "UTF-8")));
        Model m = new Model();
        m.version = MiniJson.getStr(o, "version", "");
        m.trainedOn = MiniJson.getStr(o, "trained_on", "");
        m.type = MiniJson.getStr(o, "type", "logistic");
        m.intercept = ((Number) o.get("intercept")).doubleValue();
        for (Object fo : MiniJson.asArray(o.get("features"))) {
            Map<String, Object> fm = MiniJson.asObject(fo);
            boolean log = "log10".equals(MiniJson.getStr(fm, "transform", ""));
            double floor = log && fm.get("floor") instanceof Number ? ((Number) fm.get("floor")).doubleValue() : 1e-4;
            m.coef.put(MiniJson.getStr(fm, "name", ""), new double[]{
                ((Number) fm.get("mean")).doubleValue(), ((Number) fm.get("sd")).doubleValue(), ((Number) fm.get("coef")).doubleValue(),
                log ? floor : Double.NaN});
        }
        return m;
    }

    public static double sigmoid(double z) { return 1 / (1 + Math.exp(-z)); }

    // ── LD scores: nearest HapMap3 SNP within 100 kb ──────────────────────

    private static final Map<String, long[]> LD_POS = new HashMap<>();
    private static final Map<String, double[]> LD_L2 = new HashMap<>();

    /** log(1 + LD score of the nearest HapMap3 SNP within 100 kb), or NaN if none. */
    public static synchronized double ldScoreLog(File ldDir, String chr, long pos) throws IOException {
        String c = chr.replaceFirst("^chr", "");
        if (!LD_POS.containsKey(c)) {
            List<long[]> rows = new ArrayList<>();
            File f = new File(ldDir, c + ".l2.ldscore.gz");
            if (f.isFile()) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(f)), "UTF-8"))) {
                    String line = br.readLine();
                    List<String> h = Arrays.asList(line.split("\t"));
                    int iBp = h.indexOf("BP"), iL2 = h.indexOf("L2");
                    while ((line = br.readLine()) != null) {
                        String[] p = line.split("\t");
                        rows.add(new long[]{Long.parseLong(p[iBp]), Double.doubleToLongBits(Double.parseDouble(p[iL2]))});
                    }
                }
            }
            rows.sort(Comparator.comparingLong(r -> r[0]));
            long[] ps = new long[rows.size()];
            double[] ls = new double[rows.size()];
            for (int i = 0; i < ps.length; i++) { ps[i] = rows.get(i)[0]; ls[i] = Double.longBitsToDouble(rows.get(i)[1]); }
            LD_POS.put(c, ps);
            LD_L2.put(c, ls);
        }
        long[] ps = LD_POS.get(c);
        if (ps.length == 0) return Double.NaN;
        int i = Arrays.binarySearch(ps, pos);
        if (i < 0) i = -i - 1;
        int best = -1;
        long bestD = Long.MAX_VALUE;
        for (int j = Math.max(0, i - 1); j <= Math.min(ps.length - 1, i); j++) {
            long d = Math.abs(ps[j] - pos);
            if (d < bestD) { bestD = d; best = j; }
        }
        return best >= 0 && bestD <= 100_000 ? Math.log1p(Math.max(0, LD_L2.get(c)[best])) : Double.NaN;
    }

    // ── CADD PHRED (tabix) ────────────────────────────────────────────────

    /** chr:pos:allele -> PHRED for every scored SNV in [start, end]; alleles are the CADD alt allele. */
    public static Map<String, Double> caddRegion(File caddFile, String chr, long start, long end) throws IOException {
        Map<String, Double> out = new HashMap<>();
        if (caddFile == null || !caddFile.isFile() || !new File(caddFile.getPath() + ".tbi").isFile()) return out;
        String c = chr.replaceFirst("^chr", "");
        htsjdk.tribble.readers.TabixReader tr = new htsjdk.tribble.readers.TabixReader(caddFile.getAbsolutePath());
        try {
            htsjdk.tribble.readers.TabixReader.Iterator it = tr.query(c + ":" + Math.max(1, start) + "-" + end);
            String line;
            while (it != null && (line = it.next()) != null) {
                String[] p = line.split("\t");
                if (p.length < 6) continue;
                out.put(c + ":" + p[1] + ":" + p[2] + ":" + p[3], Double.parseDouble(p[5]));
            }
        } finally {
            tr.close();
        }
        return out;
    }

    /** PHRED for a SNP given its two alleles (either may be CADD's ref); NaN if not scored. */
    public static double caddFor(Map<String, Double> region, String chr, long pos, String a1, String a2) {
        String c = chr.replaceFirst("^chr", "") + ":" + pos + ":";
        Double v = region.get(c + a2 + ":" + a1);
        if (v == null) v = region.get(c + a1 + ":" + a2);
        return v == null ? Double.NaN : v;
    }
}
