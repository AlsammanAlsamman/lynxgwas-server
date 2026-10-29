import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anonymous private workspaces: no email, no password, no name, nothing that identifies a person.
 *
 * A workspace is created the first time a browser needs one (creating a project, opening
 * "My projects"). The browser keeps a random 256-bit device token in an HttpOnly cookie; the
 * server stores only a keyed digest of it, so a leaked data folder holds no usable token. The
 * CSRF token is derived from the device token, so nothing else needs storing.
 *
 * Optional workspace link: a separate random token, made only when the owner asks for it, that
 * opens the same workspace in another browser. Only its digest is stored, and making a new link
 * voids the old one.
 *
 * Workspaces persist as one properties file each under <dataDir>/workspaces. A workspace nobody
 * has used for {@code idleMs} is removed, together with any projects it still owns.
 */
public class WorkspaceService {

    public static class Workspace {
        public String id;
        public volatile long createdAt, lastSeenAt;
        final LinkedHashSet<String> devices = new LinkedHashSet<>();   // digests, oldest first
        String linkDigest = "";
    }

    /** What the security gate attaches to a request that carries a valid device token. */
    public static class Session {
        public final String userId, csrfToken;
        Session(String userId, String csrfToken) { this.userId = userId; this.csrfToken = csrfToken; }
    }

    /** Devices remembered per workspace; the oldest is dropped beyond this. */
    public static final int MAX_DEVICES = 20;
    /** Last-seen is written to disk at most this often per workspace. */
    static final long TOUCH_WRITE_INTERVAL_MS = 3600_000L;

    private final File dir;
    private final byte[] pepper;
    private final Clock clock;
    private final Map<String, Workspace> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByDevice = new ConcurrentHashMap<>();   // device digest -> workspace id
    private final Map<String, String> idByLink = new ConcurrentHashMap<>();     // link digest -> workspace id

    /** Called after a workspace is removed so its projects can be deleted too. */
    public interface RemovedListener { void removed(Workspace w); }
    private volatile RemovedListener removedListener = w -> {};

    public WorkspaceService(File dataDir, byte[] pepper, Clock clock) throws IOException {
        this.dir = new File(dataDir, "workspaces");
        this.pepper = pepper.clone();
        this.clock = clock;
        Files.createDirectories(dir.toPath());
        File[] files = dir.listFiles((d, n) -> n.endsWith(".properties"));
        if (files != null) for (File f : files) {
            Workspace w = read(f);
            if (w != null) index(w);
        }
    }

    public void setRemovedListener(RemovedListener l) { removedListener = l; }

    // ── Creating and using a workspace ──────────────────────────────────

    /** Creates a new workspace and returns the device token for this browser's cookie. */
    public synchronized String create() {
        Workspace w = new Workspace();
        do { w.id = Crypto.randomId(16); } while (byId.containsKey(w.id));
        w.createdAt = w.lastSeenAt = clock.millis();
        String token = Crypto.randomToken(32);
        w.devices.add(digest("device|" + token));
        write(w);
        index(w);
        return token;
    }

    /** The workspace session for a cookie token, or null. */
    public Session session(String token) {
        if (!plausible(token)) return null;
        String id = idByDevice.get(digest("device|" + token));
        Workspace w = id == null ? null : byId.get(id);
        if (w == null) return null;
        long now = clock.millis();
        if (now - w.lastSeenAt > TOUCH_WRITE_INTERVAL_MS) {
            synchronized (this) {
                w.lastSeenAt = now;
                // Last-seen is only a hint for the idle purge: a disk problem must not break requests
                try { if (byId.containsKey(w.id)) write(w); }
                catch (UncheckedIOException e) { System.err.println("[Workspaces] could not save last-seen: " + e.getMessage()); }
            }
        }
        return new Session(w.id, csrfFor(token));
    }

    /** Stops this browser from opening the workspace (the workspace and its link stay). */
    public synchronized void forgetDevice(String token) {
        if (!plausible(token)) return;
        String d = digest("device|" + token);
        String id = idByDevice.remove(d);
        Workspace w = id == null ? null : byId.get(id);
        if (w != null && w.devices.remove(d)) write(w);
    }

    // ── Workspace link (open the same workspace in another browser) ─────

    /** Makes a new link token for the workspace, voiding any earlier link. */
    public synchronized String newLink(String userId) {
        Workspace w = byId.get(userId == null ? "" : userId);
        if (w == null) return null;
        if (!w.linkDigest.isEmpty()) idByLink.remove(w.linkDigest);
        String token = Crypto.randomToken(32);
        w.linkDigest = digest("link|" + token);
        idByLink.put(w.linkDigest, w.id);
        write(w);
        return token;
    }

    /** Opens the workspace a link token belongs to; returns a new device token, or null. */
    public synchronized String openLink(String linkToken) {
        if (!plausible(linkToken)) return null;
        String id = idByLink.get(digest("link|" + linkToken));
        Workspace w = id == null ? null : byId.get(id);
        if (w == null) return null;
        String token = Crypto.randomToken(32);
        String d = digest("device|" + token);
        w.devices.add(d);
        idByDevice.put(d, w.id);
        while (w.devices.size() > MAX_DEVICES) {
            String oldest = w.devices.iterator().next();
            w.devices.remove(oldest);
            idByDevice.remove(oldest);
        }
        w.lastSeenAt = clock.millis();
        write(w);
        return token;
    }

    // ── Removal ─────────────────────────────────────────────────────────

    /** Deletes the workspace (and via the listener its projects) everywhere. */
    public void delete(String userId) {
        Workspace w = unlink(userId);
        if (w != null) removedListener.removed(w);   // project deletion is slow file IO: outside the lock
    }

    /** Removes workspaces nobody has used for {@code idleMs}. Returns how many. */
    public int purgeIdle(long idleMs) {
        List<Workspace> stale = new ArrayList<>();
        synchronized (this) {
            long now = clock.millis();
            for (Workspace w : new ArrayList<>(byId.values()))
                if (now - w.lastSeenAt > idleMs) stale.add(unlink(w.id));
        }
        for (Workspace w : stale) removedListener.removed(w);
        return stale.size();
    }

    /** Forgets a workspace's tokens and file; returns it (or null) so its projects can be deleted. */
    private synchronized Workspace unlink(String userId) {
        Workspace w = byId.remove(userId == null ? "" : userId);
        if (w == null) return null;
        for (String d : w.devices) idByDevice.remove(d);
        if (!w.linkDigest.isEmpty()) idByLink.remove(w.linkDigest);
        new File(dir, w.id + ".properties").delete();
        return w;
    }

    public Workspace workspace(String userId) { return userId == null ? null : byId.get(userId); }

    public int count() { return byId.size(); }

    // ── Internals ───────────────────────────────────────────────────────

    private static boolean plausible(String token) {
        return token != null && token.length() >= 32 && token.length() <= 100 && token.matches("[A-Za-z0-9_-]+");
    }

    private String digest(String value) { return Crypto.keyedDigest(pepper, value); }

    private String csrfFor(String deviceToken) { return digest("csrf|" + deviceToken); }

    private void index(Workspace w) {
        byId.put(w.id, w);
        for (String d : w.devices) idByDevice.put(d, w.id);
        if (!w.linkDigest.isEmpty()) idByLink.put(w.linkDigest, w.id);
    }

    private void write(Workspace w) {
        Properties p = new Properties();
        p.setProperty("id", w.id);
        p.setProperty("created", String.valueOf(w.createdAt));
        p.setProperty("last_seen", String.valueOf(w.lastSeenAt));
        p.setProperty("devices", String.join(",", w.devices));
        p.setProperty("link", w.linkDigest);
        try {
            File tmp = new File(dir, w.id + ".properties.tmp");
            try (Writer out = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) { p.store(out, null); }
            FileSafety.restrictToOwner(tmp);
            Files.move(tmp.toPath(), new File(dir, w.id + ".properties").toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save workspace", e);
        }
    }

    private static Workspace read(File f) {
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) { p.load(r); }
        catch (IOException e) { System.err.println("[Workspaces] unreadable " + f.getName()); return null; }
        Workspace w = new Workspace();
        w.id = p.getProperty("id");
        if (w.id == null || !w.id.matches("[a-z0-9]{16}")) return null;
        try {
            w.createdAt = Long.parseLong(p.getProperty("created", "0"));
            w.lastSeenAt = Long.parseLong(p.getProperty("last_seen", "0"));
        } catch (NumberFormatException e) { return null; }
        for (String d : p.getProperty("devices", "").split(",")) if (!d.isEmpty()) w.devices.add(d);
        w.linkDigest = p.getProperty("link", "");
        return w;
    }
}
