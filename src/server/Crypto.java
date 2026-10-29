import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Random tokens, keyed digests and constant-time comparison. JDK only, no third-party crypto.
 *
 * Workspace tokens are stored only as HMAC-SHA256 digests keyed with a server pepper, so a leaked
 * data folder does not reveal tokens anyone could use.
 */
public final class Crypto {
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private Crypto() {}

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    /** URL-safe random token with {@code bytes} bytes of entropy. */
    public static String randomToken(int bytes) { return B64.encodeToString(randomBytes(bytes)); }

    /** Random lowercase base32-ish id (no ambiguous characters) for users and projects. */
    public static String randomId(int length) {
        final String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(alphabet.charAt(RNG.nextInt(alphabet.length())));
        return sb.toString();
    }

    /** HMAC-SHA256(pepper, value), base64url. Used to store tokens without keeping them readable. */
    public static String keyedDigest(byte[] pepper, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
