import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Genome-wide SNP-heritability of one project by LD score regression (see {@link LdscRegression}).
 *
 * Streams the project's full GWAS summary-statistics file once, matches SNPs to the European LD scores
 * (eur_w_ld_chr: 1000 Genomes, summed over HapMap3 SNPs; the HLA-removed chr6 file) by chromosome and
 * GRCh37 position, and writes the result to {@code <project>/heritability/ldsc.json}. Chi-square comes from
 * beta/SE when the file has them (log(OR) for odds ratios), otherwise from the p-value. Sample size is the
 * per-SNP N column when present, else the configured sample size.
 */
public final class GenomeHeritability {

    /** Minimum matched SNPs for an estimate; files of top hits only cannot support LD score regression. */
    public static final int MIN_SNPS = 200_000;

    private GenomeHeritability() {}

    /** LD score directory: env LYNXGWAS_LDSC, else appRoot/resources/ldsc/eur_w_ld_chr. */
    public static File ldscDir() {
        String env = System.getenv("LYNXGWAS_LDSC");
        if (env != null && !env.isEmpty()) return new File(env);
        return new File(SusieAdapter.appRoot(), "resources/ldsc/eur_w_ld_chr");
    }

    public static File resultFile(Config cfg) { return new File(cfg.outputDir, "heritability/ldsc.json"); }

    /** Runs LDSC for one project and writes ldsc.json; returns the JSON written. */
    public static String run(Config cfg, File ldDir, ProgressListener progress) throws IOException {
        if (!new File(ldDir, "1.l2.ldscore.gz").isFile())
            throw new IOException("LD scores not found in " + ldDir.getAbsolutePath()
                + " (expected eur_w_ld_chr: 1.l2.ldscore.gz ... 22.l2.ldscore.gz)");
        if (cfg.genomeBuild != null && !cfg.genomeBuild.isEmpty() && !cfg.genomeBuild.toUpperCase().startsWith("GRCH37")
                && !cfg.genomeBuild.toLowerCase().startsWith("hg19"))
            throw new IOException("LD scores are on GRCh37; this project is " + cfg.genomeBuild);

        // ── LD scores, keyed by chr * 1e10 + pos, in genomic order ──
        progress.update("Reading LD scores", 0);
        Map<Long, Integer> idx = new HashMap<>(1 << 21);
        List<double[]> ref = new ArrayList<>(1_200_000);    // {key, l2}
        double m = 0;
        for (int c = 1; c <= 22; c++) {
            m += Double.parseDouble(new String(Files.readAllBytes(new File(ldDir, c + ".l2.M_5_50").toPath())).trim());
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(new FileInputStream(new File(ldDir, c + ".l2.ldscore.gz"))), "UTF-8"))) {
                String line = br.readLine();
                List<String> h = Arrays.asList(line.split("\t"));
                int iBp = h.indexOf("BP"), iL2 = h.indexOf("L2");
                while ((line = br.readLine()) != null) {
                    String[] f = line.split("\t");
                    long key = c * 10_000_000_000L + Long.parseLong(f[iBp]);
                    if (idx.containsKey(key)) continue;
                    idx.put(key, ref.size());
                    ref.add(new double[]{key, Double.parseDouble(f[iL2])});
                }
            }
        }

        // ── GWAS: chi2 and N per matched SNP ──
        File gwas = new File(cfg.gwasFile);
        if (!gwas.isFile() && new File(cfg.gwasFile + ".gz").isFile()) gwas = new File(cfg.gwasFile + ".gz");
        if (!gwas.isFile()) throw new IOException("GWAS file not found: " + cfg.gwasFile);
        double[] chi2 = new double[ref.size()], nArr = new double[ref.size()];
        Arrays.fill(chi2, Double.NaN);
        long rows = 0, matched = 0, fromP = 0;
        boolean or = "OR".equalsIgnoreCase(cfg.effectType);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(gwas.getName().endsWith(".gz")
                ? new GZIPInputStream(new FileInputStream(gwas), 1 << 16) : new FileInputStream(gwas), "UTF-8"), 1 << 20)) {
            String header = br.readLine();
            if (header == null) throw new IOException("GWAS file is empty");
            String sep = header.contains("\t") ? "\t" : "\\s+";
            List<String> h = Arrays.asList(header.trim().split(sep));
            int iChr = h.indexOf(cfg.colChr), iPos = h.indexOf(cfg.colPos), iP = h.indexOf(cfg.colPvalue);
            int iBeta = cfg.colBeta.isEmpty() ? -1 : h.indexOf(cfg.colBeta);
            int iOr = cfg.colOr.isEmpty() ? -1 : h.indexOf(cfg.colOr);
            int iSe = cfg.colSe.isEmpty() ? -1 : h.indexOf(cfg.colSe);
            int iN = cfg.colN.isEmpty() ? -1 : h.indexOf(cfg.colN);
            if (iChr < 0 || iPos < 0) throw new IOException("Chromosome/position columns not found in the GWAS file header");
            if (iP < 0 && (iSe < 0 || (iBeta < 0 && iOr < 0))) throw new IOException("Need a p-value column or beta/OR and SE");
            String line;
            while ((line = br.readLine()) != null) {
                if (++rows % 2_000_000 == 0) progress.update("Reading GWAS: " + (rows / 1_000_000) + "M rows", 0);
                String[] f = sep.equals("\t") ? line.split("\t", -1) : line.trim().split(sep);
                if (f.length <= Math.max(iChr, iPos)) continue;
                String cs = f[iChr].trim();
                if (cs.regionMatches(true, 0, "chr", 0, 3)) cs = cs.substring(3);
                int c;
                long pos;
                try { c = Integer.parseInt(cs); pos = Long.parseLong(f[iPos].trim()); } catch (NumberFormatException e) { continue; }
                if (c < 1 || c > 22) continue;
                Integer j = idx.get(c * 10_000_000_000L + pos);
                if (j == null || !Double.isNaN(chi2[j])) continue;
                double x = Double.NaN;
                if (iSe >= 0 && (iBeta >= 0 || iOr >= 0)) {
                    double b = parse(f, iBeta >= 0 ? iBeta : iOr), se = parse(f, iSe);
                    if (iBeta < 0 || (or && iOr < 0)) b = b > 0 ? Math.log(b) : Double.NaN;
                    if (se > 0 && !Double.isNaN(b)) x = (b / se) * (b / se);
                }
                if (Double.isNaN(x) && iP >= 0) { x = LdscRegression.chi2FromP(parse(f, iP)); fromP++; }
                if (Double.isNaN(x)) continue;
                double n = iN >= 0 ? parse(f, iN) : Double.NaN;
                if (!(n > 0)) n = cfg.sampleN;
                if (!(n > 0)) continue;
                chi2[j] = x; nArr[j] = n;
                matched++;
            }
        }
        if (matched < MIN_SNPS)
            throw new IOException("Only " + matched + " SNPs of the GWAS file match the HapMap3 LD-score SNPs (need "
                + MIN_SNPS + "); LD score regression needs genome-wide summary statistics, not top hits only.");

        // Sample-size definition. h2 scales with 1/N, so this matters more than anything else here.
        // For case-control data: a PGC-style NEFF column holds half the effective sample size; it is detected
        // against the effective N implied by the configured cases/controls and doubled. Otherwise N is classed as
        // an effective sample size or a total (cases + controls) by which of the two its median is closer to on the
        // log scale (a total that varies across SNPs in a meta-analysis stays a total). An effective N uses a sample
        // prevalence of 0.5 in the liability conversion, a total uses cases / (cases + controls).
        boolean caseControl = cfg.nCases > 0 && cfg.nControls > 0;
        String nDefinition;
        double samplePrev = Double.NaN;
        double neffCC = caseControl ? 4.0 / (1.0 / cfg.nCases + 1.0 / cfg.nControls) : Double.NaN;
        double total = cfg.nCases + (double) cfg.nControls;
        double[] nm = new double[(int) matched];
        for (int i = 0, q = 0; i < nArr.length; i++) if (!Double.isNaN(chi2[i])) nm[q++] = nArr[i];
        Arrays.sort(nm);
        double medN = nm[nm.length / 2];
        String nCol = cfg.colN.isEmpty() ? "" : cfg.colN;
        if (caseControl) {
            double rHalf = medN / neffCC;
            if (nCol.toLowerCase().contains("neff") && rHalf > 0.4 && rHalf < 0.6) {
                for (int i = 0; i < nArr.length; i++) nArr[i] *= 2;
                medN *= 2;
                nDefinition = "per-SNP " + nCol + " doubled (PGC convention: half the effective sample size; median "
                    + Math.round(medN / 2) + " vs effective N " + Math.round(neffCC) + " from cases/controls)";
                samplePrev = 0.5;
            } else if (Math.abs(Math.log(medN / neffCC)) < Math.abs(Math.log(medN / total))) {
                nDefinition = (nCol.isEmpty() ? "configured sample size" : "per-SNP " + nCol) + " treated as an effective sample size (median "
                    + Math.round(medN) + ", closer to the effective N " + Math.round(neffCC) + " than to cases + controls " + Math.round(total) + ")";
                samplePrev = 0.5;
            } else {
                nDefinition = (nCol.isEmpty() ? "configured sample size" : "per-SNP " + nCol) + " treated as cases + controls (median "
                    + Math.round(medN) + " vs total " + Math.round(total) + ")";
                samplePrev = cfg.nCases / total;
            }
        } else {
            nDefinition = nCol.isEmpty() ? "configured sample size" : "per-SNP " + nCol;
        }

        progress.update("Fitting LD score regression on " + matched + " SNPs", 1);
        int k = (int) matched, j2 = 0;
        double[] y = new double[k], l = new double[k], w = new double[k], n = new double[k];
        for (int i = 0; i < ref.size(); i++) {
            if (Double.isNaN(chi2[i])) continue;
            y[j2] = chi2[i]; l[j2] = ref.get(i)[1]; w[j2] = ref.get(i)[1]; n[j2] = nArr[i];
            j2++;
        }
        LdscRegression.Result r = LdscRegression.fit(y, l, w, n, m, LdscRegression.DEFAULT_BLOCKS);
        if (caseControl) LdscRegression.toLiability(r, cfg.prevalence, samplePrev);

        String json = toJson(r, rows, matched, fromP, m, nDefinition);
        File out = resultFile(cfg);
        out.getParentFile().mkdirs();
        Files.write(out.toPath(), json.getBytes("UTF-8"));
        return json;
    }

    public interface ProgressListener { void update(String phase, int step); }

    private static double parse(String[] f, int i) {
        if (i < 0 || i >= f.length) return Double.NaN;
        try { return Double.parseDouble(f[i].trim()); } catch (NumberFormatException e) { return Double.NaN; }
    }

    private static String num(double v) { return Double.isNaN(v) || Double.isInfinite(v) ? "null" : String.format(Locale.ROOT, "%.6g", v); }

    static String toJson(LdscRegression.Result r, long rows, long matched, long fromP, double m, String nDefinition) {
        return "{\"method\":\"LD score regression (two-step estimator, 200-block jackknife)\""
            + ",\"ld_scores\":\"1000 Genomes EUR, HapMap3 SNPs (eur_w_ld_chr)\""
            + ",\"timestamp\":" + System.currentTimeMillis()
            + ",\"gwas_rows\":" + rows + ",\"snps_matched\":" + matched + ",\"snps_used\":" + r.nSnps
            + ",\"chi2_from_p\":" + fromP + ",\"m\":" + num(m)
            + ",\"n_definition\":\"" + nDefinition.replace("\"", "'") + "\""
            + ",\"mean_chi2\":" + num(r.meanChi2) + ",\"lambda_gc\":" + num(r.lambdaGC) + ",\"mean_n\":" + num(r.meanN)
            + ",\"h2\":" + num(r.h2) + ",\"h2_se\":" + num(r.h2Se)
            + ",\"intercept\":" + num(r.intercept) + ",\"intercept_se\":" + num(r.interceptSe)
            + ",\"ratio\":" + num(r.ratio) + ",\"ratio_se\":" + num(r.ratioSe)
            + ",\"prevalence\":" + num(r.prevalence) + ",\"sample_prevalence\":" + num(r.samplePrevalence)
            + ",\"h2_liability\":" + num(r.h2Liability) + ",\"h2_liability_se\":" + num(r.h2LiabilitySe)
            + ",\"note\":\"" + r.note.replace("\"", "'") + "\"}";
    }
}
