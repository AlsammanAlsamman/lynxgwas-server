import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Token-bucket limits per key (client IP or network, workspace id). Old buckets are pruned lazily. */
public class RequestLimiter {
    private static class Bucket { double tokens; long updated; }

    private final int capacity;
    private final double refillPerMs;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Allows bursts of {@code capacity}, refilling {@code perMinute} per minute. */
    public RequestLimiter(int capacity, double perMinute, Clock clock) {
        this.capacity = capacity;
        this.refillPerMs = perMinute / 60_000.0;
        this.clock = clock;
    }

    /** True when {@code key} could make a request now, without using up a token. */
    public boolean hasCapacity(String key) {
        Bucket b = buckets.get(key);
        if (b == null) return true;
        synchronized (b) {
            return Math.min(capacity, b.tokens + (clock.millis() - b.updated) * refillPerMs) >= 1;
        }
    }

    public boolean allow(String key) {
        long now = clock.millis();
        if (buckets.size() > 50_000) buckets.entrySet().removeIf(e -> now - e.getValue().updated > 3_600_000L);
        Bucket b = buckets.computeIfAbsent(key, k -> { Bucket n = new Bucket(); n.tokens = capacity; n.updated = now; return n; });
        synchronized (b) {
            b.tokens = Math.min(capacity, b.tokens + (now - b.updated) * refillPerMs);
            b.updated = now;
            if (b.tokens < 1) return false;
            b.tokens -= 1;
            return true;
        }
    }
}
