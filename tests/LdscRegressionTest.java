import java.util.*;

/**
 * LD score regression on simulated summary statistics with known truth.
 * chi2_j = (a + N h2 l_j / M) * Z_j^2 has exactly the expectation the regression models, so h2 and
 * the intercept must be recovered within a few jackknife standard errors.
 */
public class LdscRegressionTest {

    public static void main(String[] args) {
        int failures = 0;
        failures += recover(0.25, 1.00, 1);
        failures += recover(0.05, 1.00, 2);
        failures += recover(0.25, 1.08, 3);   // confounding: intercept above 1
        failures += testLiabilityConversion();
        failures += testInverseNormal();
        if (failures > 0) { System.out.println("FAIL: " + failures + " test(s) failed"); System.exit(1); }
        System.out.println("PASS: all LdscRegression tests passed");
    }

    private static int recover(double h2, double a, long seed) {
        Random rnd = new Random(seed);
        int k = 300_000;
        double m = 1_000_000, n = 100_000;
        double[] chi2 = new double[k], ld = new double[k], nn = new double[k];
        for (int i = 0; i < k; i++) {
            // LD scores ~ gamma-like, mean ~ 100, like HapMap3 SNPs in 1000G EUR
            double l = 5 + 95 * -Math.log(1 - rnd.nextDouble()) * 1.0;
            ld[i] = l;
            nn[i] = n;
            double z = rnd.nextGaussian();
            chi2[i] = (a + n * h2 * l / m) * z * z;
        }
        LdscRegression.Result r = LdscRegression.fit(chi2, ld, ld, nn, m, 200);
        int f = 0;
        String tag = String.format(Locale.ROOT, "h2=%.2f a=%.2f", h2, a);
        f += check(tag + ": h2 recovered " + fmt(r.h2) + " (SE " + fmt(r.h2Se) + ")", Math.abs(r.h2 - h2) < 4 * r.h2Se && r.h2Se > 0);
        f += check(tag + ": intercept recovered " + fmt(r.intercept) + " (SE " + fmt(r.interceptSe) + ")",
            Math.abs(r.intercept - a) < 4 * r.interceptSe && r.interceptSe > 0);
        f += check(tag + ": SE is small relative to the estimate", r.h2Se < 0.2 * h2);
        if (a > 1) {
            double trueRatio = (a - 1) / (r.meanChi2 - 1);
            f += check(tag + ": ratio " + fmt(r.ratio) + " matches the true " + fmt(trueRatio) + " and flags confounding",
                Math.abs(r.ratio - trueRatio) < 4 * r.ratioSe && r.ratio > 2 * r.ratioSe);
        }
        return f;
    }

    private static int testLiabilityConversion() {
        LdscRegression.Result r = new LdscRegression.Result();
        r.h2 = 0.10; r.h2Se = 0.01;
        LdscRegression.toLiability(r, 0.01, 0.5);
        // K = 0.01: t = 2.326348, phi(t) = 0.026652; c = K^2(1-K)^2 / (P(1-P) phi^2) = 0.553 (hand-computed)
        double t = 2.3263478740408408, z = Math.exp(-t * t / 2) / Math.sqrt(2 * Math.PI);
        double c = 0.01 * 0.01 * 0.99 * 0.99 / (0.25 * z * z);
        return check("liability conversion " + fmt(r.h2Liability) + " = 0.1 x " + fmt(c), Math.abs(r.h2Liability - 0.1 * c) < 1e-6)
            + check("liability SE scales the same way", Math.abs(r.h2LiabilitySe - 0.01 * c) < 1e-7);
    }

    private static int testInverseNormal() {
        return check("inverseNormal(0.975) = 1.959964", Math.abs(LdscRegression.inverseNormal(0.975) - 1.959963985) < 1e-7)
            + check("chi2 from p = 5e-8 is 29.716", Math.abs(LdscRegression.chi2FromP(5e-8) - 29.7164) < 1e-3)
            + check("chi2 from p = 1e-300 is finite and large", LdscRegression.chi2FromP(1e-300) > 1300 && LdscRegression.chi2FromP(1e-300) < 1400);
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.4f", v); }

    private static int check(String label, boolean ok) {
        System.out.println((ok ? "PASS: " : "FAIL: ") + label);
        return ok ? 0 : 1;
    }
}
