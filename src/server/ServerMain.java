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
        Mailer mailer = Mailer.create(cfg);
        ServerContext ctx = new ServerContext(cfg, clock, mailer);
        ServerApi api = new ServerApi(ctx);
        LocalServer server = new LocalServer(ctx, api);
        server.start();
        System.out.printf("Listening on %s:%d (public origin %s)%n", cfg.bindHost, cfg.port, cfg.publicOrigin);
        System.out.printf("Accounts: %d, public datasets: %d, retention: %d days (warning %d days before)%n",
            ctx.accounts.accountCount(), ctx.registry.publicIds().size(), cfg.retentionDays, cfg.retentionWarnDays);

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
            mailer.flush();
        }));
    }

    /** Hourly: warn/delete expired projects, drop never-verified accounts and expired runs. */
    static void retentionSweep(ServerContext ctx, LocalServer server) {
        try {
            int deleted = ctx.registry.sweep(ctx::emailUser);
            int accounts = ctx.accounts.purgeUnverified(ctx.cfg.unverifiedAccountHours * 3_600_000L);
            int jobs = server.sweepExpiredJobs();
            if (deleted + accounts + jobs > 0)
                System.out.printf("[Retention] deleted %d project(s), %d unverified account(s), %d run(s)%n", deleted, accounts, jobs);
        } catch (Throwable t) {
            System.err.println("[Retention] sweep failed: " + t);
        }
    }

    static void warnAboutRiskySettings(ServerConfig cfg) {
        boolean loopback = cfg.bindHost.equals("127.0.0.1") || cfg.bindHost.equals("localhost") || cfg.bindHost.equals("::1");
        if (!loopback)
            System.out.println("[WARN] bind.host is not loopback: the Java server should sit behind a TLS proxy, not face the internet directly.");
        if (!cfg.secureCookies && !cfg.publicOrigin.startsWith("http://localhost") && !cfg.publicOrigin.startsWith("http://127.0.0.1"))
            System.out.println("[WARN] secure.cookies=false with a non-local public.origin: sessions would travel without TLS.");
        if (cfg.devMail())
            System.out.println("[WARN] No smtp.host: verification codes are written to " + cfg.dataDir + "/outbox (development only).");
        if (cfg.refPanelPath.isEmpty())
            System.out.println("[WARN] No ref.panel.path: LD and fine-mapping will be unavailable for user projects.");
    }
}
