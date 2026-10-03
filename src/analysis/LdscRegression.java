import java.util.*;

/**
 * LD score regression (Bulik-Sullivan et al. 2015) for SNP-heritability from GWAS summary statistics.
 *
 * Model: E[chi2_j] = N_j * h2 * l_j / M + a, where l_j is SNP j's LD score, M the number of SNPs the
 * LD scores were computed over and a the intercept (1 + N*a' under confounding/stratification).
 *
 * Follows the reference ldsc implementation's default (two-step) estimator:
 *   - SNPs with chi2 > max(80, 0.001 * max N) are removed;
 *   - regression weights 1 / (2 * (a + N h2 l / M)^2 * max(w_l, 1)), with l clipped at 1, updated by
 *     iteratively reweighted least squares from an aggregate starting value;
 *   - step 1 estimates the intercept on SNPs with chi2 < 30, step 2 fixes it and estimates the slope on
 *     all SNPs;
 *   - standard errors by a block jackknife over contiguous SNP blocks (default 200), with the weights
 *     held fixed, as ldsc does.
 * Liability-scale conversion (Lee et al. 2011) needs the population prevalence K and the sample
 * prevalence P of a case-control study.
 */
public final class LdscRegression {

    public static final int DEFAULT_BLOCKS = 200;

    public static final class Result {
        public int nSnps;
        public double meanChi2, lambdaGC, meanN;
        public double h2, h2Se, intercept, interceptSe, ratio = Double.NaN, ratioSe = Double.NaN;
        public double h2Liability = Double.NaN, h2LiabilitySe = Double.NaN;
        public double prevalence = Double.NaN, samplePrevalence = Double.NaN;
        public String note = "";
    }

    private LdscRegression() {}

    /**
     * @param chi2 per-SNP chi-square statistics, in genomic order (chromosome, position) so jackknife
     *             blocks are contiguous
     * @param ld   LD score of each SNP
     * @param wld  regression-weight LD score of each SNP (the same as ld for non-partitioned LD scores)
     * @param n    per-SNP sample size
     * @param m    number of SNPs the LD scores sum over (e.g. the M_5_50 total)
     */
    public static Result fit(double[] chi2, double[] ld, double[] wld, double[] n, double m, int nBlocks) {
        int len = chi2.length;
        if (ld.length != len || wld.length != len || n.length != len) throw new IllegalArgumentException("Input arrays differ in length");
        double maxN = 0;
        for (double v : n) maxN = Math.max(maxN, v);
        double chi2Max = Math.max(80, 0.001 * maxN);
        int[] keep = new int[len];
        int k = 0;
        for (int i = 0; i < len; i++)
            if (chi2[i] >= 0 && chi2[i] <= chi2Max && ld[i] > -1e9 && n[i] > 0 && !Double.isNaN(chi2[i])) keep[k++] = i;
        if (k < 1000) throw new IllegalArgumentException("Only " + k + " SNPs left for LD score regression (need at least 1000)");
        double[] y = new double[k], u = new double[k], l = new double[k], wl = new double[k], nn = new double[k];
        double sumChi = 0, sumLN = 0, sumN = 0;
        for (int j = 0; j < k; j++) {
            int i = keep[j];
            y[j] = chi2[i]; l[j] = ld[i]; wl[j] = wld[i]; nn[j] = n[i];
            u[j] = n[i] * ld[i] / m;
            sumChi += y[j]; sumLN += ld[i] * n[i]; sumN += n[i];
        }
        Result r = new Result();
        r.nSnps = k;
        r.meanChi2 = sumChi / k;
        r.meanN = sumN / k;
        double[] sorted = y.clone();
        Arrays.sort(sorted);
        double median = k % 2 == 1 ? sorted[k / 2] : 0.5 * (sorted[k / 2 - 1] + sorted[k / 2]);
        r.lambdaGC = median / 0.4549364231195724;   // median of chi2(1)

        // Starting values (ldsc's aggregate estimator), then IRWLS on the step-1 subset
        double h2 = clamp(m * (r.meanChi2 - 1) / (sumLN / k), 0, 1);
        double a = 1;
        boolean[] sub = new boolean[k];
        for (int j = 0; j < k; j++) sub[j] = y[j] < 30;
        double[] w = new double[k];
        for (int it = 0; it < 3; it++) {
            weights(w, a, h2, nn, l, wl, m);
            double[] s = sums(w, u, y, sub, 0, k);
            double[] ab = solve2(s);
            h2 = ab[0]; a = ab[1];
        }
        // Final weights from the step-1 fit, held fixed for both steps and the jackknife
        weights(w, a, clamp(h2, 0, 1), nn, l, wl, m);

        int blocks = Math.max(2, Math.min(nBlocks, k / 10));
        double[][] subSums = new double[blocks][], allSums = new double[blocks][];
        double[] subTot = new double[5], allTot = new double[5];
        for (int b = 0; b < blocks; b++) {
            int from = (int) ((long) k * b / blocks), to = (int) ((long) k * (b + 1) / blocks);
            subSums[b] = sums(w, u, y, sub, from, to);
            allSums[b] = sums(w, u, y, null, from, to);
            for (int q = 0; q < 5; q++) { subTot[q] += subSums[b][q]; allTot[q] += allSums[b][q]; }
        }
        double[] est = twoStep(subTot, allTot);
        r.intercept = est[1];
        r.h2 = est[0] * 1.0;   // slope on u = N l / M is h2 directly
        double ratioFull = r.meanChi2 > 1 ? (r.intercept - 1) / (r.meanChi2 - 1) : Double.NaN;

        // Delete-one-block jackknife (pseudo-values)
        double[] psH2 = new double[blocks], psA = new double[blocks], psRatio = new double[blocks];
        double[] chiBlock = new double[blocks];
        int[] cntBlock = new int[blocks];
        for (int b = 0; b < blocks; b++) {
            int from = (int) ((long) k * b / blocks), to = (int) ((long) k * (b + 1) / blocks);
            for (int j = from; j < to; j++) chiBlock[b] += y[j];
            cntBlock[b] = to - from;
        }
        for (int b = 0; b < blocks; b++) {
            double[] s1 = new double[5], s2 = new double[5];
            for (int q = 0; q < 5; q++) { s1[q] = subTot[q] - subSums[b][q]; s2[q] = allTot[q] - allSums[b][q]; }
            double[] e = twoStep(s1, s2);
            double meanChiLoo = (sumChi - chiBlock[b]) / (k - cntBlock[b]);
            double ratioLoo = meanChiLoo > 1 ? (e[1] - 1) / (meanChiLoo - 1) : Double.NaN;
            psH2[b] = blocks * r.h2 - (blocks - 1) * e[0];
            psA[b] = blocks * r.intercept - (blocks - 1) * e[1];
            psRatio[b] = blocks * ratioFull - (blocks - 1) * ratioLoo;
        }
        r.h2Se = Math.sqrt(variance(psH2) / blocks);
        r.interceptSe = Math.sqrt(variance(psA) / blocks);
        if (!Double.isNaN(ratioFull) && r.meanChi2 > 1.02) {
            r.ratio = ratioFull;
            r.ratioSe = Math.sqrt(variance(psRatio) / blocks);
        }
        if (r.meanChi2 < 1.02) r.note = "Mean chi-square is close to 1: too little polygenic signal for a reliable estimate.";
        return r;
    }

    /** Converts an observed-scale h2 to the liability scale (Lee et al. 2011). */
    public static void toLiability(Result r, double prevalenceK, double samplePrevalenceP) {
        if (!(prevalenceK > 0 && prevalenceK < 1) || !(samplePrevalenceP > 0 && samplePrevalenceP < 1)) return;
        double t = inverseNormal(1 - prevalenceK);
        double z = Math.exp(-t * t / 2) / Math.sqrt(2 * Math.PI);
        double c = prevalenceK * prevalenceK * (1 - prevalenceK) * (1 - prevalenceK)
            / (samplePrevalenceP * (1 - samplePrevalenceP) * z * z);
        r.prevalence = prevalenceK;
        r.samplePrevalence = samplePrevalenceP;
        r.h2Liability = r.h2 * c;
        r.h2LiabilitySe = r.h2Se * c;
    }

    /** Chi-square (1 df) from a two-sided p-value, for files without beta/SE. */
    public static double chi2FromP(double p) {
        if (!(p > 0) || p > 1) return Double.NaN;
        double z = inverseNormal(p / 2);
        return z * z;
    }

    // ── internals ─────────────────────────────────────────────────────────

    private static void weights(double[] w, double a, double h2, double[] n, double[] l, double[] wl, double m) {
        for (int j = 0; j < w.length; j++) {
            double het = a + h2 * n[j] * Math.max(l[j], 1) / m;
            if (het < 1e-6) het = 1e-6;
            w[j] = 1.0 / (2 * het * het * Math.max(wl[j], 1));
        }
    }

    /** Weighted sufficient statistics {sum w, sum w u, sum w u^2, sum w y, sum w u y} over [from, to). */
    private static double[] sums(double[] w, double[] u, double[] y, boolean[] mask, int from, int to) {
        double sw = 0, swu = 0, swuu = 0, swy = 0, swuy = 0;
        for (int j = from; j < to; j++) {
            if (mask != null && !mask[j]) continue;
            double ww = w[j];
            sw += ww; swu += ww * u[j]; swuu += ww * u[j] * u[j]; swy += ww * y[j]; swuy += ww * u[j] * y[j];
        }
        return new double[]{sw, swu, swuu, swy, swuy};
    }

    /** Weighted least squares y = b u + a from sufficient statistics: returns {b, a}. */
    private static double[] solve2(double[] s) {
        double det = s[2] * s[0] - s[1] * s[1];
        if (Math.abs(det) < 1e-300) return new double[]{0, 1};
        double b = (s[4] * s[0] - s[1] * s[3]) / det;
        double a = (s[2] * s[3] - s[1] * s[4]) / det;
        return new double[]{b, a};
    }

    /** Step 1: intercept from the chi2 < 30 subset; step 2: slope on all SNPs with that intercept. */
    private static double[] twoStep(double[] subSums, double[] allSums) {
        double a = solve2(subSums)[1];
        double slope = allSums[2] > 0 ? (allSums[4] - a * allSums[1]) / allSums[2] : Double.NaN;
        return new double[]{slope, a};
    }

    private static double variance(double[] v) {
        double mean = 0;
        for (double x : v) mean += x;
        mean /= v.length;
        double s = 0;
        for (double x : v) s += (x - mean) * (x - mean);
        return s / (v.length - 1);
    }

    private static double clamp(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }

    /** Inverse standard normal CDF (Acklam's rational approximation, relative error below 1.2e-9). */
    public static double inverseNormal(double p) {
        if (p <= 0) return Double.NEGATIVE_INFINITY;
        if (p >= 1) return Double.POSITIVE_INFINITY;
        final double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
            1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        final double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
            6.680131188771972e+01, -1.328068155288572e+01};
        final double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
            -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        final double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
            3.754408661907416e+00};
        double q, x;
        if (p < 0.02425) {
            q = Math.sqrt(-2 * Math.log(p));
            x = (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        } else if (p > 1 - 0.02425) {
            q = Math.sqrt(-2 * Math.log(1 - p));
            x = -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        } else {
            q = p - 0.5;
            double r = q * q;
            x = (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
        }
        return x;
    }

}
