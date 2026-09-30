import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Settings for the online (multi-user) server, read from a properties file outside the repo.
 *
 * Location: the LYNX_SERVER_CONFIG environment variable, else ./server.properties.
 * Secrets (SMTP password, code pepper) are read from environment variables named in the file,
 * never stored in the file itself, so the file can be kept in version control or backups.
 * See server.properties.example for every key.
 */
public class ServerConfig {

    /** Address to bind. Keep 127.0.0.1 and put a TLS reverse proxy (Caddy/nginx) in front. */
    public String bindHost = "127.0.0.1";
    public int    port     = 8765;
    /** Public origin users reach, e.g. https://lynxgwas.example.org — used for CSRF Origin checks and email links. */
    public String publicOrigin = "http://localhost:8765";
    /** True when served over HTTPS (behind the proxy): Secure cookies, __Host- prefix, HSTS. */
    public boolean secureCookies = false;
    /** Honour X-Forwarded-For for client IPs (rate limiting). Only true when behind your own proxy. */
    public boolean trustProxy = false;

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
    /** A warning email goes out this many days before deletion. */
    public int retentionWarnDays = 3;
    /** Unverified accounts are removed after this many hours. */
    public int unverifiedAccountHours = 48;

    /** Upload / quota limits. */
    public long maxUploadMb = 2048;
    public int  maxProjectsPerUser = 5;
    public long maxStoragePerUserMb = 8192;
    /** Heavy background jobs (processing, analyses) running at once, per user and in total. */
    public int  maxJobsPerUser = 1;
    public int  maxJobsTotal = 4;

    /** SMTP. Empty host = development mode: emails are written to <dataDir>/outbox instead of sent. */
    public String smtpHost = "";
    public int    smtpPort = 587;
    public String smtpUser = "";
    /** Name of the environment variable holding the SMTP password. */
    public String smtpPasswordEnv = "LYNX_SMTP_PASSWORD";
    /** "starttls" (port 587) or "ssl" (port 465). Plain SMTP is refused. */
    public String smtpSecurity = "starttls";
    public String mailFrom = "LYNXgwas <no-reply@localhost>";

    /** Name of the environment variable holding a random secret mixed into stored code hashes. */
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
        c.publicOrigin         = stripSlash(p.getProperty("public.origin", c.publicOrigin).trim());
        c.secureCookies        = boolProp(p, "secure.cookies", c.secureCookies);
        c.trustProxy           = boolProp(p, "trust.proxy", c.trustProxy);
        c.dataDir              = p.getProperty("data.dir", c.dataDir).trim();
        c.projectsDir          = p.getProperty("projects.dir", c.projectsDir).trim();
        c.publicProjects       = p.getProperty("public.projects", c.publicProjects).trim();
        c.refPanelPath         = p.getProperty("ref.panel.path", c.refPanelPath).trim();
        c.refPanelPopulation   = p.getProperty("ref.panel.population", c.refPanelPopulation).trim();
        c.gff3File             = p.getProperty("gff3.file", c.gff3File).trim();
        c.susiexPath           = p.getProperty("susiex.path", c.susiexPath).trim();
        c.retentionDays        = intProp(p, "retention.days", c.retentionDays);
        c.retentionWarnDays    = intProp(p, "retention.warn.days", c.retentionWarnDays);
        c.unverifiedAccountHours = intProp(p, "unverified.account.hours", c.unverifiedAccountHours);
        c.maxUploadMb          = longProp(p, "max.upload.mb", c.maxUploadMb);
        c.maxProjectsPerUser   = intProp(p, "max.projects.per.user", c.maxProjectsPerUser);
        c.maxStoragePerUserMb  = longProp(p, "max.storage.per.user.mb", c.maxStoragePerUserMb);
        c.maxJobsPerUser       = intProp(p, "max.jobs.per.user", c.maxJobsPerUser);
        c.maxJobsTotal         = intProp(p, "max.jobs.total", c.maxJobsTotal);
        c.smtpHost             = p.getProperty("smtp.host", c.smtpHost).trim();
        c.smtpPort             = intProp(p, "smtp.port", c.smtpPort);
        c.smtpUser             = p.getProperty("smtp.user", c.smtpUser).trim();
        c.smtpPasswordEnv      = p.getProperty("smtp.password.env", c.smtpPasswordEnv).trim();
        c.smtpSecurity         = p.getProperty("smtp.security", c.smtpSecurity).trim().toLowerCase(Locale.ROOT);
        c.mailFrom             = p.getProperty("mail.from", c.mailFrom).trim();
        c.pepperEnv            = p.getProperty("pepper.env", c.pepperEnv).trim();
        c.validate();
        return c;
    }

    void validate() {
        if (dataDir.trim().isEmpty()) throw new IllegalArgumentException("data.dir must be set");
        if (!projectsDir.equals("projects"))
            throw new IllegalArgumentException("projects.dir must be 'projects' (symlink <app>/projects to move it)");
        if (retentionDays < 1) throw new IllegalArgumentException("retention.days must be >= 1");
        if (retentionWarnDays < 0 || retentionWarnDays >= retentionDays)
            throw new IllegalArgumentException("retention.warn.days must be between 0 and retention.days-1");
        if (!smtpSecurity.equals("starttls") && !smtpSecurity.equals("ssl"))
            throw new IllegalArgumentException("smtp.security must be starttls or ssl");
        if (secureCookies && !publicOrigin.startsWith("https://"))
            throw new IllegalArgumentException("secure.cookies=true needs an https:// public.origin");
    }

    public boolean devMail() { return smtpHost.isEmpty(); }

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
