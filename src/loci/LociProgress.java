package loci;

public class LociProgress {
    public volatile String currentStep = "Initializing";
    public volatile int stepIndex      = 0;
    public volatile int totalSteps     = 6;
    public volatile boolean done       = false;
    public volatile boolean cancelled  = false;
    public volatile String error       = null;
    /** Non-fatal notes for the user (e.g. a chromosome identified without LD clumping); null when none. */
    public volatile String warning     = null;

    public volatile int candidateSnps     = 0;
    public volatile int seedSnps          = 0;
    public volatile int lociFound         = 0;
    public volatile int totalChromosomes  = 0;
    public volatile int currentChromosome = 0;

    public synchronized void addWarning(String w) { warning = warning == null ? w : warning + " " + w; }

    public int pct() {
        if (done) return 100;
        double phaseWeight = 100.0 / totalSteps;
        double base = Math.max(0, stepIndex - 1) * phaseWeight;
        double within = totalChromosomes > 0 ? currentChromosome * phaseWeight / totalChromosomes : 0;
        return Math.min(99, (int)(base + within));
    }

    public String toJson() {
        return String.format(
            "{\"pct\":%d,\"current_step\":\"%s\",\"step_index\":%d,\"total_steps\":%d," +
            "\"done\":%s,\"error\":%s,\"warning\":%s," +
            "\"candidate_snps\":%d,\"seed_snps\":%d,\"loci_found\":%d," +
            "\"total_chromosomes\":%d,\"current_chromosome\":%d}",
            pct(), esc(currentStep), stepIndex, totalSteps,
            done, error != null ? "\"" + esc(error) + "\"" : "null", warning != null ? "\"" + esc(warning) + "\"" : "null",
            candidateSnps, seedSnps, lociFound,
            totalChromosomes, currentChromosome);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
