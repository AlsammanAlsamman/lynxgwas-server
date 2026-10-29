import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.regex.*;

/**
 * End-to-end test: starts the real server on a random local port and exercises it over HTTP as
 * a visitor without a workspace, two separate anonymous workspaces, a second browser opening a
 * workspace link, and a cross-site attacker.
 *
 * Runs from the app folder (it serves index.html from there) and uses a throwaway
 * projects/zz-http-test-public dataset plus a temp data folder; both are removed afterwards.
 */
public class ServerHttpTest {
    static int failures = 0;
    static String base, origin;
    static final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    static final String PUB = "zz-http-test-public";

    static void check(boolean ok, String msg) {
        System.out.println((ok ? "PASS: " : "FAIL: ") + msg);
        if (!ok) failures++;
    }

    /** One browser: its own workspace cookie and CSRF token. */
    static class Browser {
        String cookie = "", csrf = "";
        String origin = ServerHttpTest.origin;

        HttpResponse<String> send(String method, String path, String body, boolean withCsrf) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
            if (!cookie.isEmpty()) b.header("Cookie", cookie);
            if (origin != null && !method.equals("GET")) b.header("Origin", origin);
            if (withCsrf && !csrf.isEmpty()) b.header("X-Lynx-CSRF", csrf);
            b.header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            for (String sc : r.headers().allValues("Set-Cookie")) {
                String kv = sc.split(";", 2)[0];
                cookie = kv.endsWith("=") ? "" : kv;
            }
            return r;
        }
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, true); }
        HttpResponse<String> post(String path, String body) throws Exception { return send("POST", path, body, true); }

        void refreshCsrf() throws Exception {
            Matcher m = Pattern.compile("\"csrf\":\"([^\"]+)\"").matcher(get("/api/auth/me").body());
            csrf = m.find() ? m.group(1) : "";
        }
    }

    public static void main(String[] args) throws Exception {
        File data = Files.createTempDirectory("lynx-http").toFile();
        File pub = new File("projects", PUB);
        new File(pub, "data").mkdirs();
        Files.write(new File(pub, "data/manifest.json").toPath(), "{\"loci\":[]}".getBytes());

        int port;
        try (ServerSocket ss = new ServerSocket(0)) { port = ss.getLocalPort(); }
        ServerConfig cfg = new ServerConfig();
        cfg.bindHost = "127.0.0.1";
        cfg.port = port;
        cfg.publicOrigin = "http://127.0.0.1:" + port;
        cfg.dataDir = data.getPath();
        cfg.publicProjects = PUB;
        cfg.gff3File = "resources/test-annotation.gff3";   // any value: the test never parses it
        cfg.maxNewWorkspacesPerIpPerHour = 8;
        base = origin = cfg.publicOrigin;
        ServerContext ctx = new ServerContext(cfg, Clock.systemUTC());
        LocalServer server = new LocalServer(ctx, new ServerApi(ctx));
        server.start();
        List<String> created = new ArrayList<>();
        try {
            run(created);
        } finally {
            server.stop();
            FileSafety.deleteTree(pub);
            for (String id : created) FileSafety.deleteTree(new File("projects", id));
            FileSafety.deleteTree(data);
        }
        if (failures == 0) System.out.println("PASS: all ServerHttp tests passed");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void run(List<String> created) throws Exception {
        Browser anon = new Browser();

        // ── Static files and headers ──
        HttpResponse<String> home = anon.get("/");
        check(home.statusCode() == 200 && home.body().contains("lynx-server.js"), "home page served with the server script");
        check(home.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"), "CSP header present");
        check(home.headers().firstValue("X-Content-Type-Options").orElse("").equals("nosniff"), "nosniff header present");
        check(!home.headers().firstValue("Access-Control-Allow-Origin").isPresent(), "no CORS header");
        for (String p : new String[]{"/src/LocalServer.java", "/config/global.json", "/server.properties", "/projects/" + PUB + "/data/manifest.json",
                "/build.sh", "/DECISIONS.md", "/.agent_config.json", "/output/index.html", "/assets/../src/Main.java"})
            check(anon.get(p).statusCode() >= 400, "not served: " + p);
        check(anon.get("/%2e%2e/%2e%2e/etc/passwd").statusCode() == 400, "encoded traversal refused");
        check(anon.get("/assets/vendor/d3.v7.min.js").statusCode() == 200, "vendor script served");

        // ── Removed desktop endpoints ──
        for (String p : new String[]{"/api/agent/chat", "/api/agent/config", "/pick-file", "/pick-folder", "/save-pdf", "/api/global-config",
                "/api/peek-file-header?path=/etc/passwd", "/api/rsid-detect?gwas_file=/etc/passwd", "/api/github/token",
                "/api/shared-storage/test", "/api/export-projects-info", "/api/search/rebuild-index", "/manifest", "/progress"})
            check(anon.get(p).statusCode() == 404, "removed: " + p);
        check(anon.send("OPTIONS", "/api/projects", null, false).statusCode() == 405, "OPTIONS (CORS preflight) refused");

        // ── Public datasets: open to read, closed to write ──
        String list = anon.get("/api/projects").body();
        check(list.contains(PUB), "public dataset listed for anonymous visitors");
        check(anon.get("/api/project/" + PUB + "/manifest").statusCode() == 200, "public dataset readable anonymously");
        check(anon.get("/api/projects?scope=mine").statusCode() == 401, "'My projects' needs a workspace");
        check(anon.post("/api/project/" + PUB + "/config", "{\"col.chr\":\"x\"}").statusCode() == 403, "anonymous write to public dataset refused");
        check(anon.send("DELETE", "/api/project/" + PUB, null, false).statusCode() == 403, "anonymous delete of public dataset refused");
        check(anon.get("/api/project/%2E%2E/config").statusCode() == 400, "encoded '..' project id refused");
        check(anon.get("/api/project/does-not-exist/manifest").statusCode() == 404, "unknown project is 404");

        // ── Cross-site request forgery ──
        Browser evil = new Browser();
        evil.origin = "https://evil.example";
        check(evil.post("/api/auth/start", "{}").statusCode() == 403, "cross-origin POST refused");
        Browser noOrigin = new Browser();
        noOrigin.origin = null;
        check(noOrigin.post("/api/auth/start", "{}").statusCode() == 403, "POST without Origin or Fetch-Metadata refused");

        // ── The email/password endpoints are gone ──
        for (String p : new String[]{"register", "verify", "resend", "login", "logout", "forgot", "reset"})
            check(anon.post("/api/auth/" + p, "{\"email\":\"a@example.org\",\"password\":\"Blue-Tiger-Harbor-71\"}").statusCode() == 404,
                "removed: /api/auth/" + p);

        // ── Anonymous workspace: created on demand, nothing personal ──
        Browser alice = new Browser();
        String meBefore = alice.get("/api/auth/me").body();
        check(meBefore.contains("\"signed_in\":false") && alice.cookie.isEmpty(), "just visiting creates no workspace and no cookie");
        HttpResponse<String> st = alice.post("/api/auth/start", "{}");
        String setCookie = st.headers().firstValue("Set-Cookie").orElse("");
        check(st.statusCode() == 200 && setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Strict") && setCookie.contains("Max-Age="),
            "starting a workspace sets an HttpOnly, SameSite=Strict, persistent cookie");
        alice.refreshCsrf();
        check(!alice.csrf.isEmpty(), "workspace exposes a CSRF token to the page");
        String meAlice = alice.get("/api/auth/me").body();
        check(meAlice.contains("\"signed_in\":true") && !meAlice.contains("email") && !meAlice.contains("@"), "no email anywhere in the workspace info");
        String aliceCookie = alice.cookie;
        alice.post("/api/auth/start", "{}");
        check(alice.cookie.equals(aliceCookie), "starting again keeps the same workspace");
        check(anon.get("/api/auth/me").body().contains("\"signed_in\":false"),
            "one visitor's workspace never leaks into another visitor's request on the same route");
        check(anon.get("/api/projects?scope=mine").statusCode() == 401, "a visitor without a workspace still has none after others start one");

        // ── Own project: CSRF, create, upload, config ──
        check(alice.send("POST", "/api/my/projects", "{\"name\":\"x\"}", false).statusCode() == 403, "state change without CSRF token refused");
        HttpResponse<String> cp = alice.post("/api/my/projects", "{\"name\":\"Alice study\",\"description\":\"test\"}");
        Matcher idm = Pattern.compile("\"id\":\"(u-[a-z0-9]+)\"").matcher(cp.body());
        check(cp.statusCode() == 200 && idm.find(), "project created with a server-generated id");
        String pid = idm.group(1);
        created.add(pid);

        HttpRequest up = HttpRequest.newBuilder(URI.create(base + "/api/upload/" + pid + "/gwas"))
            .header("Cookie", alice.cookie).header("Origin", origin).header("X-Lynx-CSRF", alice.csrf)
            .POST(HttpRequest.BodyPublishers.ofString("CHR,BP,P,A1,A2\n1,1000,1e-9,A,G\n1,2000,0.3,C,T\n")).build();
        HttpResponse<String> upr = http.send(up, HttpResponse.BodyHandlers.ofString());
        check(upr.statusCode() == 200 && upr.body().contains("\"A2\""), "GWAS upload accepted and columns returned");
        check(new File("projects/" + pid + "/input/gwas.tsv").isFile(), "upload stored inside the project");

        HttpResponse<String> cfgPost = alice.post("/api/project/" + pid + "/config",
            "{\"gwas.file\":\"/etc/passwd\",\"ref.panel.path\":\"/tmp/x\",\"col.chr\":\"CHR\",\"col.pos\":\"BP\",\"col.pvalue\":\"P\",\"col.ea\":\"A1\",\"col.nea\":\"A2\"}");
        check(cfgPost.statusCode() == 200, "column mapping saved");
        String cfgGet = alice.get("/api/project/" + pid + "/config").body();
        check(cfgGet.contains("\"gwas.file\":\"gwas.tsv\"") && !cfgGet.contains("/etc/passwd") && !cfgGet.contains("/tmp/x"),
            "config ignores client paths and hides server paths");
        String onDisk = new String(Files.readAllBytes(new File("projects/" + pid + "/config.properties").toPath()), StandardCharsets.UTF_8);
        check(!onDisk.contains("/etc/passwd") && !onDisk.contains("/tmp/x"), "client paths never reach config.properties");
        String mine = alice.get("/api/projects?scope=mine").body();
        check(mine.contains(pid) && mine.contains("\"expires_at\":") && !mine.contains(PUB), "My projects lists only own projects, with expiry");
        check(!alice.get("/api/projects").body().contains(pid), "own project never appears in the public list");
        check(alice.post("/api/project/" + PUB + "/config", "{}").statusCode() == 403, "signed-in user still cannot write a public dataset");
        check(alice.get("/api/project/" + pid + "/download?file=exports/../config.properties").statusCode() == 400, "download traversal refused");
        check(alice.get("/api/project/" + pid + "/download?file=input/gwas.tsv").statusCode() == 400, "only exports are downloadable");

        // ── A second workspace sees nothing of Alice's ──
        Browser bob = new Browser();
        bob.post("/api/auth/start", "{}");
        bob.refreshCsrf();
        check(bob.get("/api/project/" + pid + "/config").statusCode() == 404, "another workspace cannot read the project (404, not 403)");
        check(bob.post("/api/delete-project", "{\"id\":\"" + pid + "\"}").statusCode() == 404, "another user cannot delete the project");
        check(bob.send("DELETE", "/api/project/" + pid, null, true).statusCode() == 404, "another user cannot DELETE the project");
        check(!bob.get("/api/projects?scope=mine").body().contains(pid), "another user's list does not include it");
        HttpRequest bobUp = HttpRequest.newBuilder(URI.create(base + "/api/upload/" + pid + "/gwas"))
            .header("Cookie", bob.cookie).header("Origin", origin).header("X-Lynx-CSRF", bob.csrf)
            .POST(HttpRequest.BodyPublishers.ofString("A\tB\n1\t2\n")).build();
        check(http.send(bobUp, HttpResponse.BodyHandlers.ofString()).statusCode() == 404, "another user cannot upload into it");
        check(bob.get("/api/export-progress?project=" + pid).statusCode() == 404, "another user cannot poll its jobs");
        check(bob.get("/api/project/" + pid + "/analysis/latest?locus_id=x").statusCode() == 404, "another user cannot read its fine-mapping results");
        check(bob.post("/api/project/" + pid + "/analysis/run-all", "{\"tool\":\"susie_finemapping\"}").statusCode() == 404, "another user cannot start a batch run on it");
        check(bob.post("/api/project/" + PUB + "/analysis/run-all", "{\"tool\":\"susie_finemapping\"}").statusCode() == 403, "batch runs on public datasets are refused (read-only)");
        check(anon.get("/api/project/" + PUB + "/analysis/run-result?locus_id=x&run_id=..%2F..%2Fconfig").statusCode() >= 400, "run-result refuses traversal run ids");
        check(anon.post("/api/mvsusie-run", "{\"job\":\"x\",\"locus\":\"1\"}").statusCode() == 401, "joint fine-mapping needs sign-in");
        check(bob.post("/api/mvsusie-run", "{\"job\":\"no-such-run\",\"locus\":\"1\",\"mode\":\"cross\"}").statusCode() == 404, "joint fine-mapping on an unknown or invisible run is 404");
        check(bob.get("/api/mvsusie-progress?id=anything").statusCode() == 404, "joint fine-mapping jobs are owner-only (progress)");
        check(bob.get("/api/mvsusie-result?id=anything").statusCode() == 404, "joint fine-mapping jobs are owner-only (result)");
        check(anon.post("/api/mvsusie-run-all", "{\"job\":\"x\"}").statusCode() == 401, "joint fine-mapping batch needs sign-in");
        check(bob.post("/api/mvsusie-run-all", "{\"job\":\"no-such-run\",\"mode\":\"within\"}").statusCode() == 404, "joint batch on an unknown or invisible run is 404");
        check(anon.get("/api/mvsusie-summary?job=no-such-run").statusCode() == 404, "joint summary of an unknown or invisible run is 404");
        check(anon.get("/api/mvsusie-latest?job=no-such-run&locus=1").statusCode() == 404, "saved joint runs of an unknown or invisible run are 404");
        check(anon.get("/api/mvsusie-for-dataset?project=" + pid + "&locus_index=1").statusCode() == 404, "joint runs of someone else's dataset are 404");
        check(anon.get("/api/mvsusie-for-dataset?project=" + PUB + "&locus_index=1").body().contains("\"runs\":[]"), "public dataset lists only visible joint runs");
        // Parallel requests cannot create more projects than allowed
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < 12; i++) ts.add(new Thread(() -> { try { bob.post("/api/my/projects", "{\"name\":\"race\"}"); } catch (Exception ignored) {} }));
        ts.forEach(Thread::start);
        for (Thread t : ts) t.join();
        Matcher bobIds = Pattern.compile("\"id\":\"(u-[a-z0-9]+)\"").matcher(bob.get("/api/projects?scope=mine").body());
        int bobCount = 0;
        while (bobIds.find()) { bobCount++; created.add(bobIds.group(1)); }
        check(bobCount == 5, "12 parallel project requests create exactly the 5 allowed (" + bobCount + ")");

        Browser bobStolen = new Browser();
        bobStolen.cookie = bob.cookie;
        bobStolen.csrf = alice.csrf;
        check(bobStolen.post("/api/my/projects", "{\"name\":\"y\"}").statusCode() == 403, "a CSRF token only works with its own session");

        // ── Workspace link: the same workspace in another browser ──
        HttpResponse<String> lr = alice.post("/api/my/link", "{}");
        Matcher lm = Pattern.compile("\"link\":\"([^\"]+)\"").matcher(lr.body());
        check(lr.statusCode() == 200 && lm.find() && lm.group(1).startsWith(origin + "/#workspace="), "workspace link made on request");
        String link = lm.group(1);
        check(anon.post("/api/my/link", "{}").statusCode() == 401, "no link without a workspace");
        Browser laptop = new Browser();
        check(laptop.post("/api/auth/open", "{\"link\":\"" + origin + "/#workspace=wrong-token-wrong-token-wrong-token-1\"}").statusCode() == 400,
            "a wrong link is refused");
        check(laptop.post("/api/auth/open", "{\"link\":\"" + link + "\"}").statusCode() == 200, "the link opens the workspace in another browser");
        laptop.refreshCsrf();
        check(laptop.get("/api/projects?scope=mine").body().contains(pid), "the other browser sees the same projects");
        check(!laptop.cookie.equals(alice.cookie), "each browser gets its own token");
        HttpResponse<String> lr2 = alice.post("/api/my/link", "{}");
        check(lr2.statusCode() == 200, "a new link can be made at any time");
        check(new Browser().post("/api/auth/open", "{\"link\":\"" + link + "\"}").statusCode() == 400, "a new link voids the old one");

        // ── Forget this browser ──
        check(laptop.post("/api/my/forget-browser", "{}").statusCode() == 200, "a browser can forget the workspace");
        check(laptop.get("/api/auth/me").body().contains("\"signed_in\":false"), "the forgotten browser has no workspace");
        check(laptop.post("/api/my/projects", "{\"name\":\"z\"}").statusCode() == 401, "the forgotten browser cannot create projects");
        check(alice.get("/api/projects?scope=mine").body().contains(pid), "the other browsers keep the workspace");

        // ── Delete the workspace ──
        check(alice.send("POST", "/api/my/delete-workspace", "{}", false).statusCode() == 403, "deleting needs the CSRF token");
        check(alice.post("/api/my/delete-workspace", "{}").statusCode() == 200, "workspace deleted");
        check(!new File("projects", pid).exists(), "deleting the workspace deleted its projects");
        check(alice.get("/api/auth/me").body().contains("\"signed_in\":false"), "the deleted workspace no longer opens");

        // ── Guessing links: wrong attempts are eventually refused ──
        int wrongRefused = 0;
        for (int i = 0; i < 15; i++)
            if (new Browser().post("/api/auth/open", "{\"link\":\"" + Crypto.randomToken(32) + "\"}").statusCode() == 429) wrongRefused++;
        check(wrongRefused > 0, "repeated wrong links from one IP are refused (" + wrongRefused + " of 15)");

        // ── New workspaces are rate-limited per IP (last: it uses up this IP's allowance) ──
        int refused = 0;
        for (int i = 0; i < 15; i++) if (new Browser().post("/api/auth/start", "{}").statusCode() == 429) refused++;
        check(refused > 0, "creating many workspaces from one IP is refused (" + refused + " of 15)");
    }
}
