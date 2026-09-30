import com.sun.net.httpserver.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Endpoints that exist only on the server: accounts, the caller's own projects, uploads and
 * downloads. The ported desktop endpoints stay in LocalServer.
 *
 *   POST /api/auth/register  {email,password}
 *   POST /api/auth/verify    {email,code}            -> signs in
 *   POST /api/auth/resend    {email,purpose}         purpose = verify | reset
 *   POST /api/auth/login     {email,password}        -> signs in
 *   POST /api/auth/logout
 *   POST /api/auth/forgot    {email}
 *   POST /api/auth/reset     {email,code,password}   -> signs in
 *   GET  /api/auth/me
 *   POST /api/my/projects    {name,description}      create an empty project
 *   GET  /api/my/usage
 *   POST /api/my/delete-account {password}
 *   POST /api/upload/{projectId}/gwas                raw body = the GWAS file (.tsv/.csv/.txt, optionally .gz)
 *   GET  /api/project/{id}/gwas-header               (via LocalServer's router)
 *   GET  /api/project/{id}/download?file=exports/... (via LocalServer's router)
 */
public class ServerApi {

    private final ServerContext ctx;

    public ServerApi(ServerContext ctx) { this.ctx = ctx; }

    public void register(HttpServer http) {
        SecurityGate g = ctx.gate;
        g.allowPrefix("/api/auth/", SecurityGate.Policy.PUBLIC);
        g.allowPrefix("/api/my/", SecurityGate.Policy.SIGNED_IN);
        g.allowPrefix("/api/upload/", SecurityGate.Policy.SIGNED_IN);
        g.uploadPrefix("/api/upload/");
        http.createContext("/api/auth/", this::auth).getFilters().add(g);
        http.createContext("/api/my/", this::my).getFilters().add(g);
        http.createContext("/api/upload/", this::upload).getFilters().add(g);
    }

    // ── /api/auth/* ─────────────────────────────────────────────────────

    private void auth(HttpExchange ex) throws IOException {
        String action = ex.getRequestURI().getPath().substring("/api/auth/".length());
        String method = ex.getRequestMethod();
        if (action.equals("me") && method.equalsIgnoreCase("GET")) { me(ex); return; }
        if (!method.equalsIgnoreCase("POST")) { SecurityGate.deny(ex, 405, "POST required"); return; }

        String ip = SecurityGate.clientIpOf(ex);
        if (!ctx.authLimiter.allow(ip)) { SecurityGate.deny(ex, 429, "Too many attempts. Wait a few minutes and try again."); return; }
        Map<String, Object> b = body(ex);
        if (b == null) { SecurityGate.deny(ex, 400, "Invalid request"); return; }
        String email = str(b, "email");
        AccountService.Outcome out;
        switch (action) {
            case "register":
                if (!ctx.mailLimiter.allow(ip)) { SecurityGate.deny(ex, 429, "Too many emails requested. Wait a few minutes."); return; }
                out = ctx.accounts.register(email, str(b, "password"));
                break;
            case "verify":
                out = ctx.accounts.verifyEmail(email, str(b, "code"));
                break;
            case "resend":
                if (!ctx.mailLimiter.allow(ip)) { SecurityGate.deny(ex, 429, "Too many emails requested. Wait a few minutes."); return; }
                out = ctx.accounts.resendCode(email, "reset".equals(str(b, "purpose"))
                    ? AccountService.Purpose.RESET_PASSWORD : AccountService.Purpose.VERIFY_EMAIL);
                break;
            case "login":
                out = ctx.accounts.login(email, str(b, "password"));
                break;
            case "logout":
                ctx.accounts.logout(SecurityGate.cookie(ex, ctx.gate.cookieName()));
                ex.getResponseHeaders().add("Set-Cookie", ctx.gate.sessionCookie("", 0));
                json(ex, 200, "{\"ok\":true}");
                return;
            case "forgot":
                if (!ctx.mailLimiter.allow(ip)) { SecurityGate.deny(ex, 429, "Too many emails requested. Wait a few minutes."); return; }
                out = ctx.accounts.requestPasswordReset(email);
                break;
            case "reset":
                out = ctx.accounts.resetPassword(email, str(b, "code"), str(b, "password"));
                break;
            default:
                SecurityGate.deny(ex, 404, "Not found");
                return;
        }
        if (out.sessionToken != null) {
            // Replace any session the browser already had (prevents session fixation)
            ctx.accounts.logout(SecurityGate.cookie(ex, ctx.gate.cookieName()));
            ex.getResponseHeaders().add("Set-Cookie",
                ctx.gate.sessionCookie(out.sessionToken, AccountService.SESSION_ABSOLUTE_MS / 1000));
        }
        json(ex, out.ok ? 200 : 400, "{\"ok\":" + out.ok + ",\"message\":" + q(out.message) + "}");
    }

    private void me(HttpExchange ex) throws IOException {
        AccountService.Session s = SecurityGate.session(ex);
        AccountService.Account a = s == null ? null : ctx.accounts.account(s.userId);
        StringBuilder j = new StringBuilder("{");
        j.append("\"signed_in\":").append(a != null);
        if (a != null) {
            j.append(",\"email\":").append(q(a.email));
            j.append(",\"csrf\":").append(q(s.csrfToken));
        }
        j.append(",\"retention_days\":").append(ctx.cfg.retentionDays);
        j.append(",\"max_projects\":").append(ctx.cfg.maxProjectsPerUser);
        j.append(",\"max_upload_mb\":").append(ctx.cfg.maxUploadMb);
        j.append("}");
        json(ex, 200, j.toString());
    }

    // ── /api/my/* ───────────────────────────────────────────────────────

    private void my(HttpExchange ex) throws IOException {
        String action = ex.getRequestURI().getPath().substring("/api/my/".length());
        String userId = SecurityGate.userId(ex);
        if (action.equals("usage") && ex.getRequestMethod().equalsIgnoreCase("GET")) {
            json(ex, 200, "{\"projects\":" + ctx.registry.ownedBy(userId).size()
                + ",\"max_projects\":" + ctx.cfg.maxProjectsPerUser
                + ",\"storage_mb\":" + (ctx.registry.storageUsedBy(userId) >> 20)
                + ",\"max_storage_mb\":" + ctx.cfg.maxStoragePerUserMb + "}");
            return;
        }
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { SecurityGate.deny(ex, 405, "POST required"); return; }
        Map<String, Object> b = body(ex);
        if (b == null) { SecurityGate.deny(ex, 400, "Invalid request"); return; }

        if (action.equals("projects")) {
            if (ctx.registry.ownedBy(userId).size() >= ctx.cfg.maxProjectsPerUser) {
                SecurityGate.deny(ex, 403, "You already have " + ctx.cfg.maxProjectsPerUser
                    + " projects. Delete one (or wait for one to expire) to create another."); return;
            }
            String name = str(b, "name").trim(), desc = str(b, "description").trim();
            if (name.isEmpty() || name.length() > 80 || desc.length() > 500 || hasControl(name) || hasControl(desc)) {
                SecurityGate.deny(ex, 400, "Give the project a name (up to 80 characters) and an optional description (up to 500)."); return;
            }
            ProjectRegistry.Entry e = ctx.registry.create(userId, name);
            File dir = new File(ctx.cfg.projectsDir(), e.projectId);
            writeMeta(dir, name, desc);
            json(ex, 200, "{\"ok\":true,\"id\":" + q(e.projectId) + ",\"expires_at\":" + e.expiresAt + "}");
            return;
        }
        if (action.equals("delete-account")) {
            AccountService.Account a = ctx.accounts.account(userId);
            String password = str(b, "password");
            if (a == null || !Crypto.verifyPassword(password.toCharArray(), a.passwordHash)) {
                SecurityGate.deny(ex, 403, "Password is incorrect."); return;
            }
            String email = a.email;
            ctx.accounts.deleteAccount(userId);          // also deletes every project of the account
            ex.getResponseHeaders().add("Set-Cookie", ctx.gate.sessionCookie("", 0));
            ctx.mailer.send(email, "LYNXgwas: account deleted",
                "Your LYNXgwas account and all of its projects have been deleted, as you requested.\n");
            json(ex, 200, "{\"ok\":true}");
            return;
        }
        SecurityGate.deny(ex, 404, "Not found");
    }

    /** project.json with the display name/description, so listings and exports show them. */
    static void writeMeta(File dir, String name, String desc) throws IOException {
        ProjectMetadata pm = ProjectMetadata.load(dir.getAbsolutePath());
        if (pm == null) pm = new ProjectMetadata();
        pm.id = dir.getName();
        pm.name = name;
        pm.description = desc;
        pm.save(dir.getAbsolutePath());
    }

    // ── /api/upload/{projectId}/gwas ────────────────────────────────────

    private void upload(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { SecurityGate.deny(ex, 405, "POST required"); return; }
        String[] parts = ex.getRequestURI().getPath().substring("/api/upload/".length()).split("/");
        if (parts.length != 2 || !parts[1].equals("gwas")) { SecurityGate.deny(ex, 404, "Not found"); return; }
        String projectId = parts[0];
        if (!ctx.gate.requireProject(ex, projectId, true)) return;
        String userId = SecurityGate.userId(ex);

        long used = ctx.registry.storageUsedBy(userId);
        long quota = ctx.cfg.maxStoragePerUserMb << 20;
        long room = quota - used;
        if (room <= 0) { SecurityGate.deny(ex, 413, "Your storage quota is full. Delete a project first."); return; }

        File projectDir = new File(ctx.cfg.projectsDir(), projectId);
        File inputDir = new File(projectDir, "input");
        Files.createDirectories(inputDir.toPath());
        File tmp = new File(inputDir, "upload-" + Crypto.randomId(8) + ".part");
        File dest = new File(inputDir, "gwas.tsv");
        long written;
        try (InputStream raw = new BufferedInputStream(ex.getRequestBody(), 1 << 16)) {
            written = normalizeToTsv(raw, tmp, Math.min(room, ctx.cfg.maxUploadMb * 8L << 20));
        } catch (IOException e) {
            tmp.delete();
            SecurityGate.deny(ex, 400, "Upload rejected: " + e.getMessage()); return;
        }
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        // Point the project at its upload (the only way gwas.file is ever set on the server)
        File cfgFile = new File(projectDir, "config.properties");
        Config c = cfgFile.exists() ? Config.loadFromProject(projectDir.getAbsolutePath()) : new Config();
        c.gwasFile = dest.getAbsolutePath();
        c.gff3File = ctx.cfg.gff3File;
        c.refPanelPath = ctx.cfg.refPanelPath;
        c.refPanelPopulation = ctx.cfg.refPanelPopulation;
        c.ldEnabled = !ctx.cfg.refPanelPath.isEmpty();
        if (!cfgFile.exists()) c.lociFile = "";
        c.writeProperties(cfgFile.getAbsolutePath());

        json(ex, 200, "{\"ok\":true,\"bytes\":" + written + ",\"columns\":" + columnsJson(dest) + "}");
    }

    /**
     * Copies an uploaded GWAS table to {@code out} as tab-separated UTF-8 text: gunzips when the
     * body is gzip, turns comma/space/semicolon-separated files into tabs, drops CRs, and refuses
     * binary content. Stops at {@code maxBytes} of output (guards against gzip bombs and quotas).
     */
    static long normalizeToTsv(InputStream in, File out, long maxBytes) throws IOException {
        in.mark(4);
        int b0 = in.read(), b1 = in.read();
        in.reset();
        InputStream text = (b0 == 0x1f && b1 == 0x8b) ? new GZIPInputStream(in, 1 << 16) : in;
        long total = 0, lines = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(text, StandardCharsets.UTF_8), 1 << 20);
             Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8), 1 << 20)) {
            String header = br.readLine();
            if (header == null) throw new IOException("the file is empty");
            if (header.startsWith("﻿")) header = header.substring(1);
            String delim = header.contains("\t") ? "\t" : header.contains(",") ? "," : header.contains(";") ? ";" : "\\s+";
            String line = header;
            while (line != null) {
                if (line.length() > 1_000_000) throw new IOException("a line is longer than 1 MB; is this really a GWAS table?");
                for (int i = 0; i < line.length(); i++) {
                    char ch = line.charAt(i);
                    if (ch == 0 || (ch < 0x20 && ch != '\t' && ch != '\r')) throw new IOException("the file contains binary data");
                }
                String row = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                if (!delim.equals("\t")) row = String.join("\t", row.trim().split(delim, -1));
                if (!row.isEmpty()) {
                    w.write(row); w.write('\n');
                    total += row.length() + 1;
                    lines++;
                }
                if (total > maxBytes) throw new IOException("the file is larger than your remaining allowance");
                line = br.readLine();
            }
        }
        if (lines < 2) throw new IOException("the file has a header but no data rows");
        return total;
    }

    // ── Project-scoped helpers called from LocalServer's router ─────────

    /** GET /api/project/{id}/gwas-header — columns of the uploaded GWAS file. */
    public void gwasHeader(HttpExchange ex, File projectDir) throws IOException {
        File f = new File(projectDir, "input/gwas.tsv");
        if (!f.isFile()) { SecurityGate.deny(ex, 404, "No GWAS file uploaded yet"); return; }
        json(ex, 200, "{\"columns\":" + columnsJson(f) + "}");
    }

    /**
     * GET /api/project/{id}/download?file=exports/<name> — files the server wrote into the
     * project's exports folder. Nothing else in the project folder is downloadable this way.
     */
    public void download(HttpExchange ex, File projectDir) throws IOException {
        String q = ex.getRequestURI().getRawQuery();
        String name = null;
        if (q != null) for (String kv : q.split("&"))
            if (kv.startsWith("file=")) name = java.net.URLDecoder.decode(kv.substring(5), "UTF-8");
        if (name == null || !name.startsWith("exports/") || name.indexOf('/', 8) >= 0 || !name.matches("exports/[A-Za-z0-9._-]{1,200}")) {
            SecurityGate.deny(ex, 400, "Bad file name"); return;
        }
        File exportsDir = new File(projectDir, "exports");
        File f;
        try { f = FileSafety.within(exportsDir, name.substring(8)); }
        catch (IOException e) { SecurityGate.deny(ex, 400, "Bad file name"); return; }
        if (!f.isFile()) { SecurityGate.deny(ex, 404, "Not found"); return; }
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/octet-stream");
        h.set("Content-Disposition", "attachment; filename=\"" + f.getName() + "\"");
        ex.sendResponseHeaders(200, f.length());
        try (OutputStream os = ex.getResponseBody()) { Files.copy(f.toPath(), os); }
    }

    // ── Small utilities ─────────────────────────────────────────────────

    static String columnsJson(File tsv) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(tsv), StandardCharsets.UTF_8))) {
            String h = br.readLine();
            StringBuilder j = new StringBuilder("[");
            if (h != null) {
                String[] cols = h.split("\t");
                for (int i = 0; i < cols.length && i < 200; i++) {
                    if (i > 0) j.append(',');
                    j.append(q(cols[i].trim()));
                }
            }
            return j.append(']').toString();
        }
    }

    static Map<String, Object> body(HttpExchange ex) {
        try {
            byte[] bytes = ex.getRequestBody().readAllBytes();
            if (bytes.length > 64 * 1024) return null;
            return MiniJson.asObject(MiniJson.parse(new String(bytes, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    static String str(Map<String, Object> b, String key) {
        Object v = b.get(key);
        return v instanceof String ? (String) v : "";
    }

    static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isISOControl(s.charAt(i))) return true;
        return false;
    }

    static String q(String s) { return MiniJson.encode(s == null ? "" : s); }

    static void json(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
