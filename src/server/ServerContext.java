import java.io.*;
import java.nio.file.*;
import java.time.Clock;

/** The shared services of one running server, built once at startup. */
public class ServerContext {
    public final ServerConfig cfg;
    public final Clock clock;
    public final Mailer mailer;
    public final AccountService accounts;
    public final ProjectRegistry registry;
    public final SecurityGate gate;
    public final JobLimiter jobs;
    public final RequestLimiter authLimiter;   // login/register/verify/reset attempts per IP
    public final RequestLimiter mailLimiter;   // code emails per IP

    public ServerContext(ServerConfig cfg, Clock clock, Mailer mailer) throws IOException {
        this.cfg = cfg;
        this.clock = clock;
        this.mailer = mailer;
        Files.createDirectories(cfg.dataDir().toPath());
        FileSafety.restrictToOwner(cfg.dataDir());
        Files.createDirectories(cfg.projectsDir().toPath());
        this.accounts = new AccountService(cfg.dataDir(), loadPepper(cfg), mailer, clock, "LYNXgwas");
        this.registry = new ProjectRegistry(cfg, clock);
        this.accounts.setAccountRemovedListener(a -> registry.deleteAllOf(a.id));
        this.gate = new SecurityGate(cfg, accounts, registry, clock);
        this.jobs = new JobLimiter(cfg.maxJobsPerUser, cfg.maxJobsTotal);
        this.authLimiter = new RequestLimiter(20, 10, clock);
        this.mailLimiter = new RequestLimiter(5, 2, clock);
    }

    /** Emails an account owner (by user id); silently skipped if the account no longer exists. */
    public void emailUser(String userId, String subjectSuffix, String body) {
        AccountService.Account a = accounts.account(userId);
        if (a != null) mailer.send(a.email, "LYNXgwas: " + subjectSuffix, body);
    }

    /**
     * Server secret for keyed digests: the environment variable named by pepper.env, else a random
     * key generated once into <dataDir>/pepper.key (owner-only). Changing it voids pending codes/sessions.
     */
    static byte[] loadPepper(ServerConfig cfg) throws IOException {
        String env = cfg.secretFromEnv(cfg.pepperEnv);
        if (env.length() >= 32) return env.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (!env.isEmpty()) throw new IOException(cfg.pepperEnv + " must be at least 32 characters");
        File f = new File(cfg.dataDir(), "pepper.key");
        if (!f.exists()) {
            File tmp = new File(cfg.dataDir(), "pepper.key.tmp");
            Files.write(tmp.toPath(), Crypto.randomBytes(32));
            FileSafety.restrictToOwner(tmp);
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE);
        }
        byte[] key = Files.readAllBytes(f.toPath());
        if (key.length < 32) throw new IOException("pepper.key is corrupt");
        return key;
    }
}
