import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who owns which project, and when each user project expires.
 *
 * Records live in <dataDir>/registry/<projectId>.properties, outside the projects folder, so
 * nothing a user uploads or edits can change ownership. A project folder with no record is
 * public (read-only) when listed in public.projects, or when public.projects is "*".
 *
 * Retention: every user project expires {@code retentionDays} after creation. The sweep emails
 * the owner {@code warnDays} before, then deletes the folder and emails a deletion notice.
 */
public class ProjectRegistry {

    public static class Entry {
        public String projectId, ownerId, name;
        public long createdAt, expiresAt;
        public boolean warned;
    }

    public enum Access { NONE, READ, OWNER }

    private final File registryDir;
    private final File projectsDir;
    private final Set<String> explicitPublic;
    private final boolean allUnownedPublic;
    private final Clock clock;
    private final int retentionDays, warnDays;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public ProjectRegistry(ServerConfig cfg, Clock clock) throws IOException {
        this.registryDir = new File(cfg.dataDir(), "registry");
        this.projectsDir = cfg.projectsDir();
        this.clock = clock;
        this.retentionDays = cfg.retentionDays;
        this.warnDays = cfg.retentionWarnDays;
        this.allUnownedPublic = cfg.publicProjects.equals("*");
        this.explicitPublic = new HashSet<>();
        if (!allUnownedPublic)
            for (String s : cfg.publicProjects.split(",")) if (!s.trim().isEmpty()) explicitPublic.add(s.trim());
        Files.createDirectories(registryDir.toPath());
        File[] files = registryDir.listFiles((d, n) -> n.endsWith(".properties"));
        if (files != null) for (File f : files) {
            Entry e = read(f);
            if (e != null) entries.put(e.projectId, e);
        }
    }

    // ── Access decisions ────────────────────────────────────────────────

    public boolean isPublic(String projectId) {
        if (!FileSafety.validProjectId(projectId) || entries.containsKey(projectId)) return false;
        if (!new File(projectsDir, projectId).isDirectory()) return false;
        return allUnownedPublic || explicitPublic.contains(projectId);
    }

    public Entry entry(String projectId) { return projectId == null ? null : entries.get(projectId); }

    public Access access(String projectId, String userId) {
        if (!FileSafety.validProjectId(projectId)) return Access.NONE;
        Entry e = entries.get(projectId);
        if (e != null) return userId != null && e.ownerId.equals(userId) ? Access.OWNER : Access.NONE;
        return isPublic(projectId) ? Access.READ : Access.NONE;
    }

    public List<String> publicIds() {
        List<String> ids = new ArrayList<>();
        File[] dirs = projectsDir.listFiles(File::isDirectory);
        if (dirs != null) for (File d : dirs) if (isPublic(d.getName())) ids.add(d.getName());
        Collections.sort(ids);
        return ids;
    }

    public List<Entry> ownedBy(String userId) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.values()) if (e.ownerId.equals(userId)) out.add(e);
        out.sort(Comparator.comparingLong(e -> e.createdAt));
        return out;
    }

    // ── Creation and deletion ───────────────────────────────────────────

    /** Allocates a fresh project id owned by {@code userId} and creates its (empty) folder. */
    public synchronized Entry create(String userId, String name) throws IOException {
        Entry e = new Entry();
        do { e.projectId = "u-" + Crypto.randomId(12); }
        while (entries.containsKey(e.projectId) || new File(projectsDir, e.projectId).exists());
        e.ownerId = userId;
        e.name = name;
        e.createdAt = clock.millis();
        e.expiresAt = e.createdAt + retentionDays * 86_400_000L;
        write(e);                                   // record first: a folder without a record would read as public
        entries.put(e.projectId, e);
        Files.createDirectories(new File(projectsDir, e.projectId).toPath());
        return e;
    }

    public synchronized void delete(String projectId) throws IOException {
        Entry e = entries.get(projectId);
        if (e == null) throw new IOException("not a user project");
        File dir = new File(projectsDir, projectId);
        FileSafety.deleteTree(dir);                 // folder first: a record without a folder is harmless
        entries.remove(projectId);
        new File(registryDir, projectId + ".properties").delete();
    }

    public long storageUsedBy(String userId) {
        long total = 0;
        for (Entry e : ownedBy(userId)) total += FileSafety.sizeOf(new File(projectsDir, e.projectId));
        return total;
    }

    // ── Retention ───────────────────────────────────────────────────────

    /** Fraction of the retention period left, 1.0 = just created, 0.0 = due for deletion. */
    public double remainingFraction(Entry e) {
        double total = e.expiresAt - e.createdAt;
        return total <= 0 ? 0 : Math.max(0, Math.min(1, (e.expiresAt - clock.millis()) / total));
    }

    /**
     * Sends due warnings and deletes expired projects. Emails go through {@code notify}
     * (ownerId, subject, body). Returns the number of projects deleted.
     */
    public int sweep(Notifier notify) {
        long now = clock.millis();
        int deleted = 0;
        for (Entry e : new ArrayList<>(entries.values())) {
            try {
                if (now >= e.expiresAt) {
                    delete(e.projectId);
                    deleted++;
                    notify.send(e.ownerId, "project deleted",
                        "Your project \"" + e.name + "\" reached the end of its " + retentionDays + "-day storage period "
                        + "and has been permanently deleted from the server, together with its uploaded data and results.\n\n"
                        + "Projects on this server are kept for " + retentionDays + " days. Download anything you want to keep "
                        + "before the counter runs out.\n");
                } else if (!e.warned && warnDays > 0 && now >= e.expiresAt - warnDays * 86_400_000L) {
                    long hoursLeft = Math.max(1, (e.expiresAt - now) / 3_600_000L);
                    String left = hoursLeft >= 48 ? (hoursLeft / 24) + " days" : hoursLeft + " hours";
                    notify.send(e.ownerId, "project will be deleted in " + left,
                        "Your project \"" + e.name + "\" will be permanently deleted in about " + left + ", "
                        + "when its " + retentionDays + "-day storage period ends.\n\n"
                        + "Download the results you want to keep (Export on the project card) before then. "
                        + "After deletion the data cannot be recovered.\n");
                    e.warned = true;
                    write(e);
                }
            } catch (IOException ex) {
                System.err.printf("[Retention] %s: %s%n", e.projectId, ex.getMessage());
            }
        }
        return deleted;
    }

    public interface Notifier { void send(String ownerId, String subjectSuffix, String body); }

    /** Deletes every project of a removed account (no emails). */
    public void deleteAllOf(String userId) {
        for (Entry e : ownedBy(userId)) {
            try { delete(e.projectId); } catch (IOException ex) { System.err.println("[Registry] " + ex.getMessage()); }
        }
    }

    // ── Persistence ─────────────────────────────────────────────────────

    private void write(Entry e) throws IOException {
        Properties p = new Properties();
        p.setProperty("project", e.projectId);
        p.setProperty("owner", e.ownerId);
        p.setProperty("name", e.name == null ? "" : e.name);
        p.setProperty("created", String.valueOf(e.createdAt));
        p.setProperty("expires", String.valueOf(e.expiresAt));
        p.setProperty("warned", String.valueOf(e.warned));
        File tmp = new File(registryDir, e.projectId + ".properties.tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) { p.store(w, null); }
        FileSafety.restrictToOwner(tmp);
        Files.move(tmp.toPath(), new File(registryDir, e.projectId + ".properties").toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Entry read(File f) {
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) { p.load(r); }
        catch (IOException ex) { return null; }
        Entry e = new Entry();
        e.projectId = p.getProperty("project");
        e.ownerId = p.getProperty("owner");
        if (e.projectId == null || e.ownerId == null || !FileSafety.validProjectId(e.projectId)) return null;
        e.name = p.getProperty("name", "");
        e.createdAt = Long.parseLong(p.getProperty("created", "0"));
        e.expiresAt = Long.parseLong(p.getProperty("expires", "0"));
        e.warned = Boolean.parseBoolean(p.getProperty("warned", "false"));
        return e;
    }
}
