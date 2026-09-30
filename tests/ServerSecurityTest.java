import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.GZIPOutputStream;

/**
 * Unit tests for the server's security core: passwords, accounts and emailed codes, lockout,
 * sessions, project ownership and retention, path safety, config policy, upload normalisation,
 * and the rate/job limiters. Uses a controllable clock, temp folders and the development outbox.
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

    static String codeIn(Mailer.OutboxMailer m, String email) throws IOException {
        m.flush();
        String mail = m.latestTo(email);
        if (mail == null) return null;
        Matcher x = Pattern.compile("\\n\\s+(\\d{6})\\n").matcher(mail);
        return x.find() ? x.group(1) : null;
    }

    public static void main(String[] args) throws Exception {
        crypto();
        accounts();
        registry();
        paths();
        configPolicy();
        uploads();
        limiters();
        if (failures == 0) System.out.println("PASS: all ServerSecurity tests passed");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void crypto() {
        String h = Crypto.hashPassword("correct horse battery".toCharArray());
        check(h.startsWith("pbkdf2-sha256$600000$"), "password hash records algorithm and cost");
        check(Crypto.verifyPassword("correct horse battery".toCharArray(), h), "right password verifies");
        check(!Crypto.verifyPassword("correct horse batterx".toCharArray(), h), "wrong password is rejected");
        check(!h.equals(Crypto.hashPassword("correct horse battery".toCharArray())), "same password hashes differently (salted)");
        check(Crypto.needsRehash("pbkdf2-sha256$1000$AAAA$BBBB"), "weaker hash is flagged for rehash");
        check(!Crypto.verifyPassword("x".toCharArray(), "garbage"), "malformed hash never verifies");
        check(Crypto.randomDigits(6).matches("\\d{6}"), "codes are 6 digits");
        check(!Crypto.randomToken(32).equals(Crypto.randomToken(32)), "tokens are random");
    }

    static void accounts() throws Exception {
        TestClock clock = new TestClock();
        File data = tempDir("acct");
        Mailer.OutboxMailer mail = new Mailer.OutboxMailer(new File(data, "outbox"));
        AccountService acc = new AccountService(data, Crypto.randomBytes(32), mail, clock, "LYNXgwas");

        check(!acc.register("not-an-email", "Str0ng-enough-pass").ok, "invalid email refused");
        check(!acc.register("a@example.org", "short").ok, "short password refused");
        check(!acc.register("a@example.org", "password12345").ok, "guessable password refused");
        check(acc.register("t@example.org", "Blue-Tiger-Harbor-71").ok, "short email names don't block ordinary passwords");
        check(!acc.register("maria.lopez@example.org", "maria.lopez-2026!").ok, "password containing the email name refused");

        AccountService.Outcome r = acc.register("Alice@Example.org", "Blue-Tiger-Harbor-71");
        check(r.ok, "registration accepted");
        String code = codeIn(mail, "alice@example.org");
        check(code != null, "verification code emailed (email normalised to lowercase)");
        check(!acc.login("alice@example.org", "Blue-Tiger-Harbor-71").ok, "cannot sign in before verifying");

        AccountService.Outcome dup = acc.register("bob@example.org", "Green-Falcon-River-22");
        AccountService.Outcome unknownResend = acc.resendCode("nobody@example.org", AccountService.Purpose.VERIFY_EMAIL);
        check(dup.message.equals(r.message), "register answers identically for any email (no enumeration)");
        check(unknownResend.ok, "resend for unknown email looks the same as for a real one");

        String wrong = code.equals("000000") ? "111111" : "000000";
        check(!acc.verifyEmail("alice@example.org", wrong).ok, "wrong code refused");
        AccountService.Outcome v = acc.verifyEmail("alice@example.org", code);
        check(v.ok && v.sessionToken != null, "right code verifies and signs in");
        check(!acc.verifyEmail("alice@example.org", code).ok, "code is single-use");
        AccountService.Session s = acc.session(v.sessionToken);
        check(s != null && s.csrfToken != null && s.csrfToken.length() >= 20, "session has its own CSRF token");

        // Code brute force: 5 wrong guesses burn the code
        acc.register("carol@example.org", "Red-Panda-Mountain-93");
        String carolCode = codeIn(mail, "carol@example.org");
        for (int i = 0; i < 5; i++) acc.verifyEmail("carol@example.org", String.format("%06d", (Integer.parseInt(carolCode) + 1 + i) % 1_000_000));
        check(!acc.verifyEmail("carol@example.org", carolCode).ok, "after 5 wrong guesses even the right code is refused");

        // Code expiry
        clock.advance(61_000);
        acc.resendCode("carol@example.org", AccountService.Purpose.VERIFY_EMAIL);
        String fresh = codeIn(mail, "carol@example.org");
        clock.advance(AccountService.CODE_TTL_MS + 1);
        check(!acc.verifyEmail("carol@example.org", fresh).ok, "expired code refused");

        // Login lockout
        for (int i = 0; i < AccountService.MAX_FAILED_LOGINS; i++) acc.login("alice@example.org", "Wrong-Password-" + i);
        check(!acc.login("alice@example.org", "Blue-Tiger-Harbor-71").ok, "account locks after repeated failures, even for the right password");
        clock.advance(AccountService.LOCKOUT_MS + 1);
        AccountService.Outcome ok = acc.login("alice@example.org", "Blue-Tiger-Harbor-71");
        check(ok.ok, "lock lifts after the lockout period");
        check(acc.login("ALICE@example.org", "nope-nope-nope").message.equals(acc.login("ghost@example.org", "nope-nope-nope").message),
            "wrong password and unknown email give the same message");

        // Sessions: idle expiry, logout
        String tok = ok.sessionToken;
        check(acc.session(tok) != null, "fresh session is valid");
        clock.advance(AccountService.SESSION_IDLE_MS + 1);
        check(acc.session(tok) == null, "idle session expires");
        String tok2 = acc.login("alice@example.org", "Blue-Tiger-Harbor-71").sessionToken;
        acc.logout(tok2);
        check(acc.session(tok2) == null, "logout ends the session");

        // Password reset revokes other sessions
        String tok3 = acc.login("alice@example.org", "Blue-Tiger-Harbor-71").sessionToken;
        clock.advance(61_000);
        acc.requestPasswordReset("alice@example.org");
        String resetCode = codeIn(mail, "alice@example.org");
        AccountService.Outcome reset = acc.resetPassword("alice@example.org", resetCode, "Silver-Otter-Canyon-58");
        check(reset.ok, "password reset with emailed code");
        check(acc.session(tok3) == null, "reset signs out existing sessions");
        check(acc.login("alice@example.org", "Silver-Otter-Canyon-58").ok, "new password works");
        check(!acc.login("alice@example.org", "Blue-Tiger-Harbor-71").ok, "old password no longer works");

        // Persistence: accounts survive a restart, sessions do not
        AccountService again = new AccountService(data, Crypto.randomBytes(32), mail, clock, "LYNXgwas");
        check(again.login("alice@example.org", "Silver-Otter-Canyon-58").ok, "accounts are persisted");
        File[] files = new File(data, "accounts").listFiles();
        String onDisk = new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8);
        check(!onDisk.contains("Silver-Otter"), "password is never stored in clear");

        // Unverified accounts are purged
        clock.advance(49L * 3_600_000L);
        int purged = again.purgeUnverified(48L * 3_600_000L);
        check(purged >= 2, "unverified accounts older than 48h are removed (" + purged + ")");
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
        check(reg.access("public-demo", "user1") == ProjectRegistry.Access.READ, "public dataset is read-only even when signed in");
        check(reg.access("../etc", "user1") == ProjectRegistry.Access.NONE, "traversal id refused");
        check(reg.access("missing", null) == ProjectRegistry.Access.NONE, "unknown id refused");
        check(!reg.publicIds().contains(e.projectId) && reg.publicIds().contains("public-demo"), "public listing excludes user projects");
        check(e.expiresAt - e.createdAt == 15L * 86_400_000L, "user projects expire 15 days after creation");

        List<String> mails = new ArrayList<>();
        reg.sweep((owner, subj, body) -> mails.add(owner + "|" + subj));
        check(mails.isEmpty(), "nothing happens early in the retention period");
        clock.advance(12L * 86_400_000L + 1);
        reg.sweep((owner, subj, body) -> mails.add(owner + "|" + subj));
        check(mails.size() == 1 && mails.get(0).startsWith("user1|project will be deleted in"), "warning email 3 days before deletion");
        reg.sweep((owner, subj, body) -> mails.add(owner + "|" + subj));
        check(mails.size() == 1, "warning is sent only once");
        check(new File(projects, e.projectId).isDirectory(), "project still exists before expiry");
        clock.advance(3L * 86_400_000L);
        int deleted = reg.sweep((owner, subj, body) -> mails.add(owner + "|" + subj));
        check(deleted == 1 && !new File(projects, e.projectId).exists(), "project folder deleted at expiry");
        check(mails.size() == 2 && mails.get(1).equals("user1|project deleted"), "deletion notice emailed");
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
        check(rl.allow("ip"), "tokens refill over time");

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
