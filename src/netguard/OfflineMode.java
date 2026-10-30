package netguard;

import java.io.IOException;
import java.net.URI;

/**
 * Strict offline mode: no request leaves the computer.
 *
 * <p>On when the app is started with {@code --offline}, with {@code -Dlynxgwas.offline=true}, or with the
 * environment variable {@code LYNXGWAS_OFFLINE=1}. Every place that opens a network connection calls
 * {@link #check(String)} first; in offline mode it refuses any host that is not this computer (localhost,
 * 127.0.0.0/8, ::1), so a local AI model still works and nothing else is contacted: no rsID, GWAS Catalog
 * or Open Targets lookups, no GENCODE or example-data download and no cloud AI model. Features that need the
 * network report that offline mode is on instead of failing silently.
 */
public final class OfflineMode {
    private static volatile Boolean forced = null;

    private OfflineMode() {}

    /** Turns offline mode on (from the {@code --offline} command-line flag). */
    public static void enable() { forced = Boolean.TRUE; }

    public static boolean on() {
        if (forced != null) return forced;
        String p = System.getProperty("lynxgwas.offline");
        if (p != null) return Boolean.parseBoolean(p.trim());
        String e = System.getenv("LYNXGWAS_OFFLINE");
        return e != null && (e.trim().equals("1") || e.trim().equalsIgnoreCase("true"));
    }

    /** Throws when offline mode is on and {@code url} points to another computer. */
    public static void check(String url) throws IOException {
        if (!on()) return;
        String host;
        try { host = URI.create(url).getHost(); } catch (IllegalArgumentException e) { host = null; }
        if (!isLocal(host))
            throw new IOException("Offline mode is on: LYNXgwas does not contact " + (host == null ? url : host)
                + ". Start it without --offline to use online lookups.");
    }

    public static boolean isLocal(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        return h.equals("localhost") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1") || h.startsWith("127.");
    }

    /** For tests: clear a forced setting. */
    public static void reset() { forced = null; }
}
