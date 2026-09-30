import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caps heavy background work (processing, analyses, locus identification, cross-dataset runs) per
 * user and in total, so one account can't monopolise CPU, memory or PLINK slots.
 */
public class JobLimiter {
    private final int perUser, total;
    private final AtomicInteger running = new AtomicInteger();
    private final Map<String, AtomicInteger> byUser = new ConcurrentHashMap<>();

    public JobLimiter(int perUser, int total) { this.perUser = perUser; this.total = total; }

    /** Null when the job may start (the caller must then run it via {@link #start}); otherwise the reason. */
    public synchronized String refuse(String userId) {
        if (running.get() >= total) return "The server is busy with other analyses. Try again in a few minutes.";
        AtomicInteger mine = byUser.get(userId);
        if (mine != null && mine.get() >= perUser)
            return "You already have " + perUser + " analysis running. Wait for it to finish before starting another.";
        return null;
    }

    /**
     * Starts {@code work} on a background thread counted against {@code userId}, or returns the
     * refusal reason without starting anything.
     */
    public synchronized String start(String userId, String threadName, Runnable work) {
        String why = refuse(userId);
        if (why != null) return why;
        running.incrementAndGet();
        AtomicInteger mine = byUser.computeIfAbsent(userId, k -> new AtomicInteger());
        mine.incrementAndGet();
        Thread t = new Thread(() -> {
            try { work.run(); }
            finally {
                synchronized (JobLimiter.this) {   // same lock as start(), so a counter is never removed while reused
                    running.decrementAndGet();
                    if (mine.decrementAndGet() <= 0) byUser.remove(userId, mine);
                }
            }
        }, threadName);
        t.setDaemon(true);
        t.start();
        return null;
    }

    public int running() { return running.get(); }
}
