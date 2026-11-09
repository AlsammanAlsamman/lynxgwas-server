import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/**
 * The data and programs LYNXgwas needs besides its own code (config/required_data.json), shared by the
 * desktop and the server edition. Run from the app folder:
 *
 *   check [--build 37|38|all]                  what is present, and which analyses lack what (exit 1 if a required item is missing)
 *   setup [--build 37|38|all] [--yes] [--only id,id] [--skip-optional]
 *                                              download and prepare what is missing (asks for --yes before downloading)
 *
 * GRCh37 is the default build. GRCh38 items made at setup (lifted LD-score positions and Roadmap peaks) are
 * built from the GRCh37 files with the UCSC chain, as the manifest says.
 */
public class RequiredData {

    static final File MANIFEST = new File("config/required_data.json");
    static final File DOWNLOADS = new File("resources/.downloads");
    static final String[] MARKS = {"H3K27ac", "H3K4me1", "H3K4me3"};

    public static boolean handles(String[] args) {
        return args.length > 0 && (args[0].equals("check") || args[0].equals("setup"));
    }

    public static int run(String[] args) {
        try {
            String build = "37";
            boolean yes = false, skipOptional = false;
            Set<String> only = new HashSet<>();
            for (int i = 1; i < args.length; i++) {
                if (args[i].equals("--build") && i + 1 < args.length) build = args[++i];
                else if (args[i].equals("--yes")) yes = true;
                else if (args[i].equals("--skip-optional")) skipOptional = true;
                else if (args[i].equals("--only") && i + 1 < args.length) only.addAll(Arrays.asList(args[++i].split(",")));
                else { System.err.println("Unknown option: " + args[i]); return 2; }
            }
            if (!build.matches("37|38|all")) { System.err.println("--build is 37, 38 or all"); return 2; }
            Map<String, Object> m = manifest();
            readConfiguredPaths();
            return args[0].equals("check") ? check(m, build, Collections.emptyList()) : setup(m, build, yes, only, skipOptional);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Panels and annotations the app is set up to use elsewhere count as present: the server's settings
     * (ref.panel.path[.grch38], gff3.file[.grch38]) and the desktop's reference panels in config/global.json.
     */
    static void readConfiguredPaths() {
        String sp = System.getenv("LYNX_SERVER_CONFIG");
        File props = new File(sp != null && !sp.isEmpty() ? sp : "server.properties");
        if (props.isFile()) {
            Properties p = new Properties();
            try (Reader r = new InputStreamReader(new FileInputStream(props), StandardCharsets.UTF_8)) { p.load(r); } catch (IOException ignored) {}
            putIfSet("panel-grch37", p.getProperty("ref.panel.path", ""), ".bed");
            putIfSet("panel-grch38", p.getProperty("ref.panel.path.grch38", ""), ".bed");
            putIfSet("genes-grch37", p.getProperty("gff3.file", ""), "");
            putIfSet("genes-grch38", p.getProperty("gff3.file.grch38", ""), "");
        }
        File g = new File("config/global.json");
        if (g.isFile()) {
            try {
                Map<String, Object> gc = MiniJson.asObject(MiniJson.parse(new String(Files.readAllBytes(g.toPath()), StandardCharsets.UTF_8)));
                if (gc.get("reference_panels") instanceof List) for (Object o : MiniJson.asArray(gc.get("reference_panels"))) {
                    Map<String, Object> rp = MiniJson.asObject(o);
                    if (!"EUR".equalsIgnoreCase(MiniJson.getStr(rp, "population", ""))) continue;
                    String b = MiniJson.getStr(rp, "build", "").toLowerCase(Locale.ROOT);
                    String id = b.contains("38") ? "panel-grch38" : "panel-grch37";
                    if (!CONFIGURED.containsKey(id)) putIfSet(id, MiniJson.getStr(rp, "plink_path", ""), ".bed");
                }
            } catch (Exception ignored) {}
        }
    }

    private static void putIfSet(String id, String path, String ext) {
        if (path != null && !path.trim().isEmpty() && new File(path.trim() + ext).isFile()) CONFIGURED.put(id, path.trim() + ext);
    }

    /** One line per required data item that is missing for the build, for the server's startup log. */
    public static List<String> missingRequired(String build) {
        List<String> out = new ArrayList<>();
        try {
            readConfiguredPaths();
            for (Map<String, Object> it : list(manifest(), "items")) {
                if (!inBuild(it, build) || !Boolean.TRUE.equals(it.get("required"))) continue;
                List<String> miss = missingFiles(it);
                if (!miss.isEmpty()) out.add(MiniJson.getStr(it, "id", "") + " (" + MiniJson.getStr(it, "what", "") + "): lacks " + miss.get(0));
            }
        } catch (IOException e) {
            out.add(e.getMessage());
        }
        return out;
    }

    /** {"build","gff3","panel","missing":[...]} : the installed gene annotation and EUR panel of a build. */
    public static String defaultsJson(String build) {
        String b = GenomeLiftover.isGrch38(build) ? "38" : "37";
        String gff = "", panel = "";
        List<String> missing = new ArrayList<>();
        try {
            readConfiguredPaths();
            for (Map<String, Object> it : list(manifest(), "items")) {
                String id = MiniJson.getStr(it, "id", "");
                if (!id.equals("genes-grch" + b) && !id.equals("panel-grch" + b)) continue;
                String path = CONFIGURED.getOrDefault(id, MiniJson.getStr(it, "path", ""));
                File f = new File(path);
                if (!missingFiles(it).isEmpty()) { missing.add(MiniJson.getStr(it, "what", id)); continue; }
                String abs = f.getAbsolutePath().replace('\\', '/');
                if (id.startsWith("genes")) gff = abs; else panel = abs.replaceAll("\\.bed$", "");
            }
        } catch (IOException e) {
            missing.add(e.getMessage());
        }
        StringBuilder j = new StringBuilder("{\"build\":\"GRCh" + b + "\",\"gff3\":" + MiniJson.encode(gff) + ",\"panel\":" + MiniJson.encode(panel) + ",\"missing\":[");
        for (int i = 0; i < missing.size(); i++) j.append(i > 0 ? "," : "").append(MiniJson.encode(missing.get(i)));
        return j.append("],\"setup\":").append(MiniJson.encode("setup --yes" + (b.equals("38") ? " --build 38" : ""))).append('}').toString();
    }

    static Map<String, Object> manifest() throws IOException {
        if (!MANIFEST.isFile()) throw new IOException("config/required_data.json not found: run this from the app folder");
        return MiniJson.asObject(MiniJson.parse(new String(Files.readAllBytes(MANIFEST.toPath()), StandardCharsets.UTF_8)));
    }

    static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (m.get(key) instanceof List) for (Object o : MiniJson.asArray(m.get(key))) out.add(MiniJson.asObject(o));
        return out;
    }

    static boolean inBuild(Map<String, Object> item, String build) {
        String b = MiniJson.getStr(item, "build", "both");
        return build.equals("all") || b.equals("both") || b.equals("GRCh" + build);
    }

    // ── check ───────────────────────────────────────────────────────────────

    /** Prints what is present; extra = {label, path} pairs the caller wants checked too (server settings). */
    /** Paths the app is configured to use instead of an item's default path (id -> path), e.g. the server's panel. */
    static final Map<String, String> CONFIGURED = new HashMap<>();

    public static int check(Map<String, Object> m, String build, List<String[]> extra) {
        int missingRequired = 0;
        Set<String> lacking = new TreeSet<>();
        System.out.printf("%-20s %-7s %-9s %s%n", "Data", "Build", "Status", "What");
        for (Map<String, Object> it : list(m, "items")) {
            if (!inBuild(it, build)) continue;
            List<String> miss = missingFiles(it);
            boolean req = Boolean.TRUE.equals(it.get("required"));
            String st = miss.isEmpty() ? "ok" : req ? "MISSING" : "missing";
            System.out.printf("%-20s %-7s %-9s %s%n", MiniJson.getStr(it, "id", ""), MiniJson.getStr(it, "build", ""), st, MiniJson.getStr(it, "what", ""));
            if (!miss.isEmpty()) {
                System.out.println("      lacks " + miss.get(0) + (miss.size() > 1 ? " (and " + (miss.size() - 1) + " more)" : ""));
                if (req) missingRequired++;
                for (Object o : MiniJson.asArray(it.get("needed_by"))) lacking.add(String.valueOf(o) + " [" + MiniJson.getStr(it, "build", "") + "]");
            }
        }
        for (String[] e : extra) {
            boolean ok = !e[1].isEmpty() && new File(e[1]).exists();
            System.out.printf("%-20s %-7s %-9s %s%n", e[0], "", ok ? "ok" : "MISSING", e[1].isEmpty() ? "(not set)" : e[1]);
            if (!ok) { missingRequired++; lacking.add(e[0]); }
        }
        System.out.printf("%n%-20s %-9s %s%n", "Program", "Status", "Found");
        for (Map<String, Object> t : list(m, "tools")) {
            String id = MiniJson.getStr(t, "id", "");
            String found = findTool(id);
            String version = found == null ? null : runVersion(found, t);
            boolean req = Boolean.TRUE.equals(t.get("required"));
            System.out.printf("%-20s %-9s %s%n", id, version != null ? "ok" : req ? "MISSING" : "missing",
                found == null ? "not in bin/ or on PATH" : found + (version == null ? " (does not run)" : "  " + version));
            if (version == null) {
                if (req) missingRequired++;
                for (Object o : MiniJson.asArray(t.get("needed_by"))) lacking.add(String.valueOf(o));
            }
        }
        System.out.printf("%n%-20s %-9s %s%n", "R library", "Status", "Packages");
        String rscript = ToolLocator.rscript();
        for (Map<String, Object> lib : list(m, "r_packages")) {
            String envName = MiniJson.getStr(lib, "env", "");
            String path = envName.isEmpty() ? "" : jointLibPath(envName);
            Map<String, Object> pk = MiniJson.asObject(lib.get("packages"));
            Map<String, String> have = rscript == null ? Collections.emptyMap() : rVersions(rscript, path, pk.keySet());
            List<String> bad = new ArrayList<>();
            for (Map.Entry<String, Object> e : pk.entrySet()) {
                String v = have.getOrDefault(e.getKey(), "missing"), want = String.valueOf(e.getValue());
                if (v.equals("missing") || (!want.isEmpty() && !v.equals(want))) bad.add(e.getKey() + (want.isEmpty() ? "" : " " + want) + " (have " + v + ")");
            }
            String label = MiniJson.getStr(lib, "lib", "") + (path.isEmpty() ? "" : " " + path);
            System.out.printf("%-20s %-9s %s%n", MiniJson.getStr(lib, "lib", ""), rscript == null ? "MISSING" : bad.isEmpty() ? "ok" : "MISSING",
                rscript == null ? "Rscript not found" : bad.isEmpty() ? String.join(", ", pk.keySet()) + (path.isEmpty() ? "" : "  in " + path) : String.join(", ", bad));
            if (rscript == null || !bad.isEmpty()) {
                missingRequired++;
                for (Object o : MiniJson.asArray(lib.get("needed_by"))) lacking.add(String.valueOf(o));
                if (label.isEmpty()) label = "R";
            }
        }
        System.out.println();
        if (lacking.isEmpty()) System.out.println("Every analysis has what it needs" + (build.equals("all") ? "." : " for GRCh" + build + " projects."));
        else {
            System.out.println("Not available until the missing items are installed (run 'setup'):");
            for (String l : lacking) System.out.println("  - " + l);
        }
        return missingRequired == 0 ? 0 : 1;
    }

    /** Files of an item that are absent (empty when it is complete). */
    static List<String> missingFiles(Map<String, Object> it) {
        List<String> want = new ArrayList<>();
        String path = CONFIGURED.getOrDefault(MiniJson.getStr(it, "id", ""), MiniJson.getStr(it, "path", ""));
        String recipe = MiniJson.getStr(it, "recipe", "");
        if (recipe.equals("roadmap") || recipe.equals("lift-roadmap")) {
            String base = recipe.equals("roadmap") ? "regulatory_data/" : "regulatory_data/GRCh38/";
            for (Object e : MiniJson.asArray(it.get("eids") != null ? it.get("eids") : roadmapEids()))
                for (String mk : MARKS) want.add(base + e + "/" + e + "-" + mk + ".narrowPeak.gz");
        } else if (recipe.equals("plink2-eur")) {
            String stem = path.replaceAll("\\.bed$", "");
            for (String ext : new String[]{".bed", ".bim", ".fam"}) want.add(stem + ext);
        } else if (recipe.equals("lift-ldscores") || MiniJson.getStr(it, "unpack", "").startsWith("untar")) {
            String dir = new File(path).getParent();
            for (int c = 1; c <= 22; c++) { want.add(dir + "/" + c + ".l2.ldscore.gz"); want.add(dir + "/" + c + ".l2.M_5_50"); }
        } else {
            want.add(path);
        }
        if (it.get("also") instanceof List) for (Object o : MiniJson.asArray(it.get("also"))) want.add(MiniJson.getStr(MiniJson.asObject(o), "path", ""));
        List<String> miss = new ArrayList<>();
        for (String w : want) if (!new File(w).isFile() || new File(w).length() == 0) miss.add(w);
        return miss;
    }

    /** A program in the app's bin/ (where the classes are, or the current folder) or on PATH. */
    static String findTool(String id) {
        String f = ToolLocator.binary(SusieAdapter.appRoot(), id);
        return f != null ? f : ToolLocator.binary(new File("."), id);
    }

    static List<Object> roadmapEids() { return new ArrayList<>(Arrays.asList("E034", "E065", "E073", "E096", "E098", "E106")); }

    static String jointLibPath(String envName) {
        String env = System.getenv(envName);
        if (env != null && !env.isEmpty()) return env;
        File app = new File(SusieAdapter.appRoot(), "rlib");
        if (app.isDirectory()) return app.getAbsolutePath();
        File home = new File(System.getProperty("user.home"), "R/mvsusie-lib");
        return home.isDirectory() ? home.getAbsolutePath() : "(not set: " + envName + ")";
    }

    static String runVersion(String exe, Map<String, Object> t) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        for (Object a : MiniJson.asArray(t.get("args"))) cmd.add(String.valueOf(a));
        Set<Integer> ok = new HashSet<>(Collections.singletonList(0));
        if (t.get("ok_exit") instanceof List) for (Object o : MiniJson.asArray(t.get("ok_exit"))) ok.add(((Number) o).intValue());
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out;
            try (InputStream in = p.getInputStream()) { out = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            if (!ok.contains(p.exitValue()) && out.trim().isEmpty()) return null;
            for (String line : out.split("\n")) {
                String l = line.trim();
                if (l.matches("(?i).*(v\\d|version|\\d+\\.\\d+).*") && !l.isEmpty()) return l.length() > 70 ? l.substring(0, 70) : l;
            }
            return "runs";
        } catch (Exception e) {
            return null;
        }
    }

    /** Installed versions of the packages in the given library (or the default ones when lib is empty). */
    static Map<String, String> rVersions(String rscript, String lib, Set<String> pkgs) {
        Map<String, String> out = new HashMap<>();
        StringBuilder r = new StringBuilder("for (p in c(");
        int i = 0;
        for (String p : pkgs) r.append(i++ > 0 ? "," : "").append('\'').append(p).append('\'');
        String loc = lib.isEmpty() || lib.startsWith("(") ? "NULL" : "'" + lib.replace("\\", "/") + "'";
        // single quotes only: Windows mangles double quotes inside one argument
        r.append(")) cat(p, tryCatch(as.character(packageVersion(p, lib.loc = ").append(loc).append(")), error = function(e) 'missing'), '\\n')");
        try {
            Process p = new ProcessBuilder(rscript, "-e", r.toString()).redirectErrorStream(true).start();
            String text;
            try (InputStream in = p.getInputStream()) { text = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            p.waitFor(60, TimeUnit.SECONDS);
            for (String line : text.split("\n")) {
                String[] f = line.trim().split("\\s+");
                if (f.length == 2 && pkgs.contains(f[0])) out.put(f[0], f[1]);
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ── setup ───────────────────────────────────────────────────────────────

    static int setup(Map<String, Object> m, String build, boolean yes, Set<String> only, boolean skipOptional) throws Exception {
        List<Map<String, Object>> todo = new ArrayList<>();
        long bytes = 0;
        for (Map<String, Object> it : list(m, "items")) {
            String id = MiniJson.getStr(it, "id", "");
            if (!inBuild(it, build) || (!only.isEmpty() && !only.contains(id))) continue;
            if (skipOptional && !Boolean.TRUE.equals(it.get("required"))) continue;
            if (missingFiles(it).isEmpty()) continue;
            todo.add(it);
            bytes += ExampleDataInstaller.num(it.get("download_bytes"));
        }
        if (todo.isEmpty()) {
            System.out.println("Nothing to download: every data item" + (build.equals("all") ? "" : " for GRCh" + build) + " is in place.");
            return check(m, build, Collections.emptyList());
        }
        System.out.println("To download and prepare:");
        for (Map<String, Object> it : todo)
            System.out.printf("  %-20s %8s  %s%n", MiniJson.getStr(it, "id", ""), gb(ExampleDataInstaller.num(it.get("download_bytes"))), MiniJson.getStr(it, "what", ""));
        System.out.printf("Total download about %s; free disk %s.%n", gb(bytes), gb(new File(".").getAbsoluteFile().getUsableSpace()));
        if (!yes) { System.out.println("Run again with --yes to start."); return 3; }
        DOWNLOADS.mkdirs();
        int failed = 0;
        for (Map<String, Object> it : todo) {
            String id = MiniJson.getStr(it, "id", "");
            System.out.println("== " + id + ": " + MiniJson.getStr(it, "what", ""));
            try {
                prepare(it);
                List<String> still = missingFiles(it);
                if (!still.isEmpty()) throw new IOException("still missing after setup: " + still.get(0));
                System.out.println("   done");
            } catch (Exception e) {
                failed++;
                System.err.println("   FAILED: " + e.getMessage());
            }
        }
        System.out.println();
        int c = check(m, build, Collections.emptyList());
        return failed > 0 ? 1 : c;
    }

    static void prepare(Map<String, Object> it) throws Exception {
        String recipe = MiniJson.getStr(it, "recipe", "");
        String path = MiniJson.getStr(it, "path", "");
        switch (recipe) {
            case "roadmap": {
                String base = MiniJson.getStr(it, "url", "");
                for (Object e : MiniJson.asArray(it.get("eids")))
                    for (String mk : MARKS) {
                        File out = new File("regulatory_data/" + e + "/" + e + "-" + mk + ".narrowPeak.gz");
                        if (out.isFile() && out.length() > 0) continue;
                        fetch(base + e + "-" + mk + ".narrowPeak.gz", out, -1, null);
                    }
                return;
            }
            case "plink2-eur": buildPanel(it); return;
            case "lift-ldscores": liftLdScores(new File("resources/ldsc/eur_w_ld_chr"), new File(path).getParentFile()); return;
            case "lift-roadmap": liftRoadmap(); return;
            default: break;
        }
        String url = MiniJson.getStr(it, "url", ""), unpack = MiniJson.getStr(it, "unpack", "");
        // "latest" sources change size with every release: the size in the list is only an estimate
        long size = Boolean.TRUE.equals(it.get("latest")) ? -1 : ExampleDataInstaller.num(it.get("download_bytes"));
        if (unpack.isEmpty()) {
            fetch(url, new File(path), size, MiniJson.getStr(it, "md5", ""));
        } else {
            File dl = new File(DOWNLOADS, MiniJson.getStr(it, "id", "x") + ".download");
            fetch(url, dl, size, MiniJson.getStr(it, "md5", ""));
            unpack(dl, unpack, new File(path));
            dl.delete();
        }
        if (it.get("also") instanceof List) for (Object o : MiniJson.asArray(it.get("also"))) {
            Map<String, Object> a = MiniJson.asObject(o);
            File f = new File(MiniJson.getStr(a, "path", ""));
            if (!f.isFile()) fetch(MiniJson.getStr(a, "url", ""), f, -1, null);
        }
    }

    /** Downloads url to dest (resuming a .part), checking the size and MD5 when given. */
    static void fetch(String url, File dest, long size, String md5) throws Exception {
        if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
        File part = new File(dest.getPath() + ".part");
        ExampleDataInstaller.Progress p = new ExampleDataInstaller.Progress();
        Thread show = progressPrinter(p, dest.getName());
        try {
            ExampleDataInstaller.download(p, url, part, size > 0 ? size : -1, "Downloading");
        } finally {
            show.interrupt();
        }
        if (md5 != null && !md5.isEmpty() && !md5.equalsIgnoreCase(md5Of(part))) {
            part.delete();
            throw new IOException("checksum mismatch for " + url);
        }
        Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    static Thread progressPrinter(ExampleDataInstaller.Progress p, String label) {
        Thread t = new Thread(() -> {
            long last = -1;
            try {
                while (true) {
                    Thread.sleep(5000);
                    if (p.bytesDone != last) {
                        last = p.bytesDone;
                        System.out.printf("   %s: %s%s%n", label, gb(p.bytesDone), p.bytesTotal > 0 ? " of " + gb(p.bytesTotal) : "");
                    }
                }
            } catch (InterruptedException ignored) {}
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    static void unpack(File dl, String how, File target) throws IOException {
        if (target.getParentFile() != null) target.getParentFile().mkdirs();
        if (how.equals("gunzip")) {
            File tmp = new File(target.getPath() + ".part");
            try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(dl), 1 << 16), 1 << 16)) {
                Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } else if (how.startsWith("unzip-one:")) {
            String name = how.substring("unzip-one:".length());
            try (ZipFile z = new ZipFile(dl)) {
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    if (!e.isDirectory() && e.getName().replace('\\', '/').endsWith("/" + name) || e.getName().equals(name)) {
                        try (InputStream in = z.getInputStream(e)) { Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
                        return;
                    }
                }
            }
            throw new IOException(name + " not found in the download");
        } else if (how.startsWith("untar:")) {
            untarGz(dl, new File(how.substring("untar:".length())));
        } else {
            throw new IOException("unknown unpack step " + how);
        }
    }

    /** Extracts a .tar.gz into dir (plain files and folders only, nothing outside dir). */
    static void untarGz(File tgz, File dir) throws IOException {
        Path root = dir.toPath().toAbsolutePath().normalize();
        try (InputStream in = new BufferedInputStream(new GZIPInputStream(new FileInputStream(tgz), 1 << 16), 1 << 16)) {
            byte[] h = new byte[512];
            while (true) {
                if (in.readNBytes(h, 0, 512) < 512) break;
                boolean empty = true;
                for (byte b : h) if (b != 0) { empty = false; break; }
                if (empty) break;
                String name = new String(h, 0, 100, StandardCharsets.US_ASCII).replace("\0", "");
                String prefix = new String(h, 345, 155, StandardCharsets.US_ASCII).replace("\0", "");
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                long size = Long.parseLong(new String(h, 124, 12, StandardCharsets.US_ASCII).replace("\0", "").trim().isEmpty() ? "0"
                    : new String(h, 124, 12, StandardCharsets.US_ASCII).replace("\0", "").trim(), 8);
                char type = (char) h[156];
                Path out = root.resolve(name).normalize();
                if (!out.startsWith(root)) throw new IOException("refusing tar entry outside the folder: " + name);
                if (type == '5') Files.createDirectories(out);
                else if (type == '0' || type == 0) {
                    Files.createDirectories(out.getParent());
                    try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
                        long left = size;
                        byte[] buf = new byte[1 << 16];
                        while (left > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new EOFException("truncated tar");
                            os.write(buf, 0, n);
                            left -= n;
                        }
                    }
                } else {
                    in.skipNBytes(size);
                }
                long pad = (512 - size % 512) % 512;
                in.skipNBytes(pad);
            }
        }
    }

    /**
     * 1000 Genomes EUR panel: the 503 EUR samples of phase 3, autosomes, biallelic, IDs present, duplicates removed.
     * Autosomes only, like the GRCh37 panel the article used: PLINK 1.9 rejects the GRCh38 "PAR1"/"PAR2" codes.
     */
    static void buildPanel(Map<String, Object> it) throws Exception {
        String plink2 = findTool("plink2");
        if (plink2 == null) throw new IOException("PLINK 2 is needed to build the reference panel: put plink2 in bin/ or on PATH "
            + "(https://www.cog-genomics.org/plink/2.0/) and run setup again.");
        File work = new File(DOWNLOADS, MiniJson.getStr(it, "id", "panel"));
        work.mkdirs();
        Map<String, File> src = new HashMap<>();
        for (Object o : MiniJson.asArray(it.get("sources"))) {
            Map<String, Object> s = MiniJson.asObject(o);
            File f = new File(work, MiniJson.getStr(s, "name", ""));
            if (!f.isFile()) fetch(MiniJson.getStr(s, "url", ""), f, ExampleDataInstaller.num(s.get("bytes")) > 0 ? ExampleDataInstaller.num(s.get("bytes")) : -1, null);
            src.put(f.getName(), f);
        }
        // EUR samples of phase 3 (the 2,504 unrelated): the same 503 people in both builds
        File keep = new File(work, "eur_keep.txt");
        File phase3Psam = src.get("phase3_corrected.psam");
        List<String> ids = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(phase3Psam))) {
            String[] h = br.readLine().replace("#", "").split("\t");
            int iid = Arrays.asList(h).indexOf("IID"), sp = Arrays.asList(h).indexOf("SuperPop");
            String line;
            while ((line = br.readLine()) != null) {
                String[] f = line.split("\t");
                if (sp >= 0 && f.length > sp && f[sp].equals("EUR")) ids.add(f[iid]);
            }
        }
        if (ids.size() != 503) throw new IOException("expected 503 EUR samples in phase3_corrected.psam, found " + ids.size());
        Files.write(keep.toPath(), ids);
        File pgenZst = null, pvar = null, psam = src.get("hg38.psam") != null ? src.get("hg38.psam") : phase3Psam;
        for (File f : src.values()) {
            if (f.getName().endsWith(".pgen.zst")) pgenZst = f;
            if (f.getName().endsWith(".pvar.zst")) pvar = f;
        }
        File pgen = new File(work, pgenZst.getName().replace(".zst", ""));
        if (!pgen.isFile()) {
            System.out.println("   decompressing " + pgenZst.getName());
            exec(Arrays.asList(plink2, "--zst-decompress", pgenZst.getPath()), pgen);
        }
        File dot = new File(work, "exclude_dot.txt");
        Files.write(dot.toPath(), Collections.singletonList("."));
        String out = MiniJson.getStr(it, "path", "").replaceAll("\\.bed$", "");
        new File(out).getAbsoluteFile().getParentFile().mkdirs();
        System.out.println("   keeping the 503 EUR samples");
        exec(Arrays.asList(plink2, "--pgen", pgen.getPath(), "--pvar", pvar.getPath(), "--psam", psam.getPath(),
            "--keep", keep.getPath(), "--autosome", "--max-alleles", "2", "--exclude", dot.getPath(), "--rm-dup", "exclude-all",
            "--make-bed", "--memory", "6000", "--threads", "4", "--out", out), null);   // bounded: setup may run beside the app
        // every sample a founder, as in the GRCh37 panel (the GRCh38 sample file links a few relatives, and PLINK 1.9
        // leaves non-founders out of allele frequencies)
        File fam = new File(out + ".fam");
        List<String> rows = new ArrayList<>();
        for (String line : Files.readAllLines(fam.toPath())) {
            String[] f = line.trim().split("\\s+");
            if (f.length >= 6) { f[2] = "0"; f[3] = "0"; line = String.join("\t", f); }
            rows.add(line);
        }
        Files.write(fam.toPath(), rows);
        deleteTree(work);
    }

    static void exec(List<String> cmd, File stdoutTo) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (stdoutTo != null) { pb.redirectOutput(stdoutTo); pb.redirectError(ProcessBuilder.Redirect.INHERIT); }
        else pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT);
        int code = pb.start().waitFor();
        if (code != 0) throw new IOException(cmd.get(0) + " failed (exit " + code + ")");
    }

    /** The LD-score files with HapMap3 positions lifted to GRCh38 (unmapped or chromosome-changing SNPs dropped). */
    static void liftLdScores(File from, File to) throws Exception {
        htsjdk.samtools.util.Log.setGlobalLogLevel(htsjdk.samtools.util.Log.LogLevel.WARNING);
        htsjdk.samtools.liftover.LiftOver lo = new htsjdk.samtools.liftover.LiftOver(new File("resources/hg19ToHg38.over.chain"));
        to.mkdirs();
        long kept = 0, dropped = 0;
        for (int c = 1; c <= 22; c++) {
            List<String[]> rows = new ArrayList<>();
            String header;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(new File(from, c + ".l2.ldscore.gz"))), StandardCharsets.UTF_8))) {
                header = br.readLine();
                List<String> h = Arrays.asList(header.split("\t"));
                int bp = h.indexOf("BP");
                String line;
                while ((line = br.readLine()) != null) {
                    String[] f = line.split("\t");
                    long pos = Long.parseLong(f[bp]);
                    htsjdk.samtools.util.Interval iv = lo.liftOver(new htsjdk.samtools.util.Interval("chr" + c, (int) pos, (int) pos));
                    if (iv == null || !iv.getContig().equals("chr" + c)) { dropped++; continue; }
                    f[bp] = String.valueOf(iv.getStart());
                    rows.add(f);
                    kept++;
                }
                final int b = bp;
                rows.sort(Comparator.comparingLong(r -> Long.parseLong(r[b])));
            }
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(new File(to, c + ".l2.ldscore.gz"))), StandardCharsets.UTF_8))) {
                pw.println(header);
                for (String[] r : rows) pw.println(String.join("\t", r));
            }
            for (String suffix : new String[]{".l2.M", ".l2.M_5_50"}) {
                File f = new File(from, c + suffix);
                if (f.isFile()) Files.copy(f.toPath(), new File(to, c + suffix).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        System.out.printf("   lifted %,d LD-score SNPs to GRCh38 (%,d unmapped, dropped)%n", kept, dropped);
    }

    /** The Roadmap peaks lifted to GRCh38 (peaks that do not map, or split, are dropped). */
    static void liftRoadmap() throws Exception {
        htsjdk.samtools.util.Log.setGlobalLogLevel(htsjdk.samtools.util.Log.LogLevel.WARNING);
        htsjdk.samtools.liftover.LiftOver lo = new htsjdk.samtools.liftover.LiftOver(new File("resources/hg19ToHg38.over.chain"));
        for (Object e : roadmapEids()) {
            for (String mk : MARKS) {
                File in = new File("regulatory_data/" + e + "/" + e + "-" + mk + ".narrowPeak.gz");
                File out = new File("regulatory_data/GRCh38/" + e + "/" + e + "-" + mk + ".narrowPeak.gz");
                if (!in.isFile()) throw new IOException("missing " + in + " (set up roadmap-grch37 first)");
                out.getParentFile().mkdirs();
                long kept = 0, dropped = 0;
                try (BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(in)), StandardCharsets.UTF_8));
                     PrintWriter pw = new PrintWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(out)), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String[] f = line.split("\t");
                        if (f.length < 3) continue;
                        String chr = f[0].startsWith("chr") ? f[0] : "chr" + f[0];
                        htsjdk.samtools.util.Interval iv = lo.liftOver(new htsjdk.samtools.util.Interval(chr, Integer.parseInt(f[1]) + 1, Integer.parseInt(f[2])), 0.95);
                        if (iv == null || !iv.getContig().equals(chr)) { dropped++; continue; }
                        f[1] = String.valueOf(iv.getStart() - 1);
                        f[2] = String.valueOf(iv.getEnd());
                        pw.println(String.join("\t", f));
                        kept++;
                    }
                }
                System.out.printf("   %s %s: %,d peaks lifted, %,d dropped%n", e, mk, kept, dropped);
            }
        }
    }

    static String md5Of(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 16)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    static void deleteTree(File d) throws IOException {
        if (!d.exists()) return;
        try (java.util.stream.Stream<Path> w = Files.walk(d.toPath())) {
            List<Path> all = new ArrayList<>();
            w.forEach(all::add);
            Collections.reverse(all);
            for (Path x : all) Files.deleteIfExists(x);
        }
    }

    static String gb(long b) { return b >= 1e9 ? String.format(Locale.ROOT, "%.1f GB", b / 1e9) : String.format(Locale.ROOT, "%.0f MB", b / 1e6); }
}
