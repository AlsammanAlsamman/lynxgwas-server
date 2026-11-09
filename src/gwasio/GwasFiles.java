package gwasio;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Opens a GWAS summary-statistics file the way every reader of the raw file expects it: plain
 * tab-separated UTF-8 lines. A gzipped file, or a .zip holding the table (itself possibly gzipped), is
 * unpacked on the fly, and a comma-, semicolon- or space-separated file is turned into tabs line by
 * line (the separator is taken from the header, as the server's upload does). CRs and a leading BOM are
 * dropped. Nothing is written to disk.
 */
public final class GwasFiles {
    private GwasFiles() {}

    public static BufferedReader open(String path) throws IOException { return open(new File(path)); }

    public static BufferedReader open(File f) throws IOException {
        InputStream in = unpack(new BufferedInputStream(new FileInputStream(f), 1 << 16));
        return new TabReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /** The stream as plain text: gzip and zip (first data file inside, optionally gzipped) are unpacked. */
    public static InputStream unpack(InputStream in) throws IOException {
        if (!in.markSupported()) in = new BufferedInputStream(in, 1 << 16);
        in.mark(4);
        int b0 = in.read(), b1 = in.read(), b2 = in.read(), b3 = in.read();
        in.reset();
        if (b0 == 0x1f && b1 == 0x8b) return new GZIPInputStream(in, 1 << 16);
        if (b0 == 'P' && b1 == 'K' && b2 == 3 && b3 == 4) {
            ZipInputStream zip = new ZipInputStream(in);
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (e.isDirectory() || name.startsWith("__MACOSX/") || base.startsWith(".") || base.isEmpty()) continue;
                return unpack(zip);
            }
            throw new IOException("the .zip contains no data file");
        }
        return in;
    }

    /** Separator of a header line: tab, comma, semicolon, else runs of whitespace. */
    public static String separatorOf(String header) {
        return header.contains("\t") ? "\t" : header.contains(",") ? "," : header.contains(";") ? ";" : "\\s+";
    }

    /** One line with its fields joined by tabs (unchanged when the file is already tab-separated). */
    public static String toTabs(String line, String sep) {
        if (sep.equals("\t")) return line;
        return String.join("\t", line.trim().split(sep, -1));
    }

    private static final class TabReader extends BufferedReader {
        private String sep;

        TabReader(Reader r) { super(r, 1 << 20); }

        @Override
        public String readLine() throws IOException {
            String line = super.readLine();
            if (line == null) return null;
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (sep == null) {
                if (line.startsWith("﻿")) line = line.substring(1);
                sep = separatorOf(line);
            }
            return toTabs(line, sep);
        }
    }
}
