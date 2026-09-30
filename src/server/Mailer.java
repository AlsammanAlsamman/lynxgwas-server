import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Sends account emails (verification codes, retention warnings, deletion notices).
 *
 * Sending is queued on a background thread, so a request takes the same time whether or not an
 * email was actually sent (no timing signal about which addresses have accounts).
 *
 * SMTP mode needs TLS (STARTTLS on 587 or implicit SSL on 465); plain SMTP is refused and the
 * server certificate is verified. Development mode (no smtp.host) writes .eml files into
 * <dataDir>/outbox instead, so the whole flow can be tested without a mail account.
 */
public abstract class Mailer {

    private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mailer"); t.setDaemon(true); return t;
    });

    /** Queue an email. Never throws; failures are logged without the message body. */
    public void send(String to, String subject, String body) {
        queue.submit(() -> {
            try { deliver(to, subject, body); }
            catch (Exception e) { System.err.printf("[Mailer] delivery to %s failed: %s%n", mask(to), e.getMessage()); }
        });
    }

    /** Wait for queued mail (tests and shutdown). */
    public void flush() {
        try { queue.submit(() -> {}).get(30, TimeUnit.SECONDS); } catch (Exception ignored) {}
    }

    protected abstract void deliver(String to, String subject, String body) throws Exception;

    static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }

    public static Mailer create(ServerConfig cfg) {
        if (cfg.devMail()) {
            File outbox = new File(cfg.dataDir(), "outbox");
            System.out.println("[Mailer] DEVELOPMENT MODE: no smtp.host set, emails are written to " + outbox.getAbsolutePath());
            return new OutboxMailer(outbox);
        }
        return new SmtpMailer(cfg);
    }

    /** Development: one .eml file per message. */
    public static class OutboxMailer extends Mailer {
        private final File dir;
        public OutboxMailer(File dir) { this.dir = dir; dir.mkdirs(); }

        @Override protected void deliver(String to, String subject, String body) throws IOException {
            String name = System.currentTimeMillis() + "-" + Crypto.randomId(6) + ".eml";
            String eml = "To: " + to + "\nSubject: " + subject + "\n\n" + body;
            Files.write(new File(dir, name).toPath(), eml.getBytes(StandardCharsets.UTF_8));
        }

        /** Newest message sent to {@code to}, or null (tests). */
        public String latestTo(String to) throws IOException {
            File[] files = dir.listFiles((d, n) -> n.endsWith(".eml"));
            if (files == null) return null;
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (int i = files.length - 1; i >= 0; i--) {
                String s = new String(Files.readAllBytes(files[i].toPath()), StandardCharsets.UTF_8);
                if (s.startsWith("To: " + to + "\n")) return s;
            }
            return null;
        }
    }

    /** Production: Jakarta Mail over TLS. */
    public static class SmtpMailer extends Mailer {
        private final jakarta.mail.Session session;
        private final String from;

        SmtpMailer(ServerConfig cfg) {
            Properties p = new Properties();
            p.put("mail.smtp.host", cfg.smtpHost);
            p.put("mail.smtp.port", String.valueOf(cfg.smtpPort));
            p.put("mail.smtp.auth", String.valueOf(!cfg.smtpUser.isEmpty()));
            p.put("mail.smtp.connectiontimeout", "15000");
            p.put("mail.smtp.timeout", "20000");
            p.put("mail.smtp.writetimeout", "20000");
            p.put("mail.smtp.ssl.checkserveridentity", "true");
            p.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
            if (cfg.smtpSecurity.equals("ssl")) {
                p.put("mail.smtp.ssl.enable", "true");
            } else {
                p.put("mail.smtp.starttls.enable", "true");
                p.put("mail.smtp.starttls.required", "true");
            }
            String user = cfg.smtpUser, pass = cfg.secretFromEnv(cfg.smtpPasswordEnv);
            if (!user.isEmpty() && pass.isEmpty())
                System.err.println("[Mailer] WARNING: smtp.user is set but environment variable " + cfg.smtpPasswordEnv + " is empty");
            session = jakarta.mail.Session.getInstance(p, user.isEmpty() ? null : new jakarta.mail.Authenticator() {
                @Override protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                    return new jakarta.mail.PasswordAuthentication(user, pass);
                }
            });
            from = cfg.mailFrom;
        }

        @Override protected void deliver(String to, String subject, String body) throws Exception {
            jakarta.mail.internet.MimeMessage m = new jakarta.mail.internet.MimeMessage(session);
            m.setFrom(new jakarta.mail.internet.InternetAddress(from, true));
            m.setRecipients(jakarta.mail.Message.RecipientType.TO, jakarta.mail.internet.InternetAddress.parse(to, true));
            m.setSubject(subject, "UTF-8");
            m.setText(body, "UTF-8");
            m.setSentDate(new Date());
            jakarta.mail.Transport.send(m);
        }
    }
}
