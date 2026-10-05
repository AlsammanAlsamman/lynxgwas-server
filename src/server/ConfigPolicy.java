import java.io.File;
import java.util.regex.Pattern;

/**
 * What a client may put in a project's config.properties on the server.
 *
 * File locations are never taken from the client: the GWAS file is whatever was uploaded into the
 * project, the loci file is whatever the server's own locus identification wrote, and the
 * reference panel / GFF come from server.properties. Every text value is checked against an
 * allowlist (no newlines, backslashes or control characters, which would otherwise inject extra
 * lines into the properties file) and numbers are clamped to sane ranges so one project can't
 * monopolise the machine.
 */
public final class ConfigPolicy {
    private ConfigPolicy() {}

    private static final Pattern COLUMN = Pattern.compile("^[A-Za-z0-9_.#:() \\-]{0,64}$");
    private static final Pattern LABEL  = Pattern.compile("^[\\p{L}\\p{N} _.,:;()'/+\\-]{0,80}$");

    /**
     * Applies the client's JSON onto {@code previous} (may be null for a new project) and returns
     * the resulting config, or throws IllegalArgumentException with a user-facing reason.
     */
    public static Config apply(String json, Config previous, File projectDir, ServerConfig sc) {
        Config c = new Config();
        if (previous != null) copyUserFields(previous, c);
        c.applyJson(json);

        // Server-controlled locations: restore, whatever the client sent
        c.gwasFile     = previous != null ? previous.gwasFile : "";
        c.lociFile     = previous != null && insideProject(previous.lociFile, projectDir) ? previous.lociFile : "";
        c.topSnpFile   = "";
        c.gff3File     = sc.gff3File;
        c.refPanelPath = sc.refPanelPath;
        c.refPanelPopulation = sc.refPanelPopulation;
        c.ldEnabled    = !sc.refPanelPath.isEmpty() && c.ldEnabled;

        for (String[] col : new String[][]{
                {"col.chr", c.colChr}, {"col.pos", c.colPos}, {"col.pvalue", c.colPvalue}, {"col.rsid", c.colRsid},
                {"col.varid", c.colVarid}, {"col.ea", c.colEa}, {"col.nea", c.colNea}, {"col.beta", c.colBeta},
                {"col.or", c.colOr}, {"col.se", c.colSe}, {"col.n", c.colN}, {"col.maf", c.colMaf}, {"col.info", c.colInfo}})
            if (!COLUMN.matcher(col[1]).matches()) throw new IllegalArgumentException("Invalid column name for " + col[0]);
        if (c.colChr.isEmpty() || c.colPos.isEmpty() || c.colPvalue.isEmpty())
            throw new IllegalArgumentException("Chromosome, position and p-value columns are required");

        oneOf("trait.type", c.traitType, "", "quantitative", "binary");
        oneOf("effect.type", c.effectType, "", "beta", "OR", "logOR");
        oneOf("genome.build", c.genomeBuild, "GRCh37", "GRCh38");
        if (!LABEL.matcher(c.ancestry).matches()) throw new IllegalArgumentException("Invalid ancestry label");
        if (!LABEL.matcher(c.diseaseName).matches()) throw new IllegalArgumentException("Invalid disease name");

        c.locusPadding       = clamp(c.locusPadding, 0, 2_000_000);
        c.ldTriangleBoundary = (int) clamp(c.ldTriangleBoundary, 10, 500);
        c.ldR2Threshold      = Math.max(0, Math.min(1, c.ldR2Threshold));
        c.ldParallelJobs     = (int) clamp(c.ldParallelJobs, 1, 2);
        c.threads            = (int) clamp(c.threads, 1, 4);
        c.maxSnpsPerLocus    = (int) clamp(c.maxSnpsPerLocus, 100, 20_000);
        c.splitLdThreshold   = Math.max(0, Math.min(1, c.splitLdThreshold));
        c.splitMinDistBp     = clamp(c.splitMinDistBp, 0, 10_000_000);
        c.sampleN            = (int) clamp(c.sampleN, 0, 100_000_000);
        c.nCases             = (int) clamp(c.nCases, 0, 100_000_000);
        c.nControls          = (int) clamp(c.nControls, 0, 100_000_000);
        return c;
    }

    /** Config as JSON for the browser, with server file locations replaced by neutral labels. */
    public static String redactedJson(Config c) {
        Config r = new Config();
        copyUserFields(c, r);
        r.gwasFile = c.gwasFile.isEmpty() ? "" : new File(c.gwasFile).getName();
        r.lociFile = c.lociFile.isEmpty() ? "" : "loci.txt";
        r.gff3File = c.gff3File.isEmpty() ? "" : "server annotation";
        r.refPanelPath = c.refPanelPath.isEmpty() ? "" : "server reference panel";
        r.refPanelPopulation = c.refPanelPopulation;
        r.topSnpFile = "";
        return r.toJson();
    }

    private static void copyUserFields(Config from, Config to) {
        to.gwasFile = from.gwasFile; to.lociFile = from.lociFile; to.gff3File = from.gff3File;
        to.refPanelPath = from.refPanelPath; to.refPanelPopulation = from.refPanelPopulation;
        to.colChr = from.colChr; to.colPos = from.colPos; to.colPvalue = from.colPvalue; to.colRsid = from.colRsid;
        to.colVarid = from.colVarid; to.colEa = from.colEa; to.colNea = from.colNea; to.colBeta = from.colBeta;
        to.colOr = from.colOr; to.colSe = from.colSe; to.colN = from.colN; to.colMaf = from.colMaf; to.colInfo = from.colInfo;
        to.locusPadding = from.locusPadding; to.ldEnabled = from.ldEnabled; to.ldTriangleBoundary = from.ldTriangleBoundary;
        to.ldR2Threshold = from.ldR2Threshold; to.ldParallelJobs = from.ldParallelJobs; to.threads = from.threads;
        to.maxSnpsPerLocus = from.maxSnpsPerLocus; to.splitLdThreshold = from.splitLdThreshold; to.splitMinDistBp = from.splitMinDistBp;
        to.sampleN = from.sampleN; to.nCases = from.nCases; to.nControls = from.nControls; to.traitType = from.traitType;
        to.effectType = from.effectType; to.genomeBuild = from.genomeBuild; to.ancestry = from.ancestry; to.diseaseName = from.diseaseName;
        to.prevalence = from.prevalence;
    }

    static boolean insideProject(String path, File projectDir) {
        if (path == null || path.isEmpty()) return false;
        try {
            return new File(path).getCanonicalFile().toPath().startsWith(projectDir.getCanonicalFile().toPath());
        } catch (Exception e) { return false; }
    }

    private static void oneOf(String key, String v, String... allowed) {
        for (String a : allowed) if (a.equals(v)) return;
        throw new IllegalArgumentException("Invalid value for " + key);
    }

    private static long clamp(long v, long lo, long hi) { return Math.max(lo, Math.min(hi, v)); }
}
