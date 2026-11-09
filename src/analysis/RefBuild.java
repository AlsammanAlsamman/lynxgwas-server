import java.io.File;

/**
 * Where the build-specific reference data of a project lives (see config/required_data.json and "setup").
 * GRCh37 is the default; GRCh38 has its own files, some lifted from the GRCh37 ones at setup:
 *
 *   LD scores (HapMap3, EUR)   resources/ldsc/eur_w_ld_chr          resources/ldsc/eur_w_ld_chr_grch38 (lifted)
 *   CADD v1.6 SNV PHRED        resources/cadd/gnomad...r2.1.1...    resources/cadd/gnomad.genomes.r3.0.snv.tsv.gz
 *   Roadmap histone peaks      regulatory_data/                     regulatory_data/GRCh38/ (lifted)
 *
 * Environment variables override them: LYNXGWAS_LDSC, LYNXGWAS_CADD (GRCh37) and LYNXGWAS_LDSC_GRCH38,
 * LYNXGWAS_CADD_GRCH38.
 */
public final class RefBuild {
    private RefBuild() {}

    public static boolean is38(String genomeBuild) { return GenomeLiftover.isGrch38(genomeBuild); }

    public static File ldscDir(String genomeBuild) {
        boolean b38 = is38(genomeBuild);
        String env = System.getenv(b38 ? "LYNXGWAS_LDSC_GRCH38" : "LYNXGWAS_LDSC");
        if (env != null && !env.isEmpty()) return new File(env);
        return new File(SusieAdapter.appRoot(), b38 ? "resources/ldsc/eur_w_ld_chr_grch38" : "resources/ldsc/eur_w_ld_chr");
    }

    public static File cadd(String genomeBuild) {
        boolean b38 = is38(genomeBuild);
        String env = System.getenv(b38 ? "LYNXGWAS_CADD_GRCH38" : "LYNXGWAS_CADD");
        if (env != null && !env.isEmpty()) return new File(env);
        return new File(SusieAdapter.appRoot(), b38 ? "resources/cadd/gnomad.genomes.r3.0.snv.tsv.gz"
                                                    : "resources/cadd/gnomad.genomes.r2.1.1.snv.tsv.gz");
    }

    /** Folder of the Roadmap peaks ({EID}/{EID}-{mark}.narrowPeak.gz): in the working folder, else the app folder. */
    public static String regulatoryRoot(String genomeBuild) {
        return resolve(is38(genomeBuild) ? "regulatory_data/GRCh38" : "regulatory_data").getPath();
    }

    /**
     * A reference file or folder given relative to the app: the working folder's copy when there is one
     * (a workspace, or the server's app folder), else the one next to the app's classes. The desktop runs
     * in a workspace folder while "setup" fills the app folder, so both places are looked at.
     */
    public static File resolve(String relative) {
        File here = new File(relative);
        if (here.exists()) return here;
        File app = new File(SusieAdapter.appRoot(), relative);
        return app.exists() ? app : here;
    }

    /** "GRCh38" or "GRCh37". */
    public static String name(String genomeBuild) { return GenomeLiftover.normalize(genomeBuild); }
}
