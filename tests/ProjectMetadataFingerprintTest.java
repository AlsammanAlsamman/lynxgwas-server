import java.io.*;
import java.nio.file.*;

/**
 * Core-input fingerprint v2: dataset metadata (sample size, prevalence, disease name...) must not mark a
 * project stale, while anything that changes the core outputs must; projects stamped with the legacy
 * whole-config fingerprint migrate silently when nothing changed.
 */
public class ProjectMetadataFingerprintTest {

    public static void main(String[] args) throws Exception {
        int failures = 0;
        File dir = Files.createTempDirectory("fp-test").toFile();
        File gwas = new File(dir, "gwas.tsv");
        Files.write(gwas.toPath(), "chr\tpos\tp\n1\t100\t0.5\n".getBytes());
        File loci = new File(dir, "loci.txt");
        Files.write(loci.toPath(), "1\t50\t150\n".getBytes());
        String base = "gwas.file=" + gwas.getAbsolutePath().replace('\\', '/') + "\nloci.file=" + loci.getAbsolutePath().replace('\\', '/')
            + "\ngff3.file=none.gff3\ncol.chr=chr\ncol.pos=pos\ncol.pvalue=p\nsample.n=1000\nn.cases=400\nn.controls=600\n";
        writeConfig(dir, base);
        Config c = Config.loadFromProject(dir.getAbsolutePath());
        String v2 = ProjectMetadata.computeCoreInputFingerprint(c, dir.getAbsolutePath());
        failures += check("v2 fingerprint is prefixed", v2.startsWith("v2:"));

        // Metadata-only edits keep the fingerprint
        writeConfig(dir, base.replace("sample.n=1000", "sample.n=2000") + "trait.prevalence=0.01\ndisease.name=Test\nthreads=8\n");
        Config c2 = Config.loadFromProject(dir.getAbsolutePath());
        failures += check("sample size, prevalence, disease name and threads do not change it",
            v2.equals(ProjectMetadata.computeCoreInputFingerprint(c2, dir.getAbsolutePath())));
        // ... but the legacy fingerprint did change, which is the bug being fixed
        writeConfig(dir, base);
        String legacy = ProjectMetadata.computeCoreInputFingerprintLegacy(Config.loadFromProject(dir.getAbsolutePath()), dir.getAbsolutePath());
        writeConfig(dir, base + "trait.prevalence=0.01\n");
        failures += check("(the legacy fingerprint changed on a metadata edit)",
            !legacy.equals(ProjectMetadata.computeCoreInputFingerprintLegacy(Config.loadFromProject(dir.getAbsolutePath()), dir.getAbsolutePath())));

        // Core changes still mark the project stale
        writeConfig(dir, base.replace("col.chr=chr", "col.chr=CHROM"));
        failures += check("a column mapping change changes it",
            !v2.equals(ProjectMetadata.computeCoreInputFingerprint(Config.loadFromProject(dir.getAbsolutePath()), dir.getAbsolutePath())));
        writeConfig(dir, base);
        Files.write(gwas.toPath(), "chr\tpos\tp\n1\t100\t0.4\n".getBytes());
        failures += check("a GWAS file content change changes it",
            !v2.equals(ProjectMetadata.computeCoreInputFingerprint(Config.loadFromProject(dir.getAbsolutePath()), dir.getAbsolutePath())));
        Files.write(gwas.toPath(), "chr\tpos\tp\n1\t100\t0.5\n".getBytes());

        // Legacy-stamped project with nothing changed: not stale, and re-stamped with v2
        Config c3 = Config.loadFromProject(dir.getAbsolutePath());
        ProjectMetadata pm = new ProjectMetadata();
        pm.id = "t"; pm.pipelineVersion = Config.PIPELINE_VERSION;
        pm.coreInputFingerprint = ProjectMetadata.computeCoreInputFingerprintLegacy(c3, dir.getAbsolutePath());
        pm.annotationFingerprint = ProjectMetadata.computeAnnotationFingerprint(new File(dir, "annotations.yaml").getAbsolutePath());
        pm.save(dir.getAbsolutePath());
        failures += check("legacy-stamped project is not stale",
            ProjectMetadata.checkStaleness(dir.getAbsolutePath(), c3) == ProjectMetadata.StaleReason.NOT_STALE);
        failures += check("legacy-stamped project is re-stamped with v2",
            ProjectMetadata.load(dir.getAbsolutePath()).coreInputFingerprint.startsWith("v2:"));
        // Legacy-stamped project whose GWAS changed: stale
        pm.coreInputFingerprint = ProjectMetadata.computeCoreInputFingerprintLegacy(c3, dir.getAbsolutePath());
        pm.save(dir.getAbsolutePath());
        Files.write(gwas.toPath(), "chr\tpos\tp\n1\t100\t0.3\n".getBytes());
        failures += check("legacy-stamped project with a changed GWAS file is stale",
            ProjectMetadata.checkStaleness(dir.getAbsolutePath(), Config.loadFromProject(dir.getAbsolutePath())) == ProjectMetadata.StaleReason.CORE_INPUT_CHANGED);

        if (failures > 0) { System.out.println("FAIL: " + failures + " test(s) failed"); System.exit(1); }
        System.out.println("PASS: all ProjectMetadata fingerprint tests passed");
    }

    private static void writeConfig(File dir, String s) throws IOException {
        Files.write(new File(dir, "config.properties").toPath(), s.getBytes());
    }

    private static int check(String label, boolean ok) {
        System.out.println((ok ? "PASS: " : "FAIL: ") + label);
        return ok ? 0 : 1;
    }
}
