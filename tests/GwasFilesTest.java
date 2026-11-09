import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import gwasio.GwasFiles;

/**
 * GwasFiles.open reads every accepted form of a summary-statistics file as the same tab-separated lines:
 * plain TSV, gzipped, zipped (also with a gzipped table inside, a folder and macOS metadata), CSV with
 * CRLF and a BOM, semicolon- and space-separated.
 */
public class GwasFilesTest {
    static int failures = 0;
    static final String EXPECT = "CHR\tBP\tP\n1\t100\t0.5\n2\t200\t1e-9\n";

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("gwasfiles");
        try {
            check("plain TSV", read(write(dir, "a.tsv", EXPECT.getBytes())));
            check("gzipped TSV", read(write(dir, "b.tsv.gz", gzip(EXPECT.getBytes()))));
            check("CSV with CRLF and BOM", read(write(dir, "c.csv", ("﻿CHR,BP,P\r\n1,100,0.5\r\n2,200,1e-9\r\n").getBytes("UTF-8"))));
            check("semicolon-separated", read(write(dir, "d.txt", "CHR;BP;P\n1;100;0.5\n2;200;1e-9\n".getBytes())));
            check("space-separated with runs of spaces", read(write(dir, "e.txt", "CHR  BP P\n 1 100  0.5\n2 200 1e-9\n".getBytes())));
            check("zip with folder and macOS metadata", read(write(dir, "f.zip", zip(new String[]{"__MACOSX/._g.csv", "g/", "g/g.csv"},
                new byte[][]{{0, 1}, null, "CHR,BP,P\n1,100,0.5\n2,200,1e-9\n".getBytes()}))));
            check("gzipped table inside a zip", read(write(dir, "h.zip", zip(new String[]{"g.tsv.gz"}, new byte[][]{gzip(EXPECT.getBytes())}))));
            boolean refused;
            try { read(write(dir, "i.zip", zip(new String[]{"empty/"}, new byte[][]{null}))); refused = false; }
            catch (IOException e) { refused = true; }
            report("zip without a data file is refused", refused);
        } finally {
            try (java.util.stream.Stream<Path> w = Files.walk(dir)) {
                w.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
        if (failures == 0) System.out.println("PASS: all GwasFiles tests passed");
        else { System.out.println("FAIL: " + failures + " test(s) failed"); System.exit(1); }
    }

    static Path write(Path dir, String name, byte[] b) throws IOException { return Files.write(dir.resolve(name), b); }

    static String read(Path p) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = GwasFiles.open(p.toFile())) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    static byte[] gzip(byte[] b) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(o)) { g.write(b); }
        return o.toByteArray();
    }

    static byte[] zip(String[] names, byte[][] data) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(o)) {
            for (int i = 0; i < names.length; i++) {
                z.putNextEntry(new ZipEntry(names[i]));
                if (data[i] != null) z.write(data[i]);
                z.closeEntry();
            }
        }
        return o.toByteArray();
    }

    static void check(String name, String got) { report(name, EXPECT.equals(got)); }

    static void report(String name, boolean ok) {
        System.out.println((ok ? "PASS: " : "FAIL: ") + name);
        if (!ok) failures++;
    }
}
