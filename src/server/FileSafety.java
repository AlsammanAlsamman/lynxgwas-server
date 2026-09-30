import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.regex.Pattern;

/** Path and file helpers that keep user-controlled names inside the folders they belong to. */
public final class FileSafety {
    private FileSafety() {}

    /** Project ids: letters, digits, dot, dash, underscore; no leading dot; at most 80 chars. */
    private static final Pattern PROJECT_ID = Pattern.compile("^[A-Za-z0-9_-][A-Za-z0-9._-]{0,79}$");

    public static boolean validProjectId(String id) {
        return id != null && PROJECT_ID.matcher(id).matches() && !id.contains("..");
    }

    /**
     * Resolve {@code child} inside {@code base}, refusing anything that escapes it (.., absolute
     * paths, drive letters, symlink tricks via real-path check when the target exists).
     */
    public static File within(File base, String child) throws IOException {
        if (child == null || child.isEmpty() || child.indexOf('\0') >= 0) throw new IOException("bad path");
        Path root = base.getCanonicalFile().toPath();
        Path p = root.resolve(child).normalize();
        if (!p.startsWith(root) || p.equals(root)) throw new IOException("path escapes its folder");
        File f = p.toFile();
        if (f.exists() && !f.getCanonicalFile().toPath().startsWith(root)) throw new IOException("path escapes its folder");
        return f;
    }

    /** Owner-only permissions where the filesystem supports POSIX modes (no-op on Windows). */
    public static void restrictToOwner(File f) {
        try {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
                Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString(f.isDirectory() ? "rwx------" : "rw-------"));
        } catch (IOException ignored) {}
    }

    /** Recursive delete that never follows symlinks out of the tree. */
    public static void deleteTree(File root) throws IOException {
        Path start = root.toPath();
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || attrs.isOther()) { Files.delete(dir); return FileVisitResult.SKIP_SUBTREE; }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Total bytes under a folder (symlinks not followed). */
    public static long sizeOf(File root) {
        final long[] total = {0};
        try {
            Files.walkFileTree(root.toPath(), new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size(); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException exc) { return FileVisitResult.CONTINUE; }
            });
        } catch (IOException ignored) {}
        return total[0];
    }
}
