import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import rsid.GenomeRsidRecovery;

/**
 * GenomeRsidRecovery gives every line of a GWAS file its dbSNP rsID by RsidMatcher's rules: forward or reverse
 * alleles at the same position, forward first, then the newest dbSNP build; indels by their sequence; nothing
 * when only the position matches or the position is absent. Two files share one dbSNP pass.
 */
public class GenomeRsidRecoveryTest {
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("genomersid");
        try {
            String vcf = "##fileformat=VCFv4.0\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                + "1\t100\trs10\tA\tG\t.\t.\tdbSNPBuildID=120\n"          // forward for line 1
                + "1\t200\trs20\tC\tT\t.\t.\tdbSNPBuildID=120\n"          // reverse for line 2 (ea = C)
                + "1\t300\trs30\tG\tA,T\t.\t.\tdbSNPBuildID=130\n"        // two records at 300: the newer build wins
                + "1\t300\trs31\tG\tA\t.\t.\tdbSNPBuildID=150\n"
                + "2\t400\trs40\tAT\tA\t.\t.\tdbSNPBuildID=140\n"         // indel
                + "2\t500\trs50\tC\tG\t.\t.\tdbSNPBuildID=140\n"          // position matches, alleles do not
                + "X\t600\trs60\tT\tC\t.\t.\tdbSNPBuildID=140\n";         // chromosome given as 23 in the GWAS file
            Path db = dir.resolve("dbsnp.vcf.gz");
            try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(db))) { o.write(vcf.getBytes()); }
            Path g = dir.resolve("a.tsv");
            Files.write(g, ("CHR\tBP\tEA\tNEA\tP\n"
                + "1\t100\tg\ta\t0.1\n"          // line 1: rs10 forward (lower case alleles)
                + "chr1\t200\tC\tT\t0.2\n"       // line 2: rs20 reverse
                + "1\t300\tA\tG\t0.3\n"          // line 3: rs31 (build 150 over 130)
                + "2\t400\tA\tAT\t0.4\n"         // line 4: rs40 indel forward
                + "2\t500\tA\tT\t0.5\n"          // line 5: position only
                + "3\t700\tA\tG\t0.6\n"          // line 6: not in dbSNP
                + "23\t600\tC\tT\t0.7\n"         // line 7: rs60 on X
                + "x\tbad\tA\tG\t0.8\n").getBytes());   // line 8: unreadable position, kept with an empty rsID
            Path g2 = dir.resolve("b.txt");
            Files.write(g2, "chrom\tpos\ta1\ta2\n1\t100\tA\tG\n".getBytes());

            GenomeRsidRecovery.Job j = new GenomeRsidRecovery.Job(g.toString(), "CHR", "BP", "EA", "NEA");
            GenomeRsidRecovery.Job j2 = new GenomeRsidRecovery.Job(g2.toString(), "chrom", "pos", "a1", "a2");
            GenomeRsidRecovery.run(db.toString(), List.of(j, j2), new PrintStream(OutputStream.nullOutputStream()));

            List<String> out = Files.readAllLines(Paths.get(j.outputFile));
            report("output name follows RsidPipeline", j.outputFile.endsWith("a.with_rsid.tsv"));
            report("header gains recovered_rsid", out.get(0).equals("CHR\tBP\tEA\tNEA\tP\trecovered_rsid"));
            String[] want = {"rs10", "rs20", "rs31", "rs40", "", "", "rs60", ""};
            for (int i = 0; i < want.length; i++) {
                String[] f = out.get(i + 1).split("\t", -1);
                report("line " + (i + 1) + " -> '" + want[i] + "'", f[f.length - 1].equals(want[i]) && f.length == 6);
            }
            report("counts", j.forward == 4 && j.reverse == 1 && j.posOnly == 1 && j.lines == 8);
            report("second file in the same pass", Files.readAllLines(Paths.get(j2.outputFile)).get(1).endsWith("\trs10"));
        } finally {
            try (var s = Files.walk(dir)) { s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
        if (failures > 0) { System.out.println("FAIL: " + failures + " GenomeRsidRecovery check(s)"); System.exit(1); }
        System.out.println("PASS: all GenomeRsidRecovery tests passed");
    }

    static void report(String what, boolean ok) {
        if (!ok) { failures++; System.out.println("  FAIL " + what); }
    }
}
