import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Settings for the online (multi-user) server, read from a properties file outside the repo.
 *
 * Location: the LYNX_SERVER_CONFIG environment variable, else ./server.properties.
 * The one secret (the pepper) is read from an environment variable named in the file, never
 * stored in the file itself, so the file can be kept in version control or backups.
 * See server.properties.example for every key.
 */
public class ServerConfig {

    /** Address to bind. Keep 127.0.0.1 and put a TLS reverse proxy (Caddy/nginx) in front. */
    public String bindHost = "127.0.0.1";
    public int    port     = 8765;
    /** Public origin users reach, e.g. https://lynxgwas.example.org — used for CSRF Origin checks and workspace links. */
    public String publicOrigin = "http://localhost:8765";
    /** Every origin the site is reached on (public.origin may list several, comma-separated; the first is used in links). */
    public List<String> publicOrigins = new ArrayList<>(Collections.singletonList(publicOrigin));
    /** URL path the app is served under behind the proxy, e.g. /lynxgwas ("" = the site root). */
    public String basePath = "";
    /** True when served over HTTPS (behind the proxy): Secure cookies, __Host- prefix, HSTS. */
    public boolean secureCookies = false;
    /** Honour X-Forwarded-For for client IPs (rate limiting). Only true when behind your own proxy. */
    public boolean trustProxy = false;
    /** How many proxies we own append to X-Forwarded-For (e.g. 2 = load balancer + Apache). */
    public int trustProxyHops = 1;

    /** Server-private state: accounts, sessions, registry, outbox. Never web-served. */
    public String dataDir = "server-data";
    /** Where project folders live (public ones and users' ones). */
    public String projectsDir = "projects";
    /** Comma-separated ids of the read-only public projects; "*" = every project with no owner. */
    public String publicProjects = "*";

    /** Reference panel / annotation paths every user project is forced to use (clients can't set paths). */
    public String refPanelPath = "";
    public String refPanelPopulation = "EUR";
    public String gff3File = "";
    /** SuSiEx.py on the server (empty = SuSiEx runs disabled). */
    public String susiexPath = "";

    /** Retention: user projects are deleted this many days after creation. */
    public int retentionDays = 15;
    /** A workspace nobody has opened for this many days is removed (with any projects left in it). */
    public int workspaceIdleDays = 30;

    /** Upload / quota limits. */
    public long maxUploadMb = 2048;
    public int  maxProjectsPerUser = 5;
    public long maxStoragePerUserMb = 8192;
    /** Heavy background jobs (processing, analyses) running at once, per user and in total. */
    public int  maxJobsPerUser = 1;
    public int  maxJobsTotal = 4;
    /** Storage for all user projects together (0 = no limit). Uploads are refused when it is used up. */
    public long maxStorageTotalMb = 100_000;
    /** New anonymous workspaces one IP address (IPv6: one /64 network) may create per hour. */
    public int  maxNewWorkspacesPerIpPerHour = 30;

    /** Name of the environment variable holding a random secret mixed into stored token digests. */
    public String pepperEnv = "LYNX_CODE_PEPPER";

    public static ServerConfig load() throws IOException {
        String path = System.getenv("LYNX_SERVER_CONFIG");
        if (path == null || path.isEmpty()) path = "server.properties";
        return load(new File(path));
    }

    public static ServerConfig load(File file) throws IOException {
        ServerConfig c = new ServerConfig();
        if (!file.exists()) {
            System.out.printf("[ServerConfig] %s not found, using development defaults%n", file.getPath());
            return c;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file.toPath())) { p.load(r); }
        c.bindHost             = p.getProperty("bind.host", c.bindHost).trim();
        c.port                 = intProp(p, "port", c.port);
        c.publicOrigins = new ArrayList<>();
        for (String o : p.getProperty("public.origin", c.publicOrigin).split(","))
            if (!o.trim().isEmpty()) c.publicOrigins.add(stripSlash(o.trim()));
        if (c.publicOrigins.isEmpty()) c.publicOrigins.add(c.publicOrigin);
        c.publicOrigin         = c.publicOrigins.get(0);
        c.basePath             = stripSlash(p.getProperty("base.path", c.basePath).trim());
        c.secureCookies        = boolProp(p, "secure.cookies", c.secureCookies);
        c.trustProxy           = boolProp(p, "trust.proxy", c.trustProxy);
        c.trustProxyHops       = intProp(p, "trust.proxy.hops", c.trustProxyHops);
        c.dataDir              = p.getProperty("data.dir", c.dataDir).trim();
        c.projectsDir          = p.getProperty("projects.dir", c.projectsDir).trim();
        c.publicProjects       = p.getProperty("public.projects", c.publicProjects).trim();
        c.refPanelPath         = p.getProperty("ref.panel.path", c.refPanelPath).trim();
        c.refPanelPopulation   = p.getProperty("ref.panel.population", c.refPanelPopulation).trim();
        c.gff3File             = p.getProperty("gff3.file", c.gff3File).trim();
        c.susiexPath           = p.getProperty("susiex.path", c.susiexPath).trim();
        c.retentionDays        = intProp(p, "retention.days", c.retentionDays);
        c.workspaceIdleDays    = intProp(p, "workspace.idle.days", c.workspaceIdleDays);
        c.maxUploadMb          = longProp(p, "max.upload.mb", c.maxUploadMb);
        c.maxProjectsPerUser   = intProp(p, "max.projects.per.user", c.maxProjectsPerUser);
        c.maxStoragePerUserMb  = longProp(p, "max.storage.per.user.mb", c.maxStoragePerUserMb);
        c.maxJobsPerUser       = intProp(p, "max.jobs.per.user", c.maxJobsPerUser);
        c.maxJobsTotal         = intProp(p, "max.jobs.total", c.maxJobsTotal);
        c.maxStorageTotalMb    = longProp(p, "max.storage.total.mb", c.maxStorageTotalMb);
        c.maxNewWorkspacesPerIpPerHour = intProp(p, "max.new.workspaces.per.ip.per.hour", c.maxNewWorkspacesPerIpPerHour);
        c.pepperEnv            = p.getProperty("pepper.env", c.pepperEnv).trim();
        c.validate();
        return c;
    }

    void validate() {
        if (dataDir.trim().isEmpty()) throw new IllegalArgumentException("data.dir must be set");
        if (!projectsDir.equals("projects"))
            throw new IllegalArgumentException("projects.dir must be 'projects' (symlink <app>/projects to move it)");
        if (retentionDays < 1) throw new IllegalArgumentException("retention.days must be >= 1");
        if (workspaceIdleDays < retentionDays)
            throw new IllegalArgumentException("workspace.idle.days must be at least retention.days");
        if (maxStorageTotalMb < 0) throw new IllegalArgumentException("max.storage.total.mb must be >= 0");
        if (maxNewWorkspacesPerIpPerHour < 1)
            throw new IllegalArgumentException("max.new.workspaces.per.ip.per.hour must be >= 1");
        if (trustProxyHops < 1 || trustProxyHops > 5) throw new IllegalArgumentException("trust.proxy.hops must be 1 to 5");
        if (!basePath.isEmpty() && !basePath.matches("(/[A-Za-z0-9._-]+)+"))
            throw new IllegalArgumentException("base.path must look like /lynxgwas (or be empty)");
        for (String o : publicOrigins)
            if (!o.matches("https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?"))
                throw new IllegalArgumentException("public.origin must be scheme://host[:port], without a path: " + o);
        if (secureCookies && !publicOrigin.startsWith("https://"))
            throw new IllegalArgumentException("secure.cookies=true needs an https:// public.origin");
    }

    public String secretFromEnv(String envName) {
        String v = System.getenv(envName);
        return v == null ? "" : v;
    }

    public File dataDir()     { return new File(dataDir); }
    public File projectsDir() { return new File(projectsDir); }

    private static int intProp(Properties p, String k, int d) {
        String v = p.getProperty(k); return v == null ? d : Integer.parseInt(v.trim());
    }
    private static long longProp(Properties p, String k, long d) {
        String v = p.getProperty(k); return v == null ? d : Long.parseLong(v.trim());
    }
    private static boolean boolProp(Properties p, String k, boolean d) {
        String v = p.getProperty(k); return v == null ? d : Boolean.parseBoolean(v.trim());
    }
    private static String stripSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
}
