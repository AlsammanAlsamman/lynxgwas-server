import java.time.Clock;
import java.util.concurrent.*;

/**
 * Starts the online LYNXgwas server.
 *
 *   java -cp "bin:lib/*" ServerMain            (settings from $LYNX_SERVER_CONFIG or ./server.properties)
 *
 * Run it from the app folder (index.html, tools/, bin/ and projects/ are resolved from there),
 * as an unprivileged user, behind a TLS reverse proxy. See docs/DEPLOY.md.
 */
public class ServerMain {

    public static void main(String[] args) throws Exception {
        System.out.println("=== LYNXgwas Server ===");
        ServerConfig cfg = ServerConfig.load();
        warnAboutRiskySettings(cfg);

        Clock clock = Clock.systemUTC();
        ServerContext ctx = new ServerContext(cfg, clock);
        warnAboutOldAccounts(cfg);
        ServerApi api = new ServerApi(ctx);
        LocalServer server = new LocalServer(ctx, api);
        server.start();
        System.out.printf("Listening on %s:%d (public address %s%s)%n", cfg.bindHost, cfg.port, String.join(", ", cfg.publicOrigins), cfg.basePath);
        System.out.printf("Workspaces: %d, public datasets: %d, retention: %d days, idle workspaces removed after %d days%n",
            ctx.workspaces.count(), ctx.registry.publicIds().size(), cfg.retentionDays, cfg.workspaceIdleDays);

        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "retention"); t.setDaemon(true); return t;
        });
        sched.scheduleWithFixedDelay(() -> retentionSweep(ctx, server), 1, 60, TimeUnit.MINUTES);
        sched.scheduleWithFixedDelay(() -> {
            try { server.rebuildSearchIndex(); } catch (Throwable t) { System.err.println("[Search] rebuild failed: " + t); }
        }, 2, 24 * 60, TimeUnit.MINUTES);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Server] shutting down");
            server.stop();
            sched.shutdownNow();
        }));
    }

    /** Hourly: delete expired projects, idle workspaces and expired runs. */
    static void retentionSweep(ServerContext ctx, LocalServer server) {
        try {
            int deleted = ctx.registry.sweep();
            int idle = ctx.workspaces.purgeIdle(ctx.cfg.workspaceIdleDays * 86_400_000L);
            int jobs = server.sweepExpiredJobs();
            if (deleted + idle + jobs > 0)
                System.out.printf("[Retention] deleted %d project(s), %d idle workspace(s), %d run(s)%n", deleted, idle, jobs);
        } catch (Throwable t) {
            System.err.println("[Retention] sweep failed: " + t);
        }
    }

    static void warnAboutRiskySettings(ServerConfig cfg) {
        boolean loopback = cfg.bindHost.equals("127.0.0.1") || cfg.bindHost.equals("localhost") || cfg.bindHost.equals("::1");
        if (!loopback)
            System.out.println("[WARN] bind.host is not loopback: the Java server should sit behind a TLS proxy, not face the internet directly.");
        if (!cfg.secureCookies && !cfg.publicOrigin.startsWith("http://localhost") && !cfg.publicOrigin.startsWith("http://127.0.0.1"))
            System.out.println("[WARN] secure.cookies=false with a non-local public.origin: workspace cookies would travel without TLS.");
        if (cfg.maxStorageTotalMb == 0)
            System.out.println("[WARN] max.storage.total.mb=0 (no limit): anonymous visitors could fill the disk.");
        if (cfg.refPanelPath.isEmpty())
            System.out.println("[WARN] No ref.panel.path: LD and fine-mapping will be unavailable for user projects.");
    }

    /** Data from the old email/password version: never read, but it is personal data an admin should remove. */
    static void warnAboutOldAccounts(ServerConfig cfg) {
        java.io.File old = new java.io.File(cfg.dataDir(), "accounts");
        java.io.File outbox = new java.io.File(cfg.dataDir(), "outbox");
        if (old.isDirectory() || outbox.isDirectory())
            System.out.println("[WARN] " + cfg.dataDir + " still holds accounts/ or outbox/ from the email version "
                + "(emails, password hashes). They are no longer used: delete them.");
    }
}
