import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Checks the configured sample size against the one the summary statistics themselves imply.
 *
 * A SNP's standard error is set by the sample size and its allele frequency p: for a case-control GWAS on the
 * log-odds scale SE^2 ~ 2 / (Neff p(1-p)), with Neff = 4 / (1/cases + 1/controls); for a linear model on a 0/1
 * outcome (BOLT-LMM and other biobank GWAS) SE^2 ~ P(1-P) / (2 N p(1-p)), with P the case fraction; for a
 * standardised quantitative trait SE^2 ~ 1 / (2 N p(1-p)). The median over common SNPs (MAF >= 5%, MHC excluded)
 * gives the implied N. Allele frequencies come from the file when it has them, else from the 1000 Genomes
 * European MAF of the LD-score SNPs; with a per-SNP N column each SNP is compared with its own N. Imputation
 * quality makes the implied N slightly smaller than the true one; ratios are calibrated for it (CALIBRATION) and
 * only differences beyond the tolerance are reported. A mismatch is corrected by setting an effective sample size
 * (Config.nEffective), which every analysis then uses with a sample prevalence of 0.5; the case and control counts,
 * facts about the study, stay as they are.
 *
 * Every per-locus analysis (SuSiE, ABF, COJO, MAGMA, local heritability) and LD score regression use the
 * configured N, so a wrong N is not a cosmetic error: local heritability, for one, scales with 1/N.
 */
public final class SampleSizeCheck {

    /** Implied/configured ratios inside [1/TOL, TOL] are accepted. */
    public static final double TOL = 1.5;
    static final int MIN_SNPS = 2000;
    /** Implied/configured effective N in correctly configured datasets (median over the validation corpus's single
     *  cohorts and per-SNP-N files: FinnGen, PGC, CLOZUK, IIBDGC, ...); below 1 because imputation quality lowers
     *  the information per SNP. Suggestions are divided by it so they are on the nominal-N scale the methods expect. */
    static final double CALIBRATION = 0.94;

    private SampleSizeCheck() {}

    public static File resultFile(Config cfg) { return new File(cfg.outputDir, "sample_size_check.json"); }

    /** Cache key: the GWAS file (size and date) and the sample-size settings it was checked against. */
    public static String key(Config cfg) {
        File g = gwasFile(cfg);
        return g.length() + "|" + g.lastModified() + "|" + cfg.sampleN + "|" + cfg.nCases + "|" + cfg.nControls + "|" + cfg.colN
            + "|" + cfg.colSe + "|" + cfg.colBeta + "|" + cfg.colOr + "|" + cfg.colMaf + "|" + cfg.nEffective + "|v4";
    }

    static File gwasFile(Config cfg) {
        File g = new File(cfg.gwasFile);
        if (!g.isFile() && new File(cfg.gwasFile + ".gz").isFile()) g = new File(cfg.gwasFile + ".gz");
        return g;
    }

    /** Runs the check, writes sample_size_check.json and returns its JSON. */
    public static String run(Config cfg, File ldDir) throws IOException {
        // reference allele frequencies (1000 Genomes EUR) of the LD-score SNPs, keyed by chr * 1e10 + pos
        Map<Long, Double> refMaf = new HashMap<>(1 << 21);
        if (new File(ldDir, "1.l2.ldscore.gz").isFile()) {
            for (int c = 1; c <= 22; c++) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(new FileInputStream(new File(ldDir, c + ".l2.ldscore.gz"))), "UTF-8"))) {
                    List<String> h = Arrays.asList(br.readLine().split("\t"));
                    int iBp = h.indexOf("BP"), iMaf = h.indexOf("MAF");
                    if (iMaf < 0) break;
                    String line;
                    while ((line = br.readLine()) != null) {
                        String[] f = line.split("\t");
                        refMaf.put(c * 10_000_000_000L + Long.parseLong(f[iBp]), Double.parseDouble(f[iMaf]));
                    }
                }
            }
        }
        File gwas = gwasFile(cfg);
        if (!gwas.isFile()) throw new IOException("GWAS file not found: " + cfg.gwasFile);
        boolean or = "OR".equalsIgnoreCase(cfg.effectType);
        List<Double> invVar = new ArrayList<>();      // 1 / (SE^2 * p(1-p)) per SNP
        List<Double> perSnpN = new ArrayList<>(), perSnpRatio = new ArrayList<>();   // N column; information / N per SNP
        String freqSource = "";
        long rows = 0;
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
            int iF = cfg.colMaf.isEmpty() ? -1 : h.indexOf(cfg.colMaf);
            if (iChr < 0 || iPos < 0) throw new IOException("Chromosome/position columns not found in the GWAS file header");
            if (iBeta < 0 && iOr < 0) throw new IOException("The file has no effect-size column, so its standard errors cannot be checked");
            freqSource = iF >= 0 ? "the file's allele-frequency column (" + cfg.colMaf + ")" : "1000 Genomes European MAF (LD-score SNPs)";
            if (iF < 0 && refMaf.isEmpty()) throw new IOException("No allele frequencies: the file has none and the LD scores were not found");
            String line;
            while ((line = br.readLine()) != null) {
                rows++;
                String[] f = sep.equals("\t") ? line.split("\t", -1) : line.trim().split(sep);
                if (f.length <= Math.max(iChr, iPos)) continue;
                String cs = f[iChr].trim();
                if (cs.regionMatches(true, 0, "chr", 0, 3)) cs = cs.substring(3);
                int c;
                long pos;
                try { c = Integer.parseInt(cs); pos = Long.parseLong(f[iPos].trim()); } catch (NumberFormatException e) { continue; }
                if (c < 1 || c > 22 || (c == 6 && pos > 25_000_000 && pos < 34_000_000)) continue;   // MHC out
                double p = iF >= 0 ? parse(f, iF) : Double.NaN;
                if (!(p > 0 && p < 1)) p = refMaf.getOrDefault(c * 10_000_000_000L + pos, Double.NaN);   // empty column: reference
                if (Double.isNaN(p)) continue;
                double maf = Math.min(p, 1 - p);
                if (maf < 0.05) continue;
                double b = parse(f, iBeta >= 0 ? iBeta : iOr);
                if (iBeta < 0 || (or && iOr < 0)) b = b > 0 ? Math.log(b) : Double.NaN;   // odds ratios -> log(OR), as in GenomeHeritability
                double se = iSe >= 0 ? parse(f, iSe) : Double.NaN;
                if (!(se > 0) && iP >= 0) {          // no SE column: from beta and p (only informative away from p = 1)
                    double pv = parse(f, iP);
                    if (pv > 0 && pv < 0.5 && !Double.isNaN(b) && b != 0) se = Math.abs(b) / Math.sqrt(LdscRegression.chi2FromP(pv));
                }
                if (!(se > 0) || Double.isInfinite(se)) continue;
                double iv = 1.0 / (se * se * maf * (1 - maf));
                invVar.add(iv);
                if (iN >= 0) { double n = parse(f, iN); if (n > 0) { perSnpN.add(n); perSnpRatio.add(iv / n); } }
            }
        }
        if (invVar.size() < MIN_SNPS)
            throw new IOException("Only " + invVar.size() + " common SNPs with a standard error and an allele frequency (need "
                + MIN_SNPS + ")");
        double medPerSnpN = perSnpN.isEmpty() ? Double.NaN : median(perSnpN);
        // With a per-SNP N column each SNP is compared with its own N and the result scaled to the full-study N (the
        // 99th percentile of the column: in a meta-analysis the SNPs present in every study), so SNPs missing from
        // some studies do not pull the estimate down; otherwise the median SNP.
        String basis;
        double med;
        if (perSnpRatio.size() >= MIN_SNPS) {
            med = median(perSnpRatio) * quantile(perSnpN, 0.99);
            basis = "each SNP against its own N (" + cfg.colN + "), scaled to the full-study N";
        } else {
            med = median(invVar);
            basis = "the median common SNP";
        }

        boolean caseControl = cfg.nCases > 0 && cfg.nControls > 0;
        boolean binary = caseControl || "binary".equalsIgnoreCase(cfg.traitType);
        String scale, verdict, message;
        double impliedNeff, configured, rawRatio;
        String configuredWhat;
        if (caseControl) {
            double total = cfg.nCases + (double) cfg.nControls, P = cfg.nCases / total;
            double neffCC = 4.0 / (1.0 / cfg.nCases + 1.0 / cfg.nControls);
            // log-odds: Neff = 2 * median(1/(SE^2 p(1-p)));  0/1 linear model: N = P(1-P)/2 * median(...), Neff = 4 N P(1-P)
            double neffLog = 2 * med, neffLin = 4 * P * (1 - P) * (P * (1 - P) / 2 * med);
            if (Math.abs(Math.log(neffLin / neffCC)) < Math.abs(Math.log(neffLog / neffCC))) {
                scale = "linear model on a 0/1 outcome (e.g. BOLT-LMM)";
                impliedNeff = neffLin;
            } else {
                scale = "log-odds";
                impliedNeff = neffLog;
            }
            configured = neffCC;
            configuredWhat = "effective N of the configured " + String.format(Locale.ROOT, "%,d cases and %,d controls", cfg.nCases, cfg.nControls);
        } else {
            scale = binary ? "log-odds (cases and controls not set)" : "standardised quantitative trait (assumed)";
            impliedNeff = binary ? 2 * med : med / 2;
            configured = cfg.sampleN;
            configuredWhat = "configured sample size";
        }
        if (cfg.nEffective > 0) {                     // a correction is already in place: check it instead
            configured = cfg.nEffective;
            configuredWhat = "effective N set for this project";
        }
        // calibrated ratio: correctly configured datasets give implied/configured = CALIBRATION (imputation quality)
        rawRatio = configured > 0 ? impliedNeff / configured : Double.NaN;
        double ratio = rawRatio / CALIBRATION;
        long suggested = Math.round(impliedNeff / CALIBRATION);
        boolean ok = ratio >= 1 / TOL && ratio <= TOL;
        boolean implausible = scale.startsWith("linear") && !(ratio >= 1 / 3.0 && ratio <= 3.0);
        if (implausible) {
            verdict = "review";
            message = String.format(Locale.ROOT, "The standard errors fit neither a log-odds nor a 0/1 linear model with the configured "
                + "%,d cases and %,d controls (closest: %s, %.2f times the expected size). Check the effect and SE columns, the "
                + "effect type and the sample size against the paper; no automatic correction is offered.",
                cfg.nCases, cfg.nControls, scale, ratio);
            suggested = 0;
        } else if (Double.isNaN(ratio)) {
            verdict = "unknown";
            message = "No sample size is configured; the standard errors imply an effective N of about " + fmt(suggested) + ".";
        } else if (ok) {
            verdict = "ok";
            message = String.format(Locale.ROOT, "The standard errors agree with the %s (%.2f times the expected size).", configuredWhat, ratio);
            suggested = 0;
        } else {
            verdict = "mismatch";
            message = String.format(Locale.ROOT, "The standard errors imply an effective sample size of about %s, %.1f times %s the %s (%s). "
                + "SuSiE, ABF, COJO, MAGMA and local and genome-wide heritability all use N, so their results are affected. "
                + "Typical causes: a meta-analysis whose studies have different case fractions (the effective N is the sum of the "
                + "studies' effective N, smaller than the pooled one), proxy cases, or a sample size taken from a different "
                + "stage of the study than the file.",
                fmt(suggested), ratio >= 1 ? ratio : 1 / ratio, ratio >= 1 ? "larger than" : "smaller than", configuredWhat, fmt(configured));
        }
        if (!binary) message += " For a quantitative trait this assumes the trait was standardised; if it was not, the ratio only shows its scale.";

        String json = "{\"timestamp\":" + System.currentTimeMillis()
            + ",\"key\":\"" + key(cfg).replace("\\", "/").replace("\"", "'") + "\""
            + ",\"gwas_rows\":" + rows + ",\"snps_used\":" + invVar.size()
            + ",\"freq_source\":\"" + freqSource + "\",\"scale\":\"" + scale + "\",\"basis\":\"" + basis.replace("\"", "'") + "\""
            + ",\"implied_neff\":" + num(impliedNeff) + ",\"calibration\":" + CALIBRATION
            + ",\"configured\":" + num(configured) + ",\"configured_what\":\"" + configuredWhat + "\""
            + ",\"median_per_snp_n\":" + num(medPerSnpN) + ",\"n_effective_set\":" + cfg.nEffective
            + ",\"ratio\":" + num(ratio) + ",\"tolerance\":" + TOL
            + ",\"verdict\":\"" + verdict + "\",\"message\":\"" + message.replace("\"", "'") + "\""
            + ",\"suggested\":{\"n_effective\":" + suggested + "}}";
        File out = resultFile(cfg);
        out.getParentFile().mkdirs();
        Files.write(out.toPath(), json.getBytes("UTF-8"));
        return json;
    }

    static double quantile(List<Double> v, double q) {
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a);
        return a[Math.min(a.length - 1, (int) Math.floor(q * (a.length - 1)))];
    }

    static double median(List<Double> v) {
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a);
        return a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2;
    }

    private static double parse(String[] f, int i) {
        if (i < 0 || i >= f.length) return Double.NaN;
        try { return Double.parseDouble(f[i].trim()); } catch (NumberFormatException e) { return Double.NaN; }
    }

    private static String fmt(double v) { return Double.isNaN(v) ? "?" : String.format(Locale.ROOT, "%,d", Math.round(v)); }

    private static String num(double v) { return Double.isNaN(v) || Double.isInfinite(v) ? "null" : String.format(Locale.ROOT, "%.6g", v); }
}
