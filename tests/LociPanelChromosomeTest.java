import java.io.*;
import java.nio.file.*;
import java.util.*;
import loci.LociIdentifier;
import loci.LociProgress;

/**
 * Regression test: a chromosome that is in the GWAS file but not in the reference panel (chrX with an
 * autosome-only 1000 Genomes panel) used to lose every locus silently, because PLINK clumping wrote no
 * output for it. Its loci must now come from its genome-wide significant SNPs merged by position, and
 * the progress must carry a warning that says so. chr1, which is in the panel, is still clumped.
 *
 * Needs PLINK (bin/plink or on PATH); prints SKIP without it.
 */
public class LociPanelChromosomeTest {

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("loci_panel_chr");
        try {
            // Reference panel: chr1 only, 4 samples, rs1 and rs2 with identical genotypes (r2 = 1)
            Path ref = dir.resolve("ref");
            try (PrintWriter pw = new PrintWriter(ref + ".bim")) {
                pw.println("1\trs1\t0\t1000000\tA\tG");
                pw.println("1\trs2\t0\t1050000\tA\tG");
            }
            try (PrintWriter pw = new PrintWriter(ref + ".fam")) {
                for (int i = 1; i <= 4; i++) pw.println("f" + i + " i" + i + " 0 0 1 -9");
            }
            // SNP-major .bed; per SNP one byte for 4 samples: hom, het, hom-other, het
            byte geno = (byte) 0b10_11_10_00;
            Files.write(Paths.get(ref + ".bed"), new byte[]{0x6c, 0x1b, 0x01, geno, geno});

            Path gwas = dir.resolve("gwas.tsv");
            try (PrintWriter pw = new PrintWriter(gwas.toFile())) {
                pw.println("chr\tpos\tp\tid");
                pw.println("1\t1000000\t1e-10\tsnpA");
                pw.println("1\t1050000\t1e-4\tsnpB");
                pw.println("X\t5000000\t1e-9\tx1");
                pw.println("X\t5100000\t1e-12\tx2");
                pw.println("X\t9000000\t1e-8\tx3");
                pw.println("X\t9100000\t0.2\tx4");
            }

            if (ToolLocator.binary(new File("."), "plink") == null) {
                System.out.println("SKIP: LociPanelChromosomeTest needs PLINK (bin/plink or on PATH)");
                return;
            }
            LociIdentifier.Params params = new LociIdentifier.Params();
            params.refPanelPath = ref.toString();
            LociProgress progress = new LociProgress();
            List<LociIdentifier.IdentifiedLocus> loci =
                LociIdentifier.identify(gwas.toString(), "chr", "pos", "p", "id", params, progress);

            int failures = 0;
            List<LociIdentifier.IdentifiedLocus> x = new ArrayList<>(), one = new ArrayList<>();
            for (LociIdentifier.IdentifiedLocus l : loci) (l.chr.equals("X") ? x : one).add(l);
            failures += check("chr1 keeps one clumped locus", one.size() == 1);
            failures += check("chr1 locus spans the LD-linked member (1,000,000-1,050,000)",
                one.size() == 1 && one.get(0).start == 1_000_000 && one.get(0).end == 1_050_000);
            failures += check("chrX is not lost: two loci from its significant SNPs", x.size() == 2);
            failures += check("chrX loci merged by distance (5.0-5.1 Mb and 9.0 Mb)",
                x.size() == 2 && x.get(0).start == 5_000_000 && x.get(0).end == 5_100_000
                    && x.get(1).start == 9_000_000 && x.get(1).end == 9_000_000);
            failures += check("progress warns that chrX was not clumped",
                progress.warning != null && progress.warning.contains("chrX"));
            failures += check("progress JSON carries the warning", progress.toJson().contains("\"warning\":\"chrX"));

            if (failures == 0) System.out.println("PASS: all LociPanelChromosome tests passed");
            else { System.out.println("FAIL: " + failures + " test(s) failed"); System.exit(1); }
        } finally {
            try (java.util.stream.Stream<Path> w = Files.walk(dir)) {
                w.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    private static int check(String name, boolean ok) {
        System.out.println((ok ? "PASS: " : "FAIL: ") + name);
        return ok ? 0 : 1;
    }
}
