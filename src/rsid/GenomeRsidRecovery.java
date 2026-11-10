package rsid;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Genome-wide rsID recovery: gives every SNP of a GWAS file its dbSNP rsID, not only the SNPs inside the
 * project's loci (which is what {@link RsidPipeline} covers). Several files share one pass over dbSNP.
 *
 * Matching is {@link RsidMatcher}'s: same position, and either nea = REF with ea among ALT (forward) or the
 * reverse; forward wins over reverse, then the highest dbSNPBuildID (the first one on a tie). No strand flips.
 *
 * Memory stays small because dbSNP is never held: each file's SNPs are kept as compact arrays sorted by
 * position, a per-chromosome bit set of the wanted positions rejects almost every dbSNP line at once, and
 * hits are matched against the file's SNPs as they stream past. About 16 bytes per GWAS line.
 *
 * Output, as RsidPipeline writes it: {@code <file without extension>.with_rsid.tsv}, the original lines with
 * a {@code recovered_rsid} column (empty when dbSNP has no match).
 *
 *   java rsid.GenomeRsidRecovery <dbsnp.vcf.gz> <gwas> <col chr> <col pos> <col ea> <col nea> [<gwas> ...]
 */
public class GenomeRsidRecovery {

    /** One GWAS file and the columns that locate its SNPs. */
    public static class Job {
        public final String gwasFile, colChr, colPos, colEa, colNea;
        public String outputFile;
        public long lines, forward, reverse, posOnly, noPos, unparsed;
        long[] keys;            // chr << 58 | pos << 30 | line, sorted
        byte[] snv;             // two-base code per line (ea << 4 | nea, A=1 C=2 G=3 T=4), 0 = not a plain SNV
        Map<Integer, String[]> other = new HashMap<>();   // line -> {ea, nea} for indels and the rest
        int[] rs;               // matched rs number per line, 0 = none
        byte[] how;             // 2 forward, 1 reverse, 0 none
        short[] build;          // dbSNPBuildID of the match

        public Job(String gwasFile, String colChr, String colPos, String colEa, String colNea) {
            this.gwasFile = gwasFile; this.colChr = colChr; this.colPos = colPos; this.colEa = colEa; this.colNea = colNea;
            String base = gwasFile.replaceAll("\\.gz$", "").replaceAll("\\.(tsv|txt|csv)$", "");
            this.outputFile = base + ".with_rsid.tsv";
        }

        public double rate() { return lines == 0 ? 0 : 100.0 * (forward + reverse) / lines; }
    }

    static final String[] CHROMS = {"", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15",
        "16", "17", "18", "19", "20", "21", "22", "X", "Y", "MT"};

    static int chrCode(String c) {
        c = c.trim();
        if (c.regionMatches(true, 0, "chr", 0, 3)) c = c.substring(3);
        switch (c) {
            case "X": case "x": case "23": return 23;
            case "Y": case "y": case "24": return 24;
            case "MT": case "M": case "mt": case "26": return 25;
            default:
                try { int n = Integer.parseInt(c); return n >= 1 && n <= 22 ? n : 0; }
                catch (NumberFormatException e) { return 0; }
        }
    }

    static int base(char b) {
        switch (b) { case 'A': case 'a': return 1; case 'C': case 'c': return 2; case 'G': case 'g': return 3; case 'T': case 't': return 4; default: return 0; }
    }

    static int col(String[] header, String name) {
        for (int i = 0; i < header.length; i++) if (header[i].trim().equalsIgnoreCase(name.trim())) return i;
        throw new IllegalArgumentException("column '" + name + "' not found");
    }

    /** Reads a file's SNPs into the job's arrays. */
    static void load(Job j) throws IOException {
        long[] keys = new long[1 << 20];
        byte[] snv = new byte[1 << 20];
        int n = 0, line = 0;
        try (BufferedReader br = gwasio.GwasFiles.open(j.gwasFile)) {
            String[] h = br.readLine().split("\t", -1);
            int iChr = col(h, j.colChr), iPos = col(h, j.colPos), iEa = col(h, j.colEa), iNea = col(h, j.colNea);
            int need = Math.max(Math.max(iChr, iPos), Math.max(iEa, iNea));
            String l;
            while ((l = br.readLine()) != null) {
                int idx = line++;
                String[] f = l.split("\t", need + 2);
                int c = f.length > need ? chrCode(f[iChr]) : 0;
                long pos = -1;
                if (c > 0) try { pos = Long.parseLong(f[iPos].trim()); } catch (NumberFormatException ignored) {}
                if (c == 0 || pos <= 0 || pos >= (1L << 28)) { j.unparsed++; continue; }
                if (n == keys.length) { keys = Arrays.copyOf(keys, n * 2); snv = Arrays.copyOf(snv, n * 2); }
                keys[n] = ((long) c << 58) | (pos << 30) | idx;
                String ea = f[iEa].trim(), nea = f[iNea].trim();
                int a = ea.length() == 1 ? base(ea.charAt(0)) : 0, b = nea.length() == 1 ? base(nea.charAt(0)) : 0;
                if (a > 0 && b > 0) snv[n] = (byte) (a << 4 | b);
                else j.other.put(idx, new String[]{ea.toUpperCase(), nea.toUpperCase()});
                n++;
            }
        }
        j.lines = line;
        // sort the keys and carry each line's allele code along (the code is looked up by line afterwards)
        byte[] byLine = new byte[line];
        for (int i = 0; i < n; i++) byLine[(int) (keys[i] & ((1L << 30) - 1))] = snv[i];
        j.keys = Arrays.copyOf(keys, n);
        Arrays.sort(j.keys);
        j.snv = byLine;
        j.rs = new int[line];
        j.how = new byte[line];
        j.build = new short[line];
    }

    static final String CODE = "?ACGT";

    /** Offers one dbSNP record to every line of the job at that position. */
    static void offer(Job j, int c, int pos, int rsNum, String ref, String alt, int bld) {
        long lo = ((long) c << 58) | ((long) pos << 30);
        int i = Arrays.binarySearch(j.keys, lo);
        if (i < 0) i = -i - 1;
        for (; i < j.keys.length && (j.keys[i] >>> 30) == (lo >>> 30); i++) {
            int line = (int) (j.keys[i] & ((1L << 30) - 1));
            String ea, nea;
            byte code = j.snv[line];
            if (code != 0) { ea = String.valueOf(CODE.charAt(code >> 4)); nea = String.valueOf(CODE.charAt(code & 15)); }
            else { String[] o = j.other.get(line); if (o == null) continue; ea = o[0]; nea = o[1]; }
            int kind = 0;
            if (nea.equals(ref) && inAlt(alt, ea)) kind = 2;
            else if (ea.equals(ref) && inAlt(alt, nea)) kind = 1;
            if (kind == 0) { if (j.how[line] == 0 && j.rs[line] == 0) j.rs[line] = -1; continue; }   // position seen, alleles differ
            if (kind > j.how[line] || (kind == j.how[line] && bld > j.build[line])) {
                j.how[line] = (byte) kind; j.rs[line] = rsNum; j.build[line] = (short) bld;
            }
        }
    }

    static boolean inAlt(String alt, String a) {
        int s = 0;
        while (s <= alt.length()) {
            int e = alt.indexOf(',', s);
            if (e < 0) e = alt.length();
            if (alt.regionMatches(s, a, 0, a.length()) && e - s == a.length()) return true;
            s = e + 1;
        }
        return false;
    }

    /** Runs the recovery for all jobs with one pass over dbSNP. */
    public static void run(String dbsnpVcfGz, List<Job> jobs, PrintStream log) throws IOException {
        for (Job j : jobs) {
            long t = System.currentTimeMillis();
            load(j);
            log.printf("[GenomeRsid] %s: %,d SNPs read (%,d unparsed) in %.0f s%n", new File(j.gwasFile).getName(),
                j.lines, j.unparsed, (System.currentTimeMillis() - t) / 1000.0);
        }
        // positions wanted by any job, per chromosome
        BitSet[] want = new BitSet[CHROMS.length];
        for (Job j : jobs)
            for (long k : j.keys) {
                int c = (int) (k >>> 58), pos = (int) ((k >>> 30) & ((1L << 28) - 1));
                if (want[c] == null) want[c] = new BitSet();
                want[c].set(pos);
            }
        long t = System.currentTimeMillis(), records = 0, hits = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(dbsnpVcfGz), 1 << 16), StandardCharsets.US_ASCII), 1 << 20)) {
            String l;
            int lastC = -1; String lastChr = null;
            while ((l = br.readLine()) != null) {
                if (l.isEmpty() || l.charAt(0) == '#') continue;
                records++;
                int t1 = l.indexOf('\t'), t2 = l.indexOf('\t', t1 + 1);
                String chr = l.substring(0, t1);
                int c = chr.equals(lastChr) ? lastC : chrCode(chr);
                lastChr = chr; lastC = c;
                if (c == 0 || want[c] == null) continue;
                int pos = Integer.parseInt(l, t1 + 1, t2, 10);
                if (!want[c].get(pos)) continue;
                hits++;
                int t3 = l.indexOf('\t', t2 + 1), t4 = l.indexOf('\t', t3 + 1), t5 = l.indexOf('\t', t4 + 1);
                String id = l.substring(t2 + 1, t3);
                if (!id.startsWith("rs")) continue;
                int rsNum;
                try { rsNum = Integer.parseInt(id, 2, id.length(), 10); } catch (NumberFormatException e) { continue; }
                String ref = l.substring(t3 + 1, t4).toUpperCase(), alt = l.substring(t4 + 1, t5).toUpperCase();
                int b = l.indexOf("dbSNPBuildID=", t5), bld = 0;
                if (b > 0) { int e = b + 13; while (e < l.length() && Character.isDigit(l.charAt(e))) e++; bld = Integer.parseInt(l, b + 13, e, 10); }
                for (Job j : jobs) offer(j, c, pos, rsNum, ref, alt, bld);
                if (records % 100_000_000 == 0)
                    log.printf("[GenomeRsid] dbSNP %,dM records, chr %s, %.0f s%n", records / 1_000_000, chr, (System.currentTimeMillis() - t) / 1000.0);
            }
        }
        log.printf("[GenomeRsid] dbSNP: %,d records, %,d at a wanted position, %.0f s%n", records, hits, (System.currentTimeMillis() - t) / 1000.0);
        for (Job j : jobs) write(j, log);
    }

    static void write(Job j, PrintStream log) throws IOException {
        try (BufferedReader br = gwasio.GwasFiles.open(j.gwasFile);
             PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                 new FileOutputStream(j.outputFile), StandardCharsets.UTF_8), 1 << 20))) {
            out.print(br.readLine()); out.print("\trecovered_rsid\n");
            String l; int line = 0;
            while ((l = br.readLine()) != null) {
                int i = line++;
                out.print(l); out.print('\t');
                if (i < j.rs.length) {
                    if (j.how[i] == 2) { out.print("rs"); out.print(j.rs[i]); j.forward++; }
                    else if (j.how[i] == 1) { out.print("rs"); out.print(j.rs[i]); j.reverse++; }
                    else if (j.rs[i] == -1) j.posOnly++;
                    else j.noPos++;
                }
                out.print('\n');
            }
        }
        log.printf("[GenomeRsid] %s -> %s: %.1f%% with an rsID (forward %,d, reverse %,d; position only, alleles differ %,d; "
            + "position not in dbSNP %,d)%n", new File(j.gwasFile).getName(), new File(j.outputFile).getName(), j.rate(),
            j.forward, j.reverse, j.posOnly, j.noPos);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 6 || (args.length - 1) % 5 != 0) {
            System.err.println("Usage: GenomeRsidRecovery <dbsnp.vcf.gz> <gwas> <col chr> <col pos> <col ea> <col nea> [<gwas> ...]");
            System.exit(2);
        }
        List<Job> jobs = new ArrayList<>();
        for (int i = 1; i < args.length; i += 5) jobs.add(new Job(args[i], args[i + 1], args[i + 2], args[i + 3], args[i + 4]));
        run(args[0], jobs, System.out);
    }
}
