import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password hashing, random tokens and constant-time comparison. JDK only, no third-party crypto.
 *
 * Passwords: PBKDF2-HMAC-SHA256, 600,000 iterations (OWASP 2023 guidance), 16-byte random salt,
 * stored as "pbkdf2-sha256$<iterations>$<salt b64>$<hash b64>" so the cost can be raised later
 * and old hashes still verify (and get rehashed on next login).
 * Tokens and emailed codes are stored only as HMAC-SHA256 digests keyed with a server pepper,
 * so a leaked data folder does not reveal usable sessions or codes.
 */
public final class Crypto {
    public static final int PBKDF2_ITERATIONS = 600_000;
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private Crypto() {}

    public static String hashPassword(char[] password) {
        byte[] salt = randomBytes(16);
        byte[] hash = pbkdf2(password, salt, PBKDF2_ITERATIONS);
        return "pbkdf2-sha256$" + PBKDF2_ITERATIONS + "$" + B64.encodeToString(salt) + "$" + B64.encodeToString(hash);
    }

    public static boolean verifyPassword(char[] password, String stored) {
        if (stored == null) return false;
        String[] p = stored.split("\\$");
        if (p.length != 4 || !p[0].equals("pbkdf2-sha256")) return false;
        try {
            int iters = Integer.parseInt(p[1]);
            byte[] salt = B64D.decode(p[2]);
            byte[] expected = B64D.decode(p[3]);
            return MessageDigest.isEqual(pbkdf2(password, salt, iters), expected);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** True when a stored hash was made with weaker settings than today's and should be redone. */
    public static boolean needsRehash(String stored) {
        String[] p = stored == null ? new String[0] : stored.split("\\$");
        try { return p.length != 4 || Integer.parseInt(p[1]) < PBKDF2_ITERATIONS; }
        catch (NumberFormatException e) { return true; }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    /** URL-safe random token with {@code bytes} bytes of entropy. */
    public static String randomToken(int bytes) { return B64.encodeToString(randomBytes(bytes)); }

    /** Uniformly random numeric code of {@code digits} digits (leading zeros kept). */
    public static String randomDigits(int digits) {
        StringBuilder sb = new StringBuilder(digits);
        for (int i = 0; i < digits; i++) sb.append((char) ('0' + RNG.nextInt(10)));
        return sb.toString();
    }

    /** Random lowercase base32-ish id (no ambiguous characters) for users and projects. */
    public static String randomId(int length) {
        final String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(alphabet.charAt(RNG.nextInt(alphabet.length())));
        return sb.toString();
    }

    /** HMAC-SHA256(pepper, value), base64url. Used to store tokens/codes without keeping them readable. */
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
