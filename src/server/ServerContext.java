import java.io.*;
import java.nio.file.*;
import java.time.Clock;

/** The shared services of one running server, built once at startup. */
public class ServerContext {
    public final ServerConfig cfg;
    public final Clock clock;
    public final WorkspaceService workspaces;
    public final ProjectRegistry registry;
    public final SecurityGate gate;
    public final JobLimiter jobs;
    public final RequestLimiter newWorkspaceLimiter;   // workspaces created per IP
    public final RequestLimiter linkLimiter;           // workspace-link attempts per IP (guessing)

    public ServerContext(ServerConfig cfg, Clock clock) throws IOException {
        this.cfg = cfg;
        this.clock = clock;
        Files.createDirectories(cfg.dataDir().toPath());
        FileSafety.restrictToOwner(cfg.dataDir());
        Files.createDirectories(cfg.projectsDir().toPath());
        this.workspaces = new WorkspaceService(cfg.dataDir(), loadPepper(cfg), clock);
        this.registry = new ProjectRegistry(cfg, clock);
        this.workspaces.setRemovedListener(w -> registry.deleteAllOf(w.id));
        this.gate = new SecurityGate(cfg, workspaces, registry, clock);
        this.jobs = new JobLimiter(cfg.maxJobsPerUser, cfg.maxJobsTotal);
        this.newWorkspaceLimiter = new RequestLimiter(cfg.maxNewWorkspacesPerIpPerHour,
            cfg.maxNewWorkspacesPerIpPerHour / 60.0, clock);
        this.linkLimiter = new RequestLimiter(10, 5, clock);
    }

    /**
     * Server secret for keyed digests: the environment variable named by pepper.env, else a random
     * key generated once into <dataDir>/pepper.key (owner-only). Changing it disconnects every
     * workspace from its browsers and links (their projects are then removed as idle).
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
