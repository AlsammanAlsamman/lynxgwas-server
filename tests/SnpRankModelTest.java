import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Causal-SNP ranking model: JSON loading, log10 feature transforms with a floor, and contributions. */
public class SnpRankModelTest {

    public static void main(String[] args) throws Exception {
        int failures = 0;
        File f = Files.createTempFile("snp_rank_model", ".json").toFile();
        Files.write(f.toPath(), ("{\"version\":\"test\",\"type\":\"conditional_logit\",\"intercept\":0.0,\"features\":["
            + "{\"name\":\"abf_pip\",\"mean\":-2.0,\"sd\":1.0,\"coef\":1.5,\"transform\":\"log10\",\"floor\":0.0001},"
            + "{\"name\":\"cadd_phred\",\"mean\":5.0,\"sd\":5.0,\"coef\":0.5}]}").getBytes());
        SnpRankModel.Model m = SnpRankModel.load(f);
        failures += check("conditional logit recognised", m.conditional());
        Map<String, Double> x = new HashMap<>();
        x.put("abf_pip", 0.1);      // log10 = -1 -> standardised +1
        x.put("cadd_phred", 15.0);  // standardised +2
        failures += check("linear predictor = 1.5*1 + 0.5*2 = 2.5", Math.abs(m.linear(x) - 2.5) < 1e-12);
        x.put("abf_pip", 0.0);      // floored at 1e-4 -> log10 = -4 -> standardised -2
        failures += check("PIP of 0 floored at 1e-4 before log10", Math.abs(m.linear(x) - (1.5 * -2 + 0.5 * 2)) < 1e-12);
        Map<String, Double> c = m.contributions(x);
        failures += check("contributions sum to the linear predictor",
            Math.abs(c.values().stream().mapToDouble(Double::doubleValue).sum() - m.linear(x)) < 1e-12);
        failures += check("untransformed feature contribution", Math.abs(c.get("cadd_phred") - 1.0) < 1e-12);

        // the shipped model must use exactly the features the app computes, or a feature would silently drop out
        File shipped = new File("tools/snp_rank_model.json");
        if (shipped.isFile()) {
            SnpRankModel.Model sm = SnpRankModel.load(shipped);
            Set<String> known = new HashSet<>(Arrays.asList(SnpRankModel.FEATURES));
            failures += check("shipped model loads as a conditional logit", sm.conditional());
            failures += check("every shipped-model feature is computed by the app", known.containsAll(sm.coef.keySet()));
            failures += check("the app computes no feature the shipped model lacks", sm.coef.keySet().containsAll(known));
        }
        if (failures > 0) { System.out.println("FAIL: " + failures + " test(s) failed"); System.exit(1); }
        System.out.println("PASS: all SnpRankModel tests passed");
    }

    private static int check(String label, boolean ok) {
        System.out.println((ok ? "PASS: " : "FAIL: ") + label);
        return ok ? 0 : 1;
    }
}
