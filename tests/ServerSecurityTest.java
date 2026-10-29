import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.GZIPOutputStream;

/**
 * Unit tests for the server's security core: tokens, anonymous workspaces and workspace links,
 * project ownership and retention, path safety, config policy, upload normalisation, and the
 * rate/job limiters. Uses a controllable clock and temp folders.
 */
public class ServerSecurityTest {
    static int failures = 0;

    static void check(boolean ok, String msg) {
        System.out.println((ok ? "PASS: " : "FAIL: ") + msg);
        if (!ok) failures++;
    }

    /** A clock tests can move forward. */
    static class TestClock extends Clock {
        long now = 1_800_000_000_000L;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
        void advance(long ms) { now += ms; }
    }

    static File tempDir(String name) throws IOException { return Files.createTempDirectory("lynx-" + name).toFile(); }

    public static void main(String[] args) throws Exception {
        crypto();
        workspaces();
        registry();
        paths();
        configPolicy();
        uploads();
        limiters();
        if (failures == 0) System.out.println("PASS: all ServerSecurity tests passed");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void crypto() {
        check(!Crypto.randomToken(32).equals(Crypto.randomToken(32)), "tokens are random");
        check(Crypto.randomToken(32).matches("[A-Za-z0-9_-]{43}"), "tokens are URL-safe with 256 bits");
        byte[] k = Crypto.randomBytes(32);
        check(Crypto.keyedDigest(k, "x").equals(Crypto.keyedDigest(k, "x")), "keyed digest is stable");
        check(!Crypto.keyedDigest(k, "x").equals(Crypto.keyedDigest(Crypto.randomBytes(32), "x")), "keyed digest depends on the pepper");
        check(Crypto.constantTimeEquals("abc", "abc") && !Crypto.constantTimeEquals("abc", "abd") && !Crypto.constantTimeEquals(null, "a"),
            "constant-time comparison");
    }

    static void workspaces() throws Exception {
        TestClock clock = new TestClock();
        File data = tempDir("ws");
        byte[] pepper = Crypto.randomBytes(32);
        WorkspaceService ws = new WorkspaceService(data, pepper, clock);
        List<String> removed = new ArrayList<>();
        ws.setRemovedListener(w -> removed.add(w.id));

        // Creating and using a workspace
        String tok = ws.create();
        WorkspaceService.Session s = ws.session(tok);
        check(s != null && s.userId.matches("[a-z0-9]{16}"), "new workspace opens with its device token");
        check(s.csrfToken.length() >= 40 && s.csrfToken.equals(ws.session(tok).csrfToken), "CSRF token is stable for a device");
        String tok2 = ws.create();
        check(!ws.session(tok2).userId.equals(s.userId), "each new workspace is separate");
        check(!ws.session(tok2).csrfToken.equals(s.csrfToken), "CSRF tokens differ between devices");
        check(ws.session(null) == null && ws.session("") == null && ws.session("short") == null
            && ws.session(Crypto.randomToken(32)) == null && ws.session("../" + tok) == null, "unknown or malformed tokens open nothing");

        // Nothing personal and no usable token on disk
        File file = new File(data, "workspaces/" + s.userId + ".properties");
        String onDisk = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        check(!onDisk.contains(tok), "device token is never stored in clear");
        Properties stored = new Properties();
        try (Reader r = new StringReader(onDisk)) { stored.load(r); }
        check(stored.stringPropertyNames().equals(new HashSet<>(Arrays.asList("id", "created", "last_seen", "devices", "link"))),
            "workspace file holds only id, times and token digests (no email, password, name or IP)");

        // Workspace link: opens the same workspace in another browser
        check(ws.newLink("no-such-workspace") == null, "no link for an unknown workspace");
        String link = ws.newLink(s.userId);
        String other = ws.openLink(link);
        check(other != null && !other.equals(tok) && ws.session(other).userId.equals(s.userId), "link opens the same workspace with a new device token");
        check(ws.session(tok) != null, "the first browser keeps working");
        check(ws.openLink(Crypto.randomToken(32)) == null && ws.openLink("x") == null, "a wrong link opens nothing");
        String link2 = ws.newLink(s.userId);
        check(ws.openLink(link) == null && ws.openLink(link2) != null, "a new link voids the old one");
        check(!new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).contains(link2), "link token is never stored in clear");

        // Forgetting a browser
        ws.forgetDevice(other);
        check(ws.session(other) == null && ws.session(tok) != null, "forgetting one browser leaves the others");

        // Device cap
        for (int i = 0; i < WorkspaceService.MAX_DEVICES + 5; i++) ws.openLink(link2);
        check(ws.session(tok) == null, "oldest devices are dropped beyond the cap");
        String fresh = ws.openLink(link2);

        // Persistence across a restart (same pepper), and a changed pepper disconnects everything
        WorkspaceService again = new WorkspaceService(data, pepper, clock);
        check(again.session(fresh) != null && again.session(fresh).userId.equals(s.userId), "workspaces survive a restart");
        check(again.openLink(link2) != null, "links survive a restart");
        WorkspaceService otherPepper = new WorkspaceService(data, Crypto.randomBytes(32), clock);
        check(otherPepper.session(fresh) == null, "tokens are useless without the server pepper");

        // Deletion and idle purge
        String keep = again.create();
        String keepId = again.session(keep).userId;
        again.setRemovedListener(w -> removed.add(w.id));
        again.delete(s.userId);
        check(again.session(fresh) == null && again.openLink(link2) == null && removed.contains(s.userId),
            "deleting a workspace ends its devices, its link and (via the listener) its projects");
        check(!file.exists(), "deleted workspace leaves no file");
        clock.advance(31L * 86_400_000L);
        int purged = again.purgeIdle(30L * 86_400_000L);
        check(purged >= 1 && again.session(keep) == null && removed.contains(keepId), "idle workspaces are removed");
    }

    static void registry() throws Exception {
        TestClock clock = new TestClock();
        File data = tempDir("reg"), projects = tempDir("projects");
        ServerConfig cfg = new ServerConfig();
        cfg.dataDir = data.getPath();
        cfg.projectsDir = projects.getPath();
        new File(projects, "public-demo").mkdirs();
        ProjectRegistry reg = new ProjectRegistry(cfg, clock);

        ProjectRegistry.Entry e = reg.create("user1", "My study");
        check(e.projectId.matches("u-[a-z0-9]{12}"), "user project ids are server-generated");
        check(reg.access(e.projectId, "user1") == ProjectRegistry.Access.OWNER, "owner has full access");
        check(reg.access(e.projectId, "user2") == ProjectRegistry.Access.NONE, "another user has no access");
        check(reg.access(e.projectId, null) == ProjectRegistry.Access.NONE, "anonymous has no access to a private project");
        check(reg.access("public-demo", null) == ProjectRegistry.Access.READ, "public dataset readable anonymously");
        check(reg.access("public-demo", "user1") == ProjectRegistry.Access.READ, "public dataset is read-only even with a workspace");
        check(reg.access("../etc", "user1") == ProjectRegistry.Access.NONE, "traversal id refused");
        check(reg.access("missing", null) == ProjectRegistry.Access.NONE, "unknown id refused");
        check(!reg.publicIds().contains(e.projectId) && reg.publicIds().contains("public-demo"), "public listing excludes user projects");
        check(e.expiresAt - e.createdAt == 15L * 86_400_000L, "user projects expire 15 days after creation");
        Files.write(new File(projects, e.projectId + "/data.txt").toPath(), new byte[5000]);
        ProjectRegistry.Entry other = reg.create("user2", "Other");
        Files.write(new File(projects, other.projectId + "/data.txt").toPath(), new byte[3000]);
        check(reg.storageUsedBy("user1") == 5000 && reg.storageUsedByAll() == 8000, "storage is counted per workspace and for the whole server");
        reg.delete(other.projectId);

        check(reg.sweep() == 0, "nothing happens early in the retention period");
        clock.advance(15L * 86_400_000L - 1);
        check(reg.sweep() == 0 && new File(projects, e.projectId).isDirectory(), "project still exists before expiry");
        clock.advance(1);
        int deleted = reg.sweep();
        check(deleted == 1 && !new File(projects, e.projectId).exists(), "project folder deleted at expiry");
        check(reg.access(e.projectId, "user1") == ProjectRegistry.Access.NONE, "deleted project is gone for its owner too");
        check(new File(projects, "public-demo").isDirectory(), "public datasets are never swept");

        // A symlink inside a user project must not let deletion escape the project
        ProjectRegistry.Entry e2 = reg.create("user1", "links");
        File outside = tempDir("outside");
        File keep = new File(outside, "keep.txt");
        Files.write(keep.toPath(), "keep".getBytes());
        try {
            Files.createSymbolicLink(new File(projects, e2.projectId + "/link").toPath(), outside.toPath());
            reg.delete(e2.projectId);
            check(keep.isFile(), "deleting a project does not follow symlinks out of it");
        } catch (UnsupportedOperationException | IOException noSymlinks) {
            reg.delete(e2.projectId);
            System.out.println("SKIP: symlinks not permitted here (" + noSymlinks.getClass().getSimpleName() + ")");
        }
    }

    static void paths() throws Exception {
        File base = tempDir("within");
        check(FileSafety.within(base, "a/b.txt").getPath().startsWith(base.getCanonicalPath()), "normal child path accepted");
        for (String bad : new String[]{"../x", "a/../../x", "/etc/passwd", "C:\\Windows\\win.ini", "", "a\0b"}) {
            boolean refused;
            try { FileSafety.within(base, bad); refused = false; } catch (IOException e) { refused = true; }
            check(refused, "path refused: " + bad.replace("\0", "\\0"));
        }
        check(FileSafety.validProjectId("scz-src-clozuk2018") && FileSafety.validProjectId("u-abc"), "normal ids valid");
        check(!FileSafety.validProjectId("..") && !FileSafety.validProjectId("a/b") && !FileSafety.validProjectId(".hidden")
            && !FileSafety.validProjectId("a..b") && !FileSafety.validProjectId(""), "dangerous ids invalid");
    }

    static void configPolicy() throws Exception {
        // Serving under a sub-path behind a proxy, reachable on http and https
        File pf = File.createTempFile("lynx-cfg", ".properties");
        Files.write(pf.toPath(), ("public.origin=https://bioinformatics.example.org, http://bioinformatics.example.org\n"
            + "base.path=/lynxgwas/\nsecure.cookies=true\ndata.dir=" + tempDir("cfgdata").getPath() + "\n").getBytes());
        ServerConfig sub = ServerConfig.load(pf);
        check(sub.trustProxyHops == 1, "by default only the nearest proxy is trusted");
        check(sub.basePath.equals("/lynxgwas") && sub.publicOrigin.equals("https://bioinformatics.example.org")
            && sub.publicOrigins.size() == 2, "base.path and several public origins are read (first origin is used in links)");
        SecurityGate subGate = new SecurityGate(sub, new WorkspaceService(tempDir("cfgws"), Crypto.randomBytes(32), Clock.systemUTC()), null, Clock.systemUTC());
        String cookie = subGate.sessionCookie("tok", 60);
        check(cookie.startsWith("__Secure-lynx_workspace=tok") && cookie.contains("Path=/lynxgwas/;") && cookie.contains("Secure"),
            "under a sub-path the cookie is scoped to it, so other sites on the host never get it");
        for (String bad : new String[]{"base.path=lynxgwas", "base.path=/a b", "public.origin=https://x.org/path"}) {
            Files.write(pf.toPath(), (bad + "\ndata.dir=" + tempDir("cfgdata").getPath() + "\n").getBytes());
            boolean refused;
            try { ServerConfig.load(pf); refused = false; } catch (IllegalArgumentException e) { refused = true; }
            check(refused, "invalid setting refused: " + bad);
        }
        pf.delete();

        ServerConfig sc = new ServerConfig();
        sc.refPanelPath = "/srv/ref/eur";
        sc.gff3File = "/srv/ref/genes.gff3";
        File proj = tempDir("cfgproj");
        Config prev = new Config();
        prev.gwasFile = new File(proj, "input/gwas.tsv").getAbsolutePath();
        String json = "{\"gwas.file\":\"/etc/shadow\",\"ref.panel.path\":\"/tmp/evil\",\"gff3.file\":\"/etc/passwd\","
            + "\"loci.file\":\"/etc/hosts\",\"top.snp.file\":\"/etc/group\",\"col.chr\":\"CHR\",\"col.pos\":\"BP\","
            + "\"col.pvalue\":\"P\",\"col.ea\":\"A1\",\"col.nea\":\"A2\",\"threads\":\"64\",\"ld.parallel.jobs\":\"50\"}";
        Config c = ConfigPolicy.apply(json, prev, proj, sc);
        check(c.gwasFile.equals(prev.gwasFile), "client cannot change gwas.file");
        check(c.refPanelPath.equals("/srv/ref/eur") && c.gff3File.equals("/srv/ref/genes.gff3"), "reference paths forced to server values");
        check(c.lociFile.isEmpty() && c.topSnpFile.isEmpty(), "client cannot point loci/top-SNP files anywhere");
        check(c.threads == 4 && c.ldParallelJobs == 2, "resource settings are clamped");
        boolean refused;
        try { ConfigPolicy.apply("{\"col.chr\":\"CHR\\nref.panel.path=/etc\",\"col.pos\":\"BP\",\"col.pvalue\":\"P\"}", prev, proj, sc); refused = false; }
        catch (IllegalArgumentException e) { refused = true; }
        check(refused, "newline injection into a column name is refused");
        try { ConfigPolicy.apply("{\"col.chr\":\"CHR\",\"col.pos\":\"BP\",\"col.pvalue\":\"P\",\"genome.build\":\"hg19;rm -rf\"}", prev, proj, sc); refused = false; }
        catch (IllegalArgumentException e) { refused = true; }
        check(refused, "unknown genome build refused");
        String red = ConfigPolicy.redactedJson(c);
        check(!red.contains("/srv/ref") && !red.contains(proj.getAbsolutePath().replace("\\", "\\\\")), "config shown to the browser has no server paths");
        // dataset metadata survives the redaction and a save that does not resend it
        prev.prevalence = 0.06;
        check(ConfigPolicy.redactedJson(prev).contains("\"trait.prevalence\":\"0.06\""), "prevalence is shown to the browser");
        check(ConfigPolicy.apply("{\"col.chr\":\"CHR\",\"col.pos\":\"BP\",\"col.pvalue\":\"P\"}", prev, proj, sc).prevalence == 0.06,
            "prevalence is kept when a save does not resend it");
    }

    static void uploads() throws Exception {
        File dir = tempDir("upload");
        File out = new File(dir, "g.tsv");
        ServerApi.normalizeToTsv(new ByteArrayInputStream("CHR,BP,P\r\n1,100,0.5\r\n1,200,1e-9\r\n".getBytes()), out, 1 << 20);
        check(new String(Files.readAllBytes(out.toPath())).equals("CHR\tBP\tP\n1\t100\t0.5\n1\t200\t1e-9\n"), "CSV with CRLF becomes TSV");

        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(gz)) { g.write("CHR BP  P\n2 5 0.1\n".getBytes()); }
        ServerApi.normalizeToTsv(new BufferedInputStream(new ByteArrayInputStream(gz.toByteArray())), out, 1 << 20);
        check(new String(Files.readAllBytes(out.toPath())).equals("CHR\tBP\tP\n2\t5\t0.1\n"), "gzipped, space-separated file is unpacked to TSV");

        boolean refused;
        try { ServerApi.normalizeToTsv(new ByteArrayInputStream(new byte[]{'A', '\t', 'B', '\n', 1, 2, 0, 3, '\n'}), out, 1 << 20); refused = false; }
        catch (IOException e) { refused = true; }
        check(refused, "binary content refused");

        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(bomb)) {
            g.write("CHR\tBP\tP\n".getBytes());
            byte[] row = "1\t1\t0.5\n".getBytes();
            for (int i = 0; i < 200_000; i++) g.write(row);
        }
        try { ServerApi.normalizeToTsv(new BufferedInputStream(new ByteArrayInputStream(bomb.toByteArray())), out, 100_000); refused = false; }
        catch (IOException e) { refused = true; }
        check(refused, "decompressed size is capped (gzip bomb / quota)");
        try { ServerApi.normalizeToTsv(new ByteArrayInputStream("CHR\tBP\n".getBytes()), out, 1 << 20); refused = false; }
        catch (IOException e) { refused = true; }
        check(refused, "header-only file refused");
    }

    static void limiters() throws Exception {
        TestClock clock = new TestClock();
        RequestLimiter rl = new RequestLimiter(3, 60, clock);
        check(rl.allow("ip") && rl.allow("ip") && rl.allow("ip"), "burst allowed");
        check(!rl.allow("ip"), "over the limit refused");
        check(rl.allow("other-ip"), "limits are per key");
        clock.advance(1_000);
        check(rl.hasCapacity("ip") && rl.hasCapacity("ip"), "checking capacity does not use it up");
        check(rl.allow("ip"), "tokens refill over time");
        check(!rl.hasCapacity("ip"), "capacity check sees an empty bucket");
        check(rl.hasCapacity("never-seen"), "unknown keys have capacity");

        check(ServerApi.limitKey("203.0.113.7").equals("203.0.113.7"), "IPv4 limits are per address");
        check(ServerApi.limitKey("2001:db8:1:2:aaaa::1").equals(ServerApi.limitKey("2001:db8:1:2:ffff:1:2:3")),
            "IPv6 limits are per /64, so rotating addresses inside one network does not help");
        check(!ServerApi.limitKey("2001:db8:1:2::1").equals(ServerApi.limitKey("2001:db8:1:3::1")), "different /64 networks are separate");

        JobLimiter jl = new JobLimiter(1, 2);
        Object lock = new Object();
        final boolean[] release = {false};
        Runnable wait = () -> { synchronized (lock) { while (!release[0]) try { lock.wait(); } catch (InterruptedException e) { return; } } };
        check(jl.start("u1", "t1", wait) == null, "first job for a user starts");
        check(jl.start("u1", "t2", wait) != null, "second concurrent job for the same user refused");
        check(jl.start("u2", "t3", wait) == null, "another user's job starts");
        check(jl.start("u3", "t4", wait) != null, "total cap refuses a third job");
        synchronized (lock) { release[0] = true; lock.notifyAll(); }
        for (int i = 0; i < 50 && jl.running() > 0; i++) Thread.sleep(20);
        check(jl.running() == 0 && jl.start("u1", "t5", () -> {}) == null, "slots are released when jobs finish");
    }
}
