import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Sample-size check on synthetic summary statistics whose effective N is known. */
public class SampleSizeCheckTest {

    public static void main(String[] args) throws Exception {
        int failures = 0;
        File dir = Files.createTempDirectory("nchk").toFile();
        File gwas = new File(dir, "gwas.tsv");
        // 6000 SNPs, effective N 40,000 on the log-odds scale: SE^2 = 2 / (Neff p(1-p)); half the SNPs come from
        // half the sample (as in a meta-analysis where some studies lack them), recorded in the per-SNP N column
        Random rnd = new Random(7);
        try (PrintWriter pw = new PrintWriter(gwas, "UTF-8")) {
            pw.println("chr\tpos\tea\tnea\tbeta\tse\tp\teaf\tn");
            for (int i = 0; i < 6000; i++) {
                double p = 0.1 + 0.4 * rnd.nextDouble();
                boolean partial = i % 2 == 1;
                double neff = partial ? 20_000 : 40_000, total = partial ? 50_000 : 100_000;
                double se = Math.sqrt(2.0 / (neff * p * (1 - p)));
                pw.printf(Locale.ROOT, "%d\t%d\tA\tG\t%.6g\t%.6g\t0.5\t%.4f\t%.0f%n", 1 + i % 22, 1_000_000 + i * 1000L,
                    se * rnd.nextGaussian(), se, p, total);
            }
        }
        Config cfg = new Config();
        cfg.gwasFile = gwas.getAbsolutePath();
        cfg.outputDir = dir.getAbsolutePath();
        cfg.colChr = "chr"; cfg.colPos = "pos"; cfg.colEa = "ea"; cfg.colNea = "nea"; cfg.colBeta = "beta"; cfg.colSe = "se";
        cfg.colPvalue = "p"; cfg.colMaf = "eaf"; cfg.colN = "n"; cfg.effectType = "beta"; cfg.traitType = "binary";
        File noLd = new File(dir, "no-ld-scores");
        // 1. configured correctly: an effective N of 40,000 from a total of 100,000 means a case fraction P with
        //    4 P (1-P) = 0.4
        double P = (1 - Math.sqrt(1 - 0.4)) / 2;
        cfg.nCases = (int) Math.round(100_000 * P); cfg.nControls = 100_000 - cfg.nCases; cfg.sampleN = 100_000;
        Map<String, Object> r = MiniJson.asObject(MiniJson.parse(SampleSizeCheck.run(cfg, noLd)));
        double ratio = ((Number) r.get("ratio")).doubleValue();
        failures += check("correct N accepted (ratio " + String.format(Locale.ROOT, "%.2f", ratio) + ")", "ok".equals(r.get("verdict")));
        failures += check("per-SNP N removes the partial-coverage dip (ratio near 1/calibration)",
            Math.abs(ratio - 1 / SampleSizeCheck.CALIBRATION) < 0.08);

        // 2. configured 3x too small (cases and controls from a smaller stage of the study)
        cfg.nCases /= 3; cfg.nControls /= 3; cfg.sampleN /= 3;
        r = MiniJson.asObject(MiniJson.parse(SampleSizeCheck.run(cfg, noLd)));
        long sug = ((Number) MiniJson.asObject(r.get("suggested")).get("n_effective")).longValue();
        failures += check("N three times too small is flagged", "mismatch".equals(r.get("verdict")));
        failures += check("suggested effective N close to 40,000 / calibration (" + sug + ")",
            Math.abs(sug - 40_000 / SampleSizeCheck.CALIBRATION) < 0.05 * 40_000);

        // 3. the suggestion, once set, passes the check; the cache key changes with it
        String k1 = SampleSizeCheck.key(cfg);
        cfg.nEffective = (int) sug;
        failures += check("cache key includes the effective N", !k1.equals(SampleSizeCheck.key(cfg)));
        r = MiniJson.asObject(MiniJson.parse(SampleSizeCheck.run(cfg, noLd)));
        failures += check("effective N set from the suggestion is accepted", "ok".equals(r.get("verdict")));

        // 4. the setting survives the properties file and is what the analyses use
        File props = new File(dir, "config.properties");
        cfg.sampleSizeChanged = 1234567890123L;
        cfg.writeProperties(props.getAbsolutePath());
        Config back = Config.loadFromProject(dir.getAbsolutePath());
        failures += check("effective N and change time round-trip through config.properties",
            back.nEffective == cfg.nEffective && back.sampleSizeChanged == 1234567890123L);
        failures += check("analysisN() uses the effective N", back.analysisN() == cfg.nEffective);
        back.nEffective = 0;
        failures += check("analysisN() falls back to sample.n", back.analysisN() == back.sampleN);

        if (failures > 0) { System.out.println("FAIL: " + failures + " test(s) failed"); System.exit(1); }
        System.out.println("PASS: all SampleSizeCheck tests passed");
    }

    private static int check(String label, boolean ok) {
        System.out.println((ok ? "PASS: " : "FAIL: ") + label);
        return ok ? 0 : 1;
    }
}
