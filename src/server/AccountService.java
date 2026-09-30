import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Accounts, emailed one-time codes, and login sessions.
 *
 * Accounts persist as one properties file each under <dataDir>/accounts (atomic replace on write).
 * Codes and sessions live only in memory, stored as keyed digests: a restart signs everyone out
 * and voids pending codes, which is the safe failure direction.
 *
 * Anti-enumeration: register / resend / forgot-password answer identically whether or not the
 * email has an account; the email itself tells the owner what happened.
 */
public class AccountService {

    public enum Purpose { VERIFY_EMAIL, RESET_PASSWORD }

    public static class Account {
        public String id, email, passwordHash;
        public boolean verified;
        public long createdAt, lastLoginAt, lockedUntil;
        public int failedLogins;
    }

    public static class Session {
        public final String userId;
        public final long createdAt;
        public volatile long lastSeenAt;
        public final String csrfToken;
        Session(String userId, long now, String csrf) { this.userId = userId; this.createdAt = now; this.lastSeenAt = now; this.csrfToken = csrf; }
    }

    static class PendingCode {
        String digest; long expiresAt; int attempts;
    }

    /** Result of an operation that can fail in a user-visible way. */
    public static class Outcome {
        public final boolean ok; public final String message; public final String sessionToken;
        Outcome(boolean ok, String message, String token) { this.ok = ok; this.message = message; this.sessionToken = token; }
        static Outcome ok(String msg) { return new Outcome(true, msg, null); }
        static Outcome fail(String msg) { return new Outcome(false, msg, null); }
    }

    public static final int CODE_DIGITS = 6;
    public static final long CODE_TTL_MS = 15 * 60_000L;
    public static final int CODE_MAX_ATTEMPTS = 5;
    public static final long RESEND_MIN_INTERVAL_MS = 60_000L;
    public static final int MAX_FAILED_LOGINS = 5;
    public static final long LOCKOUT_MS = 15 * 60_000L;
    public static final long SESSION_IDLE_MS = 2 * 3600_000L;
    public static final long SESSION_ABSOLUTE_MS = 24 * 3600_000L;
    public static final int PASSWORD_MIN = 10, PASSWORD_MAX = 128;

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,190}\\.[A-Za-z]{2,24}$");

    private final File accountsDir;
    private final byte[] pepper;
    private final Mailer mailer;
    private final Clock clock;
    private final String productName;

    private final Map<String, Account> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByEmail = new ConcurrentHashMap<>();
    private final Map<String, PendingCode> codes = new ConcurrentHashMap<>();      // purpose|userId -> code
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();          // purpose|email -> time
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();       // digest(token) -> session

    /** Called after an account is removed so its projects can be deleted too. */
    public interface AccountRemovedListener { void removed(Account a); }
    private volatile AccountRemovedListener removedListener = a -> {};

    public AccountService(File dataDir, byte[] pepper, Mailer mailer, Clock clock, String productName) throws IOException {
        this.accountsDir = new File(dataDir, "accounts");
        this.pepper = pepper.clone();
        this.mailer = mailer;
        this.clock = clock;
        this.productName = productName;
        Files.createDirectories(accountsDir.toPath());
        File[] files = accountsDir.listFiles((d, n) -> n.endsWith(".properties"));
        if (files != null) for (File f : files) {
            Account a = read(f);
            if (a != null) { byId.put(a.id, a); idByEmail.put(a.email, a.id); }
        }
    }

    public void setAccountRemovedListener(AccountRemovedListener l) { removedListener = l; }

    // ── Validation ─────────────────────────────────────────────────────

    public static String normalizeEmail(String email) {
        if (email == null) return null;
        String e = email.trim().toLowerCase(Locale.ROOT);
        return EMAIL.matcher(e).matches() && e.length() <= 254 ? e : null;
    }

    /** Null when acceptable, otherwise the reason. */
    public static String passwordProblem(String password, String email) {
        if (password == null || password.length() < PASSWORD_MIN) return "Password must be at least " + PASSWORD_MIN + " characters.";
        if (password.length() > PASSWORD_MAX) return "Password must be at most " + PASSWORD_MAX + " characters.";
        if (password.chars().distinct().count() < 5) return "Password is too repetitive.";
        String local = email == null ? "" : email.split("@")[0];
        if (local.length() >= 4 && password.toLowerCase(Locale.ROOT).contains(local)) return "Password must not contain your email name.";
        String lower = password.toLowerCase(Locale.ROOT);
        for (String weak : new String[]{"password", "123456", "qwerty", "letmein", "lynxgwas", "iloveyou", "welcome"})
            if (lower.contains(weak)) return "Password is too easy to guess.";
        return null;
    }

    // ── Registration and email verification ────────────────────────────

    public synchronized Outcome register(String rawEmail, String password) {
        String email = normalizeEmail(rawEmail);
        if (email == null) return Outcome.fail("Enter a valid email address.");
        String problem = passwordProblem(password, email);
        if (problem != null) return Outcome.fail(problem);
        String generic = "If this email can be used, a verification code has been sent to it.";

        String existingId = idByEmail.get(email);
        if (existingId != null) {
            Account existing = byId.get(existingId);
            if (existing.verified) {
                // Tell the real owner instead of revealing to the requester that the account exists
                if (throttleOk("exists|" + email))
                    mailer.send(email, productName + ": sign-up attempt",
                        "Someone tried to create a " + productName + " account with this email address, which already has an account.\n\n"
                        + "If this was you, sign in instead, or use \"Forgot password\" on the sign-in page.\n"
                        + "If it wasn't you, you can ignore this message; your account is unchanged.\n");
                return Outcome.ok(generic);
            }
            // Unverified: let them restart sign-up with a new password
            existing.passwordHash = Crypto.hashPassword(password.toCharArray());
            write(existing);
            sendCode(existing, Purpose.VERIFY_EMAIL);
            return Outcome.ok(generic);
        }

        Account a = new Account();
        do { a.id = Crypto.randomId(16); } while (byId.containsKey(a.id));
        a.email = email;
        a.passwordHash = Crypto.hashPassword(password.toCharArray());
        a.createdAt = clock.millis();
        byId.put(a.id, a);
        idByEmail.put(email, a.id);
        write(a);
        sendCode(a, Purpose.VERIFY_EMAIL);
        return Outcome.ok(generic);
    }

    public synchronized Outcome resendCode(String rawEmail, Purpose purpose) {
        String email = normalizeEmail(rawEmail);
        String generic = "If this email has a pending request, a new code has been sent.";
        if (email == null) return Outcome.ok(generic);
        Account a = byId.get(idByEmail.getOrDefault(email, ""));
        if (a == null) return Outcome.ok(generic);
        if (purpose == Purpose.VERIFY_EMAIL && a.verified) return Outcome.ok(generic);
        sendCode(a, purpose);
        return Outcome.ok(generic);
    }

    /** Checks the emailed code; on success marks the email verified and signs the user in. */
    public synchronized Outcome verifyEmail(String rawEmail, String code) {
        Account a = accountFor(rawEmail);
        if (a == null || a.verified || !checkCode(a, Purpose.VERIFY_EMAIL, code))
            return Outcome.fail("The code is wrong or has expired. Request a new code if needed.");
        a.verified = true;
        a.lastLoginAt = clock.millis();
        write(a);
        return new Outcome(true, "Email verified.", newSession(a));
    }

    // ── Login, logout, password reset ──────────────────────────────────

    public synchronized Outcome login(String rawEmail, String password) {
        String fail = "Email or password is incorrect.";
        Account a = accountFor(rawEmail);
        long now = clock.millis();
        if (a == null) {
            Crypto.verifyPassword(password == null ? new char[0] : password.toCharArray(), DUMMY_HASH); // equalise timing
            return Outcome.fail(fail);
        }
        if (a.lockedUntil > now)
            return Outcome.fail("Too many failed attempts. Try again in " + ((a.lockedUntil - now) / 60_000 + 1) + " minutes, or reset your password.");
        if (password == null || !Crypto.verifyPassword(password.toCharArray(), a.passwordHash)) {
            a.failedLogins++;
            if (a.failedLogins >= MAX_FAILED_LOGINS) { a.lockedUntil = now + LOCKOUT_MS; a.failedLogins = 0; }
            write(a);
            return Outcome.fail(fail);
        }
        if (!a.verified) return Outcome.fail("Please verify your email first. Use \"Resend code\" if you need a new one.");
        a.failedLogins = 0;
        a.lastLoginAt = now;
        if (Crypto.needsRehash(a.passwordHash)) a.passwordHash = Crypto.hashPassword(password.toCharArray());
        write(a);
        return new Outcome(true, "Signed in.", newSession(a));
    }

    public synchronized Outcome requestPasswordReset(String rawEmail) {
        String generic = "If this email has an account, a reset code has been sent to it.";
        Account a = accountFor(rawEmail);
        if (a != null) sendCode(a, Purpose.RESET_PASSWORD);
        return Outcome.ok(generic);
    }

    public synchronized Outcome resetPassword(String rawEmail, String code, String newPassword) {
        Account a = accountFor(rawEmail);
        String problem = passwordProblem(newPassword, a == null ? null : a.email);
        if (problem != null) return Outcome.fail(problem);
        if (a == null || !checkCode(a, Purpose.RESET_PASSWORD, code))
            return Outcome.fail("The code is wrong or has expired. Request a new code if needed.");
        a.passwordHash = Crypto.hashPassword(newPassword.toCharArray());
        a.verified = true;                 // they proved control of the mailbox
        a.failedLogins = 0; a.lockedUntil = 0;
        write(a);
        revokeSessionsOf(a.id);            // a reset signs out every other device
        mailer.send(a.email, productName + ": your password was changed",
            "The password for your " + productName + " account was just changed.\n"
            + "If you did not do this, reset it again immediately and contact the site administrator.\n");
        return new Outcome(true, "Password changed.", newSession(a));
    }

    public void logout(String token) {
        if (token != null) sessions.remove(Crypto.keyedDigest(pepper, token));
    }

    /** Signed-in session for a cookie token, or null. Enforces idle and absolute expiry. */
    public Session session(String token) {
        if (token == null || token.isEmpty() || token.length() > 100) return null;
        String key = Crypto.keyedDigest(pepper, token);
        Session s = sessions.get(key);
        if (s == null) return null;
        long now = clock.millis();
        if (now - s.lastSeenAt > SESSION_IDLE_MS || now - s.createdAt > SESSION_ABSOLUTE_MS || !byId.containsKey(s.userId)) {
            sessions.remove(key);
            return null;
        }
        s.lastSeenAt = now;
        return s;
    }

    public Account account(String userId) { return userId == null ? null : byId.get(userId); }

    /** Deletes the account (and via the listener its projects), then signs it out everywhere. */
    public synchronized void deleteAccount(String userId) {
        Account a = byId.remove(userId);
        if (a == null) return;
        idByEmail.remove(a.email);
        revokeSessionsOf(userId);
        new File(accountsDir, userId + ".properties").delete();
        removedListener.removed(a);
    }

    /** Removes accounts that never verified their email within {@code maxAgeMs}. Returns how many. */
    public synchronized int purgeUnverified(long maxAgeMs) {
        long now = clock.millis();
        List<String> stale = new ArrayList<>();
        for (Account a : byId.values()) if (!a.verified && now - a.createdAt > maxAgeMs) stale.add(a.id);
        for (String id : stale) deleteAccount(id);
        sessions.values().removeIf(s -> now - s.lastSeenAt > SESSION_IDLE_MS || now - s.createdAt > SESSION_ABSOLUTE_MS);
        codes.values().removeIf(c -> c.expiresAt < now);
        return stale.size();
    }

    public int accountCount() { return byId.size(); }

    // ── Internals ──────────────────────────────────────────────────────

    private static final String DUMMY_HASH = Crypto.hashPassword("not-a-real-password".toCharArray());

    private Account accountFor(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        return email == null ? null : byId.get(idByEmail.getOrDefault(email, ""));
    }

    private boolean throttleOk(String key) {
        long now = clock.millis();
        Long prev = lastSent.get(key);
        if (prev != null && now - prev < RESEND_MIN_INTERVAL_MS) return false;
        lastSent.put(key, now);
        return true;
    }

    private void sendCode(Account a, Purpose purpose) {
        if (!throttleOk(purpose + "|" + a.email)) return;
        String code = Crypto.randomDigits(CODE_DIGITS);
        PendingCode pc = new PendingCode();
        pc.digest = Crypto.keyedDigest(pepper, purpose + "|" + a.id + "|" + code);
        pc.expiresAt = clock.millis() + CODE_TTL_MS;
        codes.put(purpose + "|" + a.id, pc);
        String what = purpose == Purpose.VERIFY_EMAIL ? "verify your email address" : "reset your password";
        mailer.send(a.email, productName + " code: " + code,
            "Your " + productName + " code to " + what + " is:\n\n    " + code + "\n\n"
            + "It expires in " + (CODE_TTL_MS / 60_000) + " minutes. Never share this code with anyone.\n"
            + "If you did not request it, you can ignore this email.\n");
    }

    private boolean checkCode(Account a, Purpose purpose, String code) {
        String key = purpose + "|" + a.id;
        PendingCode pc = codes.get(key);
        if (pc == null || clock.millis() > pc.expiresAt) { codes.remove(key); return false; }
        if (code == null || !code.trim().matches("\\d{" + CODE_DIGITS + "}")) { burn(key, pc); return false; }
        String digest = Crypto.keyedDigest(pepper, purpose + "|" + a.id + "|" + code.trim());
        if (!Crypto.constantTimeEquals(digest, pc.digest)) { burn(key, pc); return false; }
        codes.remove(key);   // single use
        return true;
    }

    private void burn(String key, PendingCode pc) {
        if (++pc.attempts >= CODE_MAX_ATTEMPTS) codes.remove(key);
    }

    private String newSession(Account a) {
        String token = Crypto.randomToken(32);
        sessions.put(Crypto.keyedDigest(pepper, token), new Session(a.id, clock.millis(), Crypto.randomToken(24)));
        return token;
    }

    private void revokeSessionsOf(String userId) {
        sessions.values().removeIf(s -> s.userId.equals(userId));
    }

    private void write(Account a) {
        Properties p = new Properties();
        p.setProperty("id", a.id);
        p.setProperty("email", a.email);
        p.setProperty("password", a.passwordHash);
        p.setProperty("verified", String.valueOf(a.verified));
        p.setProperty("created", String.valueOf(a.createdAt));
        p.setProperty("last_login", String.valueOf(a.lastLoginAt));
        p.setProperty("failed_logins", String.valueOf(a.failedLogins));
        p.setProperty("locked_until", String.valueOf(a.lockedUntil));
        try {
            File tmp = new File(accountsDir, a.id + ".properties.tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) { p.store(w, null); }
            FileSafety.restrictToOwner(tmp);
            Files.move(tmp.toPath(), new File(accountsDir, a.id + ".properties").toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save account", e);
        }
    }

    private static Account read(File f) {
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) { p.load(r); }
        catch (IOException e) { System.err.println("[Accounts] unreadable " + f.getName()); return null; }
        Account a = new Account();
        a.id = p.getProperty("id");
        a.email = p.getProperty("email");
        a.passwordHash = p.getProperty("password");
        if (a.id == null || a.email == null || a.passwordHash == null) return null;
        a.verified = Boolean.parseBoolean(p.getProperty("verified", "false"));
        a.createdAt = Long.parseLong(p.getProperty("created", "0"));
        a.lastLoginAt = Long.parseLong(p.getProperty("last_login", "0"));
        a.failedLogins = Integer.parseInt(p.getProperty("failed_logins", "0"));
        a.lockedUntil = Long.parseLong(p.getProperty("locked_until", "0"));
        return a;
    }
}
