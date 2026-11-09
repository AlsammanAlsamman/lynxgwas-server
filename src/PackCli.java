import java.io.File;
import java.nio.file.*;
import java.util.*;

/**
 * Terminal commands for project packs, the same in the desktop and the server edition. Run them from the
 * app folder (the one holding projects/), with the app stopped or running:
 *
 *   import-pack <pack.zip>        install a pack's projects and cross-dataset runs (existing ones are kept)
 *   import-pack zenodo            download the article data from Zenodo (config/example_data.json) and install it
 *   export-pack [--with-gwas] [--with-runs] [--out <file.zip>] <project-id> [<project-id> ...]
 *
 * On the server, projects installed here belong to no workspace, so they are public, read-only datasets.
 */
public class PackCli {

    public static boolean handles(String[] args) {
        return args.length > 0 && (args[0].equals("import-pack") || args[0].equals("export-pack"));
    }

    public static int run(String[] args) {
        try {
            return args[0].equals("import-pack") ? importPack(args) : exportPack(args);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    static int importPack(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: import-pack <pack.zip> | import-pack zenodo");
            return 2;
        }
        String src = args[1].equals("zenodo") ? null : new File(args[1]).getAbsolutePath();
        ExampleDataInstaller inst = new ExampleDataInstaller(null);
        String refused = inst.start(src);
        if (refused != null) { System.err.println(refused); return 1; }
        ExampleDataInstaller.Progress p = waitFor(inst::job);
        if (p.error != null) { System.err.println("Import failed: " + p.error); return 1; }
        System.out.println("Installed: " + (p.installed.isEmpty() ? "nothing new" : String.join(", ", p.installed)));
        if (!p.skipped.isEmpty()) System.out.println("Already present, kept as they were: " + String.join(", ", p.skipped));
        if (!p.message.isEmpty()) System.out.println(p.message);
        System.out.println("Restart a running LYNXgwas so it lists the new projects.");
        return 0;
    }

    static int exportPack(String[] args) throws Exception {
        boolean gwas = false, runs = false;
        String out = null;
        List<String> ids = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--with-gwas")) gwas = true;
            else if (args[i].equals("--with-runs")) runs = true;
            else if (args[i].equals("--out") && i + 1 < args.length) out = args[++i];
            else ids.add(args[i]);
        }
        if (ids.isEmpty()) {
            System.err.println("Usage: export-pack [--with-gwas] [--with-runs] [--out <file.zip>] <project-id> [...]");
            return 2;
        }
        ProjectPackExporter ex = new ProjectPackExporter();
        String refused = ex.start(ids, gwas, runs);
        if (refused != null) { System.err.println(refused); return 1; }
        ExampleDataInstaller.Progress p = waitFor(ex::job);
        if (p.error != null) { System.err.println("Export failed: " + p.error); return 1; }
        File made = ex.file(ex.lastFile());
        if (out != null) {
            Path dest = Paths.get(out).toAbsolutePath();
            if (dest.getParent() != null) Files.createDirectories(dest.getParent());
            Files.move(made.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
            made = dest.toFile();
        }
        System.out.println("Wrote " + made.getAbsolutePath() + " (" + p.message + ")");
        return 0;
    }

    /** Prints the job's phase and percentage as they change, until it is done. */
    private static ExampleDataInstaller.Progress waitFor(java.util.function.Supplier<ExampleDataInstaller.Progress> job) throws InterruptedException {
        String last = "";
        while (true) {
            ExampleDataInstaller.Progress p = job.get();
            if (p != null) {
                String line = p.phase + (p.bytesTotal > 0 ? String.format(Locale.ROOT, " %d%%", 100 * p.bytesDone / Math.max(1, p.bytesTotal)) : "");
                if (!line.equals(last)) { System.out.println("  " + line); last = line; }
                if (p.done) return p;
            }
            Thread.sleep(2000);
        }
    }
}
