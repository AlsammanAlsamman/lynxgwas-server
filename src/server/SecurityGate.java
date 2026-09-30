import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/**
 * Front door for every request: security headers, per-IP rate limit, route allowlist, session
 * lookup, CSRF check and request-size limit. Project-level permissions are NOT decided here;
 * handlers call {@link #requireProject} where they resolve a project id, so the check always uses
 * the exact id the handler acts on.
 *
 * Route policy is an allowlist: a path that is not registered is answered 404 before any handler
 * runs, so a desktop-only endpoint that was missed in the port cannot be reached by accident.
 */
public class SecurityGate extends Filter {

    public enum Policy { PUBLIC, SIGNED_IN }

    /**
     * Per-request state. NOT HttpExchange attributes: in the JDK server those live on the
     * HttpContext and are shared by every request to the same route, which would leak one user's
     * session into other people's requests. Keyed by the exchange object itself (identity) and
     * removed when the request finishes.
     */
    private static final java.util.concurrent.ConcurrentHashMap<HttpExchange, RequestInfo> REQUESTS =
        new java.util.concurrent.ConcurrentHashMap<>();

    static final class RequestInfo {
        final String ip;
        volatile AccountService.Session session;
        RequestInfo(String ip) { this.ip = ip; }
    }
    public static final String CSRF_HEADER = "X-Lynx-CSRF";
    public static final long JSON_LIMIT = 4L << 20;   // 4 MB for ordinary requests

    private final ServerConfig cfg;
    private final AccountService accounts;
    private final ProjectRegistry registry;
    private final RequestLimiter perIp;
    private final NavigableMap<String, Policy> exact = new TreeMap<>();
    private final NavigableMap<String, Policy> prefixes = new TreeMap<>();
    private final Set<String> uploadPrefixes = new HashSet<>();
    private final String cookieName;

    public SecurityGate(ServerConfig cfg, AccountService accounts, ProjectRegistry registry, Clock clock) {
        this.cfg = cfg;
        this.accounts = accounts;
        this.registry = registry;
        this.perIp = new RequestLimiter(600, 300, clock);
        this.cookieName = cfg.secureCookies ? "__Host-lynx_session" : "lynx_session";
    }

    public SecurityGate allowExact(String path, Policy p)  { exact.put(path, p); return this; }
    public SecurityGate allowPrefix(String prefix, Policy p) { prefixes.put(prefix, p); return this; }
    /** Paths under this prefix may carry large bodies (up to max.upload.mb). */
    public SecurityGate uploadPrefix(String prefix) { uploadPrefixes.add(prefix); return this; }

    @Override public String description() { return "LYNXgwas security gate"; }

    @Override
    public void doFilter(HttpExchange ex, Chain chain) throws IOException {
        try {
            addSecurityHeaders(ex);
            String ip = clientIp(ex);
            RequestInfo info = new RequestInfo(ip);
            REQUESTS.put(ex, info);
            if (!perIp.allow(ip)) { deny(ex, 429, "Too many requests. Slow down and try again shortly."); return; }

            String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            if (!method.equals("GET") && !method.equals("HEAD") && !method.equals("POST") && !method.equals("DELETE")) {
                deny(ex, 405, "Method not allowed"); return;
            }

            String path = ex.getRequestURI().getRawPath();
            if (path == null || path.contains("..") || path.contains("%2e") || path.contains("%2E")
                    || path.contains("%2f") || path.contains("%2F") || path.contains("%5c") || path.contains("%5C")
                    || path.indexOf('\\') >= 0 || path.contains("//")) {
                deny(ex, 400, "Bad path"); return;
            }
            Policy policy = policyFor(path);
            if (policy == null) { deny(ex, 404, "Not found"); return; }

            AccountService.Session session = accounts.session(cookie(ex, cookieName));
            info.session = session;
            if (policy == Policy.SIGNED_IN && session == null) { deny(ex, 401, "Please sign in."); return; }

            boolean stateChanging = method.equals("POST") || method.equals("DELETE");
            if (stateChanging && !sameOrigin(ex)) { deny(ex, 403, "Cross-site request refused."); return; }
            if (stateChanging && session != null && !path.startsWith("/api/auth/")) {
                String sent = ex.getRequestHeaders().getFirst(CSRF_HEADER);
                if (!Crypto.constantTimeEquals(sent, session.csrfToken)) { deny(ex, 403, "Missing or invalid security token. Reload the page."); return; }
            }

            long limit = isUpload(path) ? cfg.maxUploadMb << 20 : JSON_LIMIT;
            String cl = ex.getRequestHeaders().getFirst("Content-Length");
            if (cl != null) {
                try { if (Long.parseLong(cl.trim()) > limit) { deny(ex, 413, "Request too large."); return; } }
                catch (NumberFormatException e) { deny(ex, 400, "Bad Content-Length"); return; }
            }
            ex.setStreams(new LimitedInputStream(ex.getRequestBody(), limit), null);

            chain.doFilter(ex);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            // Never leak stack traces or internal paths to the client
            System.err.printf("[Gate] %s %s failed: %s%n", ex.getRequestMethod(), ex.getRequestURI().getPath(), e);
            try { deny(ex, 500, "Internal error"); } catch (IOException ignored) {}
        } finally {
            REQUESTS.remove(ex);
        }
    }

    // ── Helpers used by handlers ────────────────────────────────────────

    public static AccountService.Session session(HttpExchange ex) {
        RequestInfo info = REQUESTS.get(ex);
        return info == null ? null : info.session;
    }

    public static String clientIpOf(HttpExchange ex) {
        RequestInfo info = REQUESTS.get(ex);
        return info == null ? "unknown" : info.ip;
    }

    public static String userId(HttpExchange ex) {
        AccountService.Session s = session(ex);
        return s == null ? null : s.userId;
    }

    /**
     * Checks access to {@code projectId}; on failure sends the error response and returns false.
     * Projects the caller may not see answer 404 (not 403) so ids of private projects don't leak.
     */
    public boolean requireProject(HttpExchange ex, String projectId, boolean write) throws IOException {
        ProjectRegistry.Access a = registry.access(projectId, userId(ex));
        if (a == ProjectRegistry.Access.NONE) { deny(ex, 404, "Project not found"); return false; }
        if (write && a != ProjectRegistry.Access.OWNER) {
            deny(ex, 403, registry.isPublic(projectId)
                ? "Public datasets are read-only. Create your own project to run this."
                : "Not allowed"); return false;
        }
        return true;
    }

    /** Same check without sending a response (for filtering lists). */
    public boolean canRead(HttpExchange ex, String projectId) {
        return registry.access(projectId, userId(ex)) != ProjectRegistry.Access.NONE;
    }

    public String cookieName() { return cookieName; }

    public String sessionCookie(String token, long maxAgeSeconds) {
        return cookieName + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAgeSeconds
            + (cfg.secureCookies ? "; Secure" : "");
    }

    public static void deny(HttpExchange ex, int code, String message) throws IOException {
        byte[] body = ("{\"error\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    // ── Internals ───────────────────────────────────────────────────────

    Policy policyFor(String path) {
        Policy p = exact.get(path);
        if (p != null) return p;
        Map.Entry<String, Policy> e = prefixes.floorEntry(path);
        while (e != null) {
            if (path.startsWith(e.getKey())) return e.getValue();
            e = prefixes.lowerEntry(e.getKey());
        }
        return null;
    }

    private boolean isUpload(String path) {
        for (String p : uploadPrefixes) if (path.startsWith(p)) return true;
        return false;
    }

    private boolean sameOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null) return origin.equals(cfg.publicOrigin);
        // Browsers always send Origin on cross-site POST; without it, fall back to Fetch Metadata
        String site = ex.getRequestHeaders().getFirst("Sec-Fetch-Site");
        return "same-origin".equals(site);
    }

    private String clientIp(HttpExchange ex) {
        if (cfg.trustProxy) {
            String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isEmpty()) {
                String[] hops = xff.split(",");
                return hops[hops.length - 1].trim();   // the hop our own proxy appended
            }
        }
        InetSocketAddress a = ex.getRemoteAddress();
        return a == null ? "unknown" : a.getAddress().getHostAddress();
    }

    static String cookie(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String h : headers)
            for (String part : h.split(";")) {
                String kv = part.trim();
                if (kv.startsWith(name + "=")) return kv.substring(name.length() + 1);
            }
        return null;
    }

    private void addSecurityHeaders(HttpExchange ex) {
        Headers h = ex.getResponseHeaders();
        // The UI still uses inline scripts/handlers, so scripts need 'unsafe-inline'; everything else is locked down.
        h.set("Content-Security-Policy",
            "default-src 'self'; script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://cdnjs.cloudflare.com https://unpkg.com; "
            + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://cdn.jsdelivr.net; font-src 'self' https://fonts.gstatic.com data:; "
            + "img-src 'self' data: blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        h.set("Cross-Origin-Resource-Policy", "same-origin");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
        h.set("Cache-Control", "no-store");
        if (cfg.secureCookies) h.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    }

    /** Stops reading after {@code limit} bytes, so a missing/lying Content-Length can't exhaust memory or disk. */
    static class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream in, long limit) { super(in); remaining = limit; }
        @Override public int read() throws IOException {
            if (remaining <= 0) throw new IOException("request body too large");
            int b = super.read(); if (b >= 0) remaining--; return b;
        }
        @Override public int read(byte[] buf, int off, int len) throws IOException {
            if (remaining <= 0) throw new IOException("request body too large");
            int n = super.read(buf, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }
    }
}
