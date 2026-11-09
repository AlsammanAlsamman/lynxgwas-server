import java.io.*;
import java.util.*;

/**
 * Locus Matrix phase 1: streams each selected dataset's raw GWAS file once,
 * keeping only the best (lowest) p-value seen per chr:pos position across
 * all datasets, filtered to p &lt;= pFilter. Writes the merged set to a
 * temp TSV (chrom, pos, p) for loci.LociIdentifier to consume unchanged.
 * Datasets on different builds are merged in the higher build (GRCh38): the GRCh37 ones' SNPs are lifted
 * as they are read, and those without a mapping on the same chromosome are left out.
 */
public class MultiLocusMerger {

    public static void merge(List<Config> configs, double pFilter, File outFile,
                              MultiLocusProgress progress) throws IOException {
        Map<String, double[]> best = new HashMap<>(); // "chr:pos" -> {p}
        String target = MultiLocusScanner.targetBuild(configs);

        progress.phase = "merge";
        progress.datasetTotal = configs.size();

        for (int i = 0; i < configs.size(); i++) {
            Config cfg = configs.get(i);
            progress.datasetIndex = i;
            progress.currentDataset = new File(cfg.outputDir).getName();
            streamOne(cfg, pFilter, best, target);
        }
        progress.datasetIndex = configs.size();

        outFile.getParentFile().mkdirs();
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(outFile)))) {
            pw.println("chrom\tpos\tp");
            for (Map.Entry<String, double[]> e : best.entrySet()) {
                int c = e.getKey().indexOf(':');
                String chr = e.getKey().substring(0, c);
                String pos = e.getKey().substring(c + 1);
                pw.println(chr + "\t" + pos + "\t" + e.getValue()[0]);
            }
        }
    }

    private static void streamOne(Config cfg, double pFilter, Map<String, double[]> best, String target) throws IOException {
        boolean lift = GenomeLiftover.isGrch38(cfg.genomeBuild) != GenomeLiftover.isGrch38(target);
        long unmapped = 0;
        try (BufferedReader br = gwasio.GwasFiles.open(cfg.gwasFile)) {
            String header = br.readLine();
            if (header == null) return;
            String[] cols = header.trim().split("\t");
            int iChr = GwasParser.colIdx(cols, cfg.colChr);
            int iPos = GwasParser.colIdx(cols, cfg.colPos);
            int iP   = GwasParser.colIdx(cols, cfg.colPvalue);
            if (iChr < 0 || iPos < 0 || iP < 0)
                throw new IOException("Missing required columns in " + cfg.gwasFile +
                    ": chr=" + cfg.colChr + " pos=" + cfg.colPos + " p=" + cfg.colPvalue);

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] f = GwasParser.splitTab(line);
                if (f.length <= Math.max(iChr, Math.max(iPos, iP))) continue;

                double p;
                try { p = Double.parseDouble(f[iP].trim()); }
                catch (NumberFormatException e) { continue; }
                if (p <= 0 || p > pFilter) continue;

                String chr = f[iChr].trim();
                String pos = f[iPos].trim();
                if (lift) {
                    long lp;
                    try { lp = GenomeLiftover.position(cfg.genomeBuild, target, chr, Long.parseLong(pos)); }
                    catch (NumberFormatException e) { continue; }
                    if (lp < 0) { unmapped++; continue; }
                    pos = String.valueOf(lp);
                }
                String key = chr + ":" + pos;

                double[] cur = best.get(key);
                if (cur == null || p < cur[0]) {
                    best.put(key, new double[]{p});
                }
            }
        }
        if (lift) System.out.printf("[MultiLocus] %s lifted %s -> %s for the merge (%,d SNPs without a mapping left out)%n",
            cfg.outputDir, GenomeLiftover.normalize(cfg.genomeBuild), target, unmapped);
    }
}
