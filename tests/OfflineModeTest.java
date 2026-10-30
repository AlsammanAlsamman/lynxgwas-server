import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Tests for strict offline mode.
 *   1. Off by default; on with the flag, the system property or the environment variable.
 *   2. When on, any other computer is refused and this computer (localhost, 127.x, ::1) is allowed, so a local
 *      AI model keeps working.
 *   3. Source guard: every source file that opens a network connection (openConnection, HttpRequest.newBuilder)
 *      calls OfflineMode.check, so a new outbound call cannot be added without it.
 */
public class OfflineModeTest {
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        netguard.OfflineMode.reset();
        System.clearProperty("lynxgwas.offline");
        boolean envOn = "1".equals(System.getenv("LYNXGWAS_OFFLINE"));
        check("off by default (unless LYNXGWAS_OFFLINE=1)", netguard.OfflineMode.on() == envOn);
        check("off: external URL allowed", allowed("https://www.ebi.ac.uk/gwas/rest/api") || envOn);

        System.setProperty("lynxgwas.offline", "true");
        check("on with -Dlynxgwas.offline=true", netguard.OfflineMode.on());
        for (String u : new String[]{"https://www.ebi.ac.uk/gwas/rest/api/v2/studies", "https://api.platform.opentargets.org/api/v4/graphql",
                "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esummary.fcgi", "https://zenodo.org/records/1", "https://api.openai.com/v1",
                "http://192.168.1.20:11434"})
            check("on: refuses " + u, !allowed(u));
        for (String u : new String[]{"http://localhost:11434", "http://127.0.0.1:1234/v1", "http://[::1]:8765/api"})
            check("on: allows " + u, allowed(u));
        System.clearProperty("lynxgwas.offline");
        netguard.OfflineMode.enable();
        check("on with --offline", netguard.OfflineMode.on());
        netguard.OfflineMode.reset();

        Path src = Paths.get(args.length > 0 ? args[0] : "src");
        List<Path> missing;
        try (Stream<Path> s = Files.walk(src)) {
            missing = s.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                String t = read(p);
                boolean net = t.contains("openConnection(") || t.contains("HttpRequest.newBuilder(") || t.contains("openStream(");
                boolean internal = p.getFileName().toString().equals("AgentToolRegistry.java"); // calls this app's own API on localhost
                return net && !internal && !t.contains("OfflineMode.check(");
            }).collect(Collectors.toList());
        }
        check("every source file with an outbound connection calls OfflineMode.check " + missing, missing.isEmpty());
        System.out.println(failures == 0 ? "PASS: all OfflineMode tests passed" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }

    static boolean allowed(String url) {
        try { netguard.OfflineMode.check(url); return true; } catch (IOException e) { return false; }
    }

    static String read(Path p) {
        try { return new String(Files.readAllBytes(p), "UTF-8"); } catch (IOException e) { return ""; }
    }

    static void check(String name, boolean ok) {
        if (ok) System.out.println("PASS: " + name);
        else { System.out.println("FAIL: " + name); failures++; }
    }
}
