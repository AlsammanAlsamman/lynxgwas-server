import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;

/**
 * Project packs for workspace users: export your own projects (and your own cross-dataset runs) as one
 * .zip to download, and import a pack (made here, by the desktop app, or from the article data) into your
 * workspace. The pack format is the desktop's (ProjectPackExporter / ExampleDataInstaller).
 *
 * An import never trusts the pack: entries are confined to a per-user staging folder, the unpacked size
 * is checked against the pack's declaration and the user's quota, every project gets a fresh private id,
 * every run a fresh id owned by the importer, and each project's config gets the server's own file
 * locations (a pack cannot point the server at files outside the project). Projects without their summary
 * statistics arrive as results only: they can be browsed, not re-run, until the user uploads the file.
 */
public class WorkspacePacks {

    private final ServerContext ctx;
    private final Map<String, ProjectPackExporter> exporters = new ConcurrentHashMap<>();
    private final Map<String, ExampleDataInstaller.Progress> imports = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<List<String>> onRunsImported = ids -> {};

    public WorkspacePacks(ServerContext ctx) { this.ctx = ctx; }

    /** Called with the ids of imported cross-dataset runs, so the running server lists them. */
    public void onRunsImported(java.util.function.Consumer<List<String>> c) { onRunsImported = c; }

    private File userDir(String kind, String userId) throws IOException {
        if (userId == null || !userId.matches("[A-Za-z0-9_-]{8,100}")) throw new IOException("bad workspace");
        return new File(ctx.cfg.dataDir(), kind + "/" + userId);
    }

    // ── Export ──────────────────────────────────────────────────────────────

    /** POST /api/my/pack-export {project_ids: [...], include_gwas, include_runs} */
    public void startExport(HttpExchange ex, Map<String, Object> b) throws IOException {
        String userId = SecurityGate.userId(ex);
        List<String> ids = new ArrayList<>();
        if (b.get("project_ids") instanceof List) for (Object o : MiniJson.asArray(b.get("project_ids"))) ids.add(String.valueOf(o));
        if (ids.isEmpty() || ids.size() > ctx.cfg.maxProjectsPerUser) { SecurityGate.deny(ex, 400, "Choose your projects to export."); return; }
        for (String id : ids) {
            if (ctx.registry.access(id, userId) != ProjectRegistry.Access.OWNER) {
                SecurityGate.deny(ex, 403, "You can export your own projects only."); return;
            }
        }
        File dir = userDir("exports", userId);
        ProjectPackExporter exp = exporters.computeIfAbsent(userId, u -> new ProjectPackExporter(dir,
            job -> u.equals(job.get("owner_id"))));                 // only the user's own cross-dataset runs travel
        ExampleDataInstaller.Progress running = exp.job();
        if (running != null && !running.done) { SecurityGate.deny(ex, 409, "An export is already running."); return; }
        FileSafety.deleteTree(dir);                                  // one export at a time is kept per workspace
        String refused = exp.start(ids, Boolean.TRUE.equals(b.get("include_gwas")), Boolean.TRUE.equals(b.get("include_runs")));
        if (refused != null) { SecurityGate.deny(ex, 400, refused); return; }
        ServerApi.json(ex, 202, "{\"ok\":true}");
    }

    /** GET /api/my/pack-export-progress: the job, and the file name once it is ready (no server paths). */
    public void exportProgress(HttpExchange ex) throws IOException {
        ProjectPackExporter exp = exporters.get(SecurityGate.userId(ex));
        ExampleDataInstaller.Progress p = exp == null ? null : exp.job();
        if (p == null) { ServerApi.json(ex, 200, "{\"phase\":\"idle\",\"done\":true}"); return; }
        String j = p.toJson();
        if (p.done && p.error == null && exp.lastFile() != null)
            j = j.substring(0, j.length() - 1) + ",\"file\":" + ServerApi.q(exp.lastFile()) + "}";
        ServerApi.json(ex, 200, j);
    }

    /** GET /api/my/pack-download?file=<name>: the workspace's finished export. */
    public void download(HttpExchange ex) throws IOException {
        String userId = SecurityGate.userId(ex);
        String q = ex.getRequestURI().getRawQuery(), name = null;
        if (q != null) for (String kv : q.split("&"))
            if (kv.startsWith("file=")) name = java.net.URLDecoder.decode(kv.substring(5), "UTF-8");
        File f = ProjectPackExporter.exported(userDir("exports", userId), name);
        if (f == null) { SecurityGate.deny(ex, 404, "No such export"); return; }
        ex.getResponseHeaders().set("Content-Type", "application/zip");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + f.getName() + "\"");
        ex.sendResponseHeaders(200, f.length());
        try (OutputStream os = ex.getResponseBody()) { Files.copy(f.toPath(), os); }
    }

    // ── Import ──────────────────────────────────────────────────────────────

    /** POST /api/upload/pack: the zip streams to the workspace's import folder, then installs in the background. */
    public void upload(HttpExchange ex) throws IOException {
        String userId = SecurityGate.userId(ex);
        ExampleDataInstaller.Progress running = imports.get(userId);
        if (running != null && !running.done) { SecurityGate.deny(ex, 409, "An import is already running."); return; }
        long room = (ctx.cfg.maxStoragePerUserMb << 20) - ctx.registry.storageUsedBy(userId);
        if (room <= 0) { SecurityGate.deny(ex, 413, "Your storage quota is full. Delete a project first."); return; }
        File dir = userDir("imports", userId);
        FileSafety.deleteTree(dir);
        Files.createDirectories(dir.toPath());
        File zip = new File(dir, "upload.zip");
        long limit = Math.min(room, ctx.cfg.maxUploadMb << 20), n = 0;
        try (InputStream in = new BufferedInputStream(ex.getRequestBody(), 1 << 16);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(zip), 1 << 16)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) {
                n += r;
                if (n > limit) throw new IOException("the pack is larger than your remaining storage");
                out.write(buf, 0, r);
            }
        } catch (IOException e) {
            FileSafety.deleteTree(dir);
            SecurityGate.deny(ex, 413, "Upload rejected: " + e.getMessage()); return;
        }
        ExampleDataInstaller.Progress p = new ExampleDataInstaller.Progress();
        p.kind = "import";
        imports.put(userId, p);
        String refused = ctx.jobs.start(userId, "pack-import", () -> install(userId, zip, p));
        if (refused != null) { imports.remove(userId); FileSafety.deleteTree(dir); SecurityGate.deny(ex, 429, refused); return; }
        ServerApi.json(ex, 202, "{\"ok\":true}");
    }

    /** GET /api/my/pack-import-progress */
    public void importProgress(HttpExchange ex) throws IOException {
        ExampleDataInstaller.Progress p = imports.get(SecurityGate.userId(ex));
        ServerApi.json(ex, 200, p == null ? "{\"phase\":\"idle\",\"done\":true}" : p.toJson());
    }

    void install(String userId, File zip, ExampleDataInstaller.Progress p) {
        List<String> createdProjects = new ArrayList<>();
        List<File> movedRuns = new ArrayList<>();
        Path staging = zip.toPath().resolveSibling("staging");
        try {
            Map<String, Object> pack;
            List<String> oldIds = new ArrayList<>();
            try (ZipFile zf = new ZipFile(zip)) {
                pack = ExampleDataInstaller.readPack(zf);
                if (pack.get("projects") instanceof List) for (Object o : MiniJson.asArray(pack.get("projects"))) oldIds.add(String.valueOf(o));
                if (oldIds.isEmpty()) throw new IOException("The pack holds no projects.");
                for (String id : oldIds) if (!id.matches("[A-Za-z0-9._-]{1,100}")) throw new IOException("Bad project id in the pack: " + id);
                int have = ctx.registry.ownedBy(userId).size();
                if (have + oldIds.size() > ctx.cfg.maxProjectsPerUser)
                    throw new IOException("The pack holds " + oldIds.size() + " project(s) and you have " + have
                        + "; a workspace holds at most " + ctx.cfg.maxProjectsPerUser + ". Delete projects first, or import a smaller pack.");
                long declared = ExampleDataInstaller.num(pack.get("uncompressed_bytes"));
                long room = (ctx.cfg.maxStoragePerUserMb << 20) - ctx.registry.storageUsedBy(userId) - zip.length();
                if (declared <= 0 || declared > room)
                    throw new IOException(String.format(Locale.ROOT, "The pack unpacks to %.1f GB; your workspace has %.1f GB left.",
                        declared / 1e9, Math.max(0, room) / 1e9));
                ExampleDataInstaller.extractTo(p, zf, declared, staging);
            }
            List<Object> rw = pack.get("rewrite_files") instanceof List ? MiniJson.asArray(pack.get("rewrite_files")) : new ArrayList<>();
            ExampleDataInstaller.fillPaths(p, rw, staging);

            p.phase("Adding the projects to your workspace", 0);
            Map<String, String> ids = new LinkedHashMap<>();             // pack id -> new id (projects and runs)
            Map<String, String> names = new LinkedHashMap<>();
            for (String old : oldIds) {
                File src = staging.resolve("projects/" + old).toFile();
                if (!new File(src, "config.properties").isFile()) throw new IOException("The pack lacks project " + old + ".");
                ProjectMetadata pm = ProjectMetadata.load(src.getAbsolutePath());
                String name = pm != null && pm.name != null && !pm.name.trim().isEmpty() ? pm.name.trim() : old;
                if (name.length() > 80 || ServerApi.hasControl(name)) name = old;
                ProjectRegistry.Entry e;
                synchronized (ctx.registry) {
                    if (ctx.registry.ownedBy(userId).size() >= ctx.cfg.maxProjectsPerUser) throw new IOException("Your workspace is full.");
                    e = ctx.registry.create(userId, name);
                }
                createdProjects.add(e.projectId);
                ids.put(old, e.projectId);
                names.put(e.projectId, name);
            }
            List<File> runDirs = new ArrayList<>();
            for (String group : new String[]{"output/multi_locus", "output/mvsusie"}) {
                File[] ds = staging.resolve(group).toFile().listFiles(File::isDirectory);
                if (ds != null) for (File d : ds) {
                    if (!d.getName().matches("[A-Za-z0-9._-]{1,100}")) continue;
                    ids.put(d.getName(), UUID.randomUUID().toString());
                    runDirs.add(d);
                }
            }

            // Rename every id in the text files (quoted, and in project paths), then claim the runs
            List<File> texts = new ArrayList<>();
            for (String old : oldIds) collectText(staging.resolve("projects/" + old).toFile(), texts);
            for (File d : runDirs) collectText(d, texts);
            for (File f : texts) {
                String t = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), n = t;
                for (Map.Entry<String, String> e : ids.entrySet()) {
                    n = n.replace("\"" + e.getKey() + "\"", "\"" + e.getValue() + "\"")
                         .replace("projects/" + e.getKey() + "/", "projects/" + e.getValue() + "/")
                         .replace("multi_locus/" + e.getKey(), "multi_locus/" + e.getValue())
                         .replace("mvsusie/" + e.getKey(), "mvsusie/" + e.getValue());
                }
                if (!n.equals(t)) Files.write(f.toPath(), n.getBytes(StandardCharsets.UTF_8));
            }
            List<String> newJobs = new ArrayList<>();
            for (File d : runDirs) {
                boolean multi = d.getParentFile().getName().equals("multi_locus");
                File meta = new File(d, multi ? "job.json" : "meta.json");
                if (meta.isFile()) {
                    Map<String, Object> m = MiniJson.asObject(MiniJson.parse(new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8)));
                    m.put(multi ? "owner_id" : "owner", userId);
                    Files.write(meta.toPath(), MiniJson.encode(m).getBytes(StandardCharsets.UTF_8));
                }
                File dest = new File(multi ? "output/multi_locus" : "output/mvsusie", ids.get(d.getName()));
                Files.createDirectories(dest.getParentFile().toPath());
                Files.move(d.toPath(), dest.toPath());
                movedRuns.add(dest);
                if (multi) newJobs.add(dest.getName());
            }

            for (String old : oldIds) {
                String id = ids.get(old);
                File dest = new File(ctx.cfg.projectsDir(), id);
                File[] items = staging.resolve("projects/" + old).toFile().listFiles();
                if (items != null) for (File it : items) Files.move(it.toPath(), new File(dest, it.getName()).toPath());
                settle(dest, old, names.get(id), p);
                p.installed.add(names.get(id));
            }
            if (!newJobs.isEmpty()) onRunsImported.accept(newJobs);
            p.phase("Done", 0);
            p.message = oldIds.size() + " project(s)" + (newJobs.isEmpty() ? "" : " and " + newJobs.size() + " cross-dataset run(s)") + " added";
        } catch (Exception e) {
            p.error = e.getMessage() == null ? e.toString() : e.getMessage();
            p.phase("Failed", 0);
            for (String id : createdProjects) {
                try { ctx.registry.delete(id); FileSafety.deleteTree(new File(ctx.cfg.projectsDir(), id)); } catch (IOException ignored) {}
            }
            for (File d : movedRuns) { try { FileSafety.deleteTree(d); } catch (IOException ignored) {} }
            p.installed.clear();
            System.err.println("[Packs] import failed: " + p.error);
        } finally {
            try { FileSafety.deleteTree(zip.getParentFile()); } catch (IOException ignored) {}
            p.done = true;
        }
    }

    /**
     * Gives an imported project the server's file locations and a summary-statistics file the server can
     * read (input/gwas.tsv), or marks it results only when the pack did not include one.
     */
    private void settle(File dir, String packId, String name, ExampleDataInstaller.Progress p) throws IOException {
        String d = dir.getAbsolutePath();
        Config c = Config.loadFromProject(d);
        File input = new File(dir, "input"), gwas = new File(input, "gwas.tsv");
        File[] inputs = input.listFiles(File::isFile);
        File packed = null;
        if (inputs != null) for (File f : inputs) if (packed == null || f.length() > packed.length()) packed = f;
        if (packed != null && !packed.equals(gwas)) {
            File tmp = new File(input, "gwas.tsv.part");
            try (InputStream in = new BufferedInputStream(new FileInputStream(packed), 1 << 16)) {
                ServerApi.normalizeToTsv(in, tmp, ctx.cfg.maxUploadMb * 8L << 20);
            }
            Files.move(tmp.toPath(), gwas.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (inputs != null) for (File f : inputs) if (!f.equals(gwas)) Files.deleteIfExists(f.toPath());
        boolean hasGwas = gwas.isFile();
        c.gwasFile = hasGwas ? gwas.getAbsolutePath() : "";
        File loci = new File(dir, "loci.txt");
        c.lociFile = loci.isFile() ? loci.getAbsolutePath() : "";
        c.topSnpFile = "";
        c.gff3File = ctx.cfg.gff3For(c.genomeBuild);
        c.refPanelPath = ctx.cfg.refPanelFor(c.genomeBuild);
        c.refPanelPopulation = ctx.cfg.refPanelPopulation;
        c.ldEnabled = !c.refPanelPath.isEmpty() && c.ldEnabled;
        // The corpus names its datasets "<disease>-..." and leaves the disease field empty; a fresh id loses
        // that prefix, so keep the disease (it picks the tissue and groups datasets by disease)
        if ((c.diseaseName == null || c.diseaseName.trim().isEmpty()) && packId.indexOf('-') > 0)
            c.diseaseName = packId.substring(0, packId.indexOf('-'));
        c.writeProperties(new File(dir, "config.properties").getAbsolutePath());

        ProjectMetadata pm = ProjectMetadata.load(d);
        if (pm == null) pm = new ProjectMetadata();
        pm.id = dir.getName();
        pm.name = name;
        pm.resultsOnly = !hasGwas;
        if (hasGwas) {
            pm.coreInputFingerprint = ProjectMetadata.computeCoreInputFingerprint(c, d);
            pm.annotationFingerprint = ProjectMetadata.computeAnnotationFingerprint(new File(dir, "annotations.yaml").getAbsolutePath());
        }
        pm.save(d);
    }

    private static final Set<String> TEXT_EXT = new HashSet<>(Arrays.asList(
        "json", "properties", "tsv", "txt", "log", "r", "yaml", "yml", "js", "csv", "md", "key", "snps", "cojo", "master", "html"));

    private static void collectText(File root, List<File> out) {
        File[] items = root.listFiles();
        if (items == null) return;
        for (File f : items) {
            if (f.isDirectory()) { collectText(f, out); continue; }
            String n = f.getName();
            int dot = n.lastIndexOf('.');
            if (n.equals("config.properties") || (dot > 0 && TEXT_EXT.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT)))) out.add(f);
        }
    }
}
