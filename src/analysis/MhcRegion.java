/**
 * The one definition of the MHC exclusion window, used by every analysis and view.
 *
 * <p>The extended MHC on chr6, 25,000,000-34,000,000 (GRCh37), the window most GWAS pipelines exclude
 * because of its long-range LD. GRCh38 coordinates are the two GRCh37 ends lifted with UCSC liftOver
 * (hg19ToHg38.over.chain), not retyped. Its extreme LD breaks fine-mapping and colocalization, and a
 * locus there can look shared between unrelated diseases through LD alone, so the cross-dataset views
 * leave it out of their statistics as well.
 */
public final class MhcRegion {
    public static final long GRCH37_START = 25_000_000L, GRCH37_END = 34_000_000L;
    public static final long GRCH38_START = 24_999_772L, GRCH38_END = 34_032_223L;

    private MhcRegion() {}

    public static long start(String genomeBuild) { return isGrch38(genomeBuild) ? GRCH38_START : GRCH37_START; }
    public static long end(String genomeBuild)   { return isGrch38(genomeBuild) ? GRCH38_END : GRCH37_END; }

    /** True when [start, end] on chr overlaps the window; chr may carry a "chr" prefix. */
    public static boolean overlaps(String chr, long start, long end, String genomeBuild) {
        return isChr6(chr) && start <= end(genomeBuild) && end >= start(genomeBuild);
    }

    public static boolean contains(String chr, long pos, String genomeBuild) {
        return overlaps(chr, pos, pos, genomeBuild);
    }

    /** Human-readable window for logs and notes, e.g. "chr6:25,000,000-34,000,000 (GRCh37)". */
    public static String label(String genomeBuild) {
        String b = isGrch38(genomeBuild) ? "GRCh38" : "GRCh37";
        return String.format(java.util.Locale.ROOT, "chr6:%,d-%,d (%s)", start(b), end(b), b);
    }

    private static boolean isGrch38(String b) { return "GRCh38".equalsIgnoreCase(b == null ? "" : b.trim()); }

    private static boolean isChr6(String chr) {
        if (chr == null) return false;
        String c = chr.trim();
        if (c.regionMatches(true, 0, "chr", 0, 3)) c = c.substring(3);
        return c.equals("6");
    }
}
