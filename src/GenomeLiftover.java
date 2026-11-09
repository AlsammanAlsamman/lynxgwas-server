import htsjdk.samtools.liftover.LiftOver;
import htsjdk.samtools.util.Interval;
import java.io.File;

/**
 * Coordinate conversion between GRCh37 (hg19) and GRCh38 (hg38), both ways, with the standard UCSC chain
 * files in resources/ (installed by "setup"). Used where coordinates of different builds meet: the GWAS
 * Catalog (GRCh38 only) for GRCh37 projects, search across builds, and cross-dataset runs whose datasets
 * are on different builds (the GRCh37 ones are lifted to GRCh38, and the user is told).
 *
 * Uses HTSJDK's LiftOver. Validated against two independently-known anchors before wiring this in:
 * rs429358's well-documented hg19 position (chr19:45,411,941) lifts to exactly chr19:44,908,684, the same
 * hg38 position Open Targets reports, and rs2238057's known hg38 position round-trips through
 * hg38->hg19->hg38 back to the exact original coordinate.
 *
 * L2G/enhancer-to-gene lookups do NOT need this: they resolve by rsID through Open Targets' own
 * search(), which is build-agnostic.
 */
public class GenomeLiftover {

    static {
        // htsjdk logs an INFO line for every interval that does not map; callers count and report those themselves
        htsjdk.samtools.util.Log.setGlobalLogLevel(htsjdk.samtools.util.Log.LogLevel.WARNING);
    }

    private static final String CHAIN_37_TO_38 = "resources/hg19ToHg38.over.chain";
    private static final String CHAIN_38_TO_37 = "resources/hg38ToHg19.over.chain";
    private static final Chain TO_38 = new Chain(CHAIN_37_TO_38, "hg19ToHg38", "GRCh37->GRCh38");
    private static final Chain TO_37 = new Chain(CHAIN_38_TO_37, "hg38ToHg19", "GRCh38->GRCh37");

    /** One chain file, loaded on first use. */
    private static final class Chain {
        final String path, name, label;
        volatile LiftOver lo;
        volatile String error;

        Chain(String path, String name, String label) { this.path = path; this.name = name; this.label = label; }

        LiftOver get() {
            if (lo != null || error != null) return lo;
            synchronized (this) {
                if (lo != null || error != null) return lo;
                File f = RefBuild.resolve(path);
                if (!f.isFile()) {
                    error = name + " chain file not found at " + path + ": run \"setup\" (or download it from "
                        + "hgdownload.soe.ucsc.edu and unzip it there).";
                    System.err.println("[GenomeLiftover] " + error);
                    return null;
                }
                try {
                    lo = new LiftOver(f);
                } catch (Exception e) {
                    error = "Failed to load liftover chain " + path + ": " + e.getMessage();
                    System.err.println("[GenomeLiftover] " + error);
                }
            }
            return lo;
        }
    }

    public static class Result {
        public boolean ok;
        public String chr;
        public long start, end;
        public String error;
    }

    /** True for a GRCh38 build string ("GRCh38", "hg38", "GRCh38 (lifted ...)"). Anything else is GRCh37, the default. */
    public static boolean isGrch38(String genomeBuild) {
        if (genomeBuild == null) return false;
        String b = genomeBuild.trim().toLowerCase(java.util.Locale.ROOT);
        return b.startsWith("grch38") || b.startsWith("hg38");
    }

    /** "GRCh38" or "GRCh37": the build a build string means, GRCh37 when unset. */
    public static String normalize(String genomeBuild) { return isGrch38(genomeBuild) ? "GRCh38" : "GRCh37"; }

    /** True if this build's coordinates need converting before comparing against the (GRCh38-only) GWAS Catalog. */
    public static boolean needsLiftover(String genomeBuild) { return !isGrch38(genomeBuild); }

    /** Converts a region from GRCh37 to GRCh38. result.ok is false (with .error set) if the chain isn't
     *  loaded or the region has no confident mapping: callers treat that as "cannot verify", never fall
     *  back to the un-lifted coordinates. */
    public static Result toGRCh38(String chr, long start, long end) { return lift(TO_38, chr, start, end); }

    /** Converts a region from GRCh38 to GRCh37 (same contract as toGRCh38). */
    public static Result toGRCh37(String chr, long start, long end) { return lift(TO_37, chr, start, end); }

    /** Converts a region from one build to the other (no change when they are the same build). */
    public static Result convert(String fromBuild, String toBuild, String chr, long start, long end) {
        if (isGrch38(fromBuild) == isGrch38(toBuild)) {
            Result r = new Result();
            r.ok = true; r.chr = chr.replaceFirst("(?i)^chr", ""); r.start = start; r.end = end;
            return r;
        }
        return isGrch38(toBuild) ? toGRCh38(chr, start, end) : toGRCh37(chr, start, end);
    }

    /** One position from one build to the other, or -1 when it does not map to the same chromosome. */
    public static long position(String fromBuild, String toBuild, String chr, long pos) {
        Result r = convert(fromBuild, toBuild, chr, pos, pos);
        return r.ok && r.chr.equalsIgnoreCase(chr.replaceFirst("(?i)^chr", "")) ? r.start : -1;
    }

    private static Result lift(Chain c, String chr, long start, long end) {
        Result r = new Result();
        LiftOver lo = c.get();
        if (lo == null) {
            r.error = c.error;
            return r;
        }
        String ucscChr = chr.regionMatches(true, 0, "chr", 0, 3) ? chr : "chr" + chr;
        Interval lifted;
        try {
            // regions: htsjdk's default 95% of bases must map; a single position must map exactly
            lifted = lo.liftOver(new Interval(ucscChr, (int) start, (int) end), start == end ? 1.0 : LiftOver.DEFAULT_LIFTOVER_MINMATCH);
        } catch (Exception e) {
            r.error = "Liftover threw: " + e.getMessage();
            return r;
        }
        if (lifted == null) {
            r.error = "No confident " + c.label + " mapping for " + chr + ":" + start + "-" + end;
            return r;
        }
        r.ok = true;
        r.chr = lifted.getContig().replaceFirst("(?i)^chr", "");
        r.start = lifted.getStart();
        r.end = lifted.getEnd();
        return r;
    }
}
