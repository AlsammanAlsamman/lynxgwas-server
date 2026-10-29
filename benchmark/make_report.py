#!/usr/bin/env python3
"""Builds the LYNXgwas Server load-test report PDF."""
import sys
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (KeepTogether, Paragraph, SimpleDocTemplate, Spacer, Table,
                                TableStyle)

OUT = sys.argv[1]
ACCENT = colors.HexColor("#1f4e5f")
LIGHT = colors.HexColor("#eef3f5")
WARN = colors.HexColor("#fff4e5")
GRID = colors.HexColor("#c9d3d8")

ss = getSampleStyleSheet()
H1 = ParagraphStyle("H1", parent=ss["Title"], fontSize=19, leading=23, textColor=ACCENT, alignment=TA_LEFT, spaceAfter=2)
SUB = ParagraphStyle("SUB", parent=ss["Normal"], fontSize=9.5, textColor=colors.HexColor("#555555"), spaceAfter=10)
H2 = ParagraphStyle("H2", parent=ss["Heading2"], fontSize=13, leading=16, textColor=ACCENT, spaceBefore=10, spaceAfter=5)
BODY = ParagraphStyle("BODY", parent=ss["Normal"], fontSize=9.5, leading=13, spaceAfter=4)
SMALL = ParagraphStyle("SMALL", parent=BODY, fontSize=8, leading=10.5, textColor=colors.HexColor("#555555"))
CELL = ParagraphStyle("CELL", parent=BODY, fontSize=8.5, leading=11, spaceAfter=0)
CELLB = ParagraphStyle("CELLB", parent=CELL, fontName="Helvetica-Bold", textColor=colors.white)
BUL = ParagraphStyle("BUL", parent=BODY, leftIndent=12, bulletIndent=2)


def p(t, s=BODY):
    return Paragraph(t, s)


def table(rows, widths, highlight_rows=()):
    data = [[p(c, CELLB) for c in rows[0]]] + [[p(str(c), CELL) for c in r] for r in rows[1:]]
    t = Table(data, colWidths=[w * mm for w in widths], repeatRows=1)
    st = [("BACKGROUND", (0, 0), (-1, 0), ACCENT),
          ("GRID", (0, 0), (-1, -1), 0.4, GRID),
          ("VALIGN", (0, 0), (-1, -1), "TOP"),
          ("TOPPADDING", (0, 0), (-1, -1), 3), ("BOTTOMPADDING", (0, 0), (-1, -1), 3)]
    for i in range(1, len(data)):
        if i % 2 == 0:
            st.append(("BACKGROUND", (0, i), (-1, i), LIGHT))
    for i in highlight_rows:
        st.append(("BACKGROUND", (0, i), (-1, i), WARN))
    t.setStyle(TableStyle(st))
    return t


def bullets(items):
    return [p(i, BUL) for i in items]


def footer(canvas, doc):
    canvas.saveState()
    canvas.setFont("Helvetica", 7.5)
    canvas.setFillColor(colors.HexColor("#777777"))
    canvas.drawString(18 * mm, 10 * mm, "LYNXgwas Server - load test report - 6 Oct 2026")
    canvas.drawRightString(A4[0] - 18 * mm, 10 * mm, f"Page {doc.page}")
    canvas.restoreState()


story = [
    p("LYNXgwas Server - Load Test Report", H1),
    p("Final results, 6 October 2026. Measured through the real server API (sign-up, upload, "
      "analyses, viewing) while sampling CPU and memory of the server and every program it starts "
      "(PLINK, R, GCTA, MAGMA).", SUB),

    p("1. Summary", H2),
    *bullets([
        "&bull; <b>Viewing results is cheap:</b> 200 people viewing at once got 100% successful answers, "
        "median 5 ms, using under 1 CPU core.",
        "&bull; <b>Analyses are the heavy part, and time grows with the number of loci:</b> the full "
        "pipeline takes 18 min for ADHD (12 loci), about 2 h for schizophrenia (178 loci), and is "
        "projected at about 15 h for height (1,345 loci), for one user.",
        "&bull; <b>Two users at once</b> (ADHD + schizophrenia) finished with no errors but used all 8 cores "
        "of the test machine; SuSiE alone keeps 6-7 cores busy.",
        "&bull; <b>Memory:</b> one user's pipeline peaks at 6-7 GB, two users at 7.5 GB (server + PLINK + R). "
        "<b>Disk:</b> 1.2-3.2 GB per project, growing with the number of loci.",
        "&bull; <b>6 problems found</b> that affect production, including one bug that breaks COJO on "
        "Linux and a GWAS file format real users will upload that the server rejects (section 7).",
    ]),
    Spacer(1, 4),
    KeepTogether([p("Recommended production server (for the parts measured)", ParagraphStyle(
        "b", parent=BODY, fontName="Helvetica-Bold")),
        table([["Resource", "Recommendation", "Why"],
               ["CPU", "16 vCPU", "SuSiE keeps 6-7 cores busy per user; 2 users filled all 8 test cores"],
               ["RAM", "32 GB", "6-7 GB per active pipeline, 7.5 GB for 2 users; PLINK clumping reserves 6 GB per call"],
               ["Java heap", "-Xmx6g", "2 GB was enough for processing; 6 GB leaves room for bursts"],
               ["Disk (projects)", "200 GB or more, SSD", "1.2-3.2 GB per project; quota caps a user at 8 GB, "
                                                          "so ~25 active users fit in 200 GB"],
               ["Disk (reference)", "~10 GB", "GENCODE GFF3 1.7 GB, 1000G EUR panel 3.6 GB + per-chromosome copy 2.6 GB"],
               ["Job limits", "max.jobs.total = 4 or less", "Each heavy job can use several cores and GBs"]],
              [32, 40, 102])]),

    p("2. Test setup", H2),
    table([["Item", "Value"],
           ["Test machine", "8 CPU cores, 23 GB RAM, Ubuntu 24.04, OpenJDK 21. Other containers were running, "
                            "so a dedicated server will do slightly better."],
           ["Real GWAS files", "PGC ADHD 2019 (8.1 M SNPs), PGC3 Schizophrenia EUR (7.7 M SNPs), "
                               "GIANT Height 2018 (2.3 M SNPs), all GRCh37, uploaded gzipped"],
           ["Reference data", "GENCODE v37lift37 GFF3; 1000 Genomes EUR PLINK panel (503 people, 22.7 M variants); "
                              "LDSC eur_w_ld_chr"],
           ["Tools", "PLINK 1.9, GCTA 1.94.1, MAGMA 1.10, R 4.3.3 with susieR 0.14.2, coloc 5.2.3, bigsnpr 1.12.18"],
           ["Synthetic data", "1 M / 5 M / 10 M SNP files and a GENCODE-sized annotation, for the load and memory tests"],
           ["Analysis cap", "Each 'run on all loci' analysis was stopped after 10 min; longer runs are projected "
                            "from the measured time per locus"]],
          [32, 142]),

    p("3. Real data - one user, full pipeline", H2),
    p("Times are for one user alone on the server. Memory is the peak of the server plus the programs it starts.", SMALL),
    table([["Step", "ADHD 2019 (12 loci)", "SCZ PGC3 (178 loci)", "Height 2018 (1,345 loci)"],
           ["Upload", "21 s", "8 s", "3 s"],
           ["Loci identification (PLINK)", "2.4 min*", "2.2 min", "2.9 min"],
           ["QC (lambda GC)", "35 s", "17 s", "5 s"],
           ["Processing incl. LD", "2.6 min", "13.7 min (4.6 s/locus)", "~1.7 h (projected)"],
           ["Build LD base", "4.5 min (22 s/locus)", "31.5 min (10.6 s/locus)", "~4 h (projected)"],
           ["FINEMAP (Wakefield)", "5 s", "50 s", "~6 min (projected)"],
           ["SuSiE fine-mapping", "5.3 min (26 s/locus, ~5 cores)", "~50 min (17 s/locus, ~6 cores)**",
            "~6.3 h (projected)"],
           ["MAGMA gene test", "27 s (2.3 s/locus)", "9.8 min (3.3 s/locus)", "~1.2 h (projected)"],
           ["COJO (GCTA)***", "14 s", "1.2 min", "~10 min (projected)"],
           ["Local heritability", "58 s (4.9 s/locus)", "~14 min (4.6 s/locus)**", "~1.7 h (projected)"],
           ["Genome-wide LDSC", "18 s", "20 s", "~20 s"],
           ["<b>Whole journey</b>", "<b>17.8 min</b>", "<b>~2 h</b>", "<b>~15 h (projected)</b>"],
           ["Peak memory", "6.5 GB", "6.9 GB", "5.8 GB (first steps)"],
           ["Disk per project", "1.2 GB", "3.2 GB", "~15 GB (estimate, above the 8 GB user quota)"]],
          [44, 44, 44, 42], highlight_rows=(12,)),
    p("* 9.3 min when the reference panel is a single file; 2.4 min with per-chromosome copies (see problem 3). "
      "** Stopped at the 10 min cap (SuSiE after 38 of 177 loci, local heritability after 131); full time projected. "
      "*** Only works on Linux with the gcta64.exe workaround (problem 1). "
      "Height was stopped during processing; later steps use the per-locus rates measured on SCZ.", SMALL),

    p("4. Real data - two users at the same time", H2),
    p("ADHD and schizophrenia started together on one server (default limits: 1 heavy job per user, 4 in total).", SMALL),
    table([["Measure", "Result"],
           ["Both journeys finished", "68 min end to end (SCZ analyses capped at 10 min each), <b>no errors</b>"],
           ["ADHD user", "Finished in about 12 min while SCZ was running (FINEMAP, SuSiE, MAGMA, COJO, ranking, "
                         "heritability and LDSC all succeeded)"],
           ["SCZ user", "Processing 15.4 min, LD base 21.8 min, SuSiE 12 s/locus, MAGMA 1.7 s/locus"],
           ["CPU", "3.2 cores on average, <b>all 8 cores</b> at peak (SuSiE ~6.8 cores)"],
           ["Peak memory", "7.5 GB (both users identifying loci with PLINK at the same time)"]],
          [45, 129]),

    p("5. Synthetic data - processing, memory and several users", H2),
    table([["Test", "Result"],
           ["One project, 1 M / 5 M / 10 M SNPs", "Upload 0.6 / 2.3 / 3.6 s, loci 1 / 3 / 5.5 s, QC 1 / 5 / 10 s, "
                                                  "processing 12-25 s. ~1.3 cores, peak ~3."],
           ["4 users processing 5 M SNPs at once", "~25 s each, 4-6 cores busy, no errors"],
           ["Memory needed (4 users at once)", "Java heap 2 GB: 2.3 GB used (more CPU on garbage collection); "
                                                "4 GB: 3.8 GB; 8 GB: 7.7 GB (Java simply grows to the limit)"],
           ["Unit tests", "27 of 28 pass, each under 0.5 s; the two server tests take 10-14 s and ~330 MB"]],
          [55, 119]),

    p("6. Many people viewing results at the same time", H2),
    p("Each simulated viewer opens a locus page, reads it for 1-3 seconds, then opens the next.", SMALL),
    table([["Viewers", "Requests/s", "Median", "95% under", "99% under", "Server CPU", "Errors"],
           ["10", "21", "2.5 ms", "6 ms", "9 ms", "0.3 cores", "0"],
           ["50", "106", "2.9 ms", "14 ms", "21 ms", "0.4 cores", "0"],
           ["100", "207", "3.3 ms", "31 ms", "74 ms", "0.4 cores", "0"],
           ["200", "395", "4.8 ms", "95 ms", "170 ms", "0.5 cores", "0"],
           ["No pauses (32 clients)", "1,005+", "26 ms", "70 ms", "118 ms", "1.3 cores", "0"]],
          [38, 22, 20, 22, 22, 26, 24]),
    p("The last row was limited by the test client, not the server.", SMALL),

    KeepTogether([
    p("7. Problems found (fix before production)", H2),
    table([["#", "Problem", "Impact", "Fix"],
           ["1", "COJO looks for the Windows file name <i>gcta64.exe</i> on Linux "
                 "(tools/cojo_conditional.yaml default + ToolLocator)",
            "COJO fails on every locus when GCTA is installed as bin/gcta64, as DEPLOY.md says",
            "Small code fix, or name the file gcta64.exe"],
           ["2", "Files starting with '##' comment lines (e.g. PGC3 SCZ) are rejected: "
                 "\"Missing required columns\"",
            "Real users uploading published PGC files will fail", "Skip '##' lines on upload"],
           ["3", "Single-file reference panel makes loci identification ~4x slower (9.3 vs 2.4 min)",
            "Slow first step for every user", "Also ship panel_chr1..22 next to the panel"],
           ["4", "PLINK clumping always reserves 6 GB (hard-coded --memory 6000)",
            "4 jobs at once may need ~24 GB for PLINK alone", "Use the RAM-based PLINK budget"],
           ["5", "SuSiE / FINEMAP fail without a sample size (N) in project settings",
            "Users get 'N is required' on all loci", "Make N required or explain it in the UI"],
           ["6", "ServerSecurityTest fails on Linux (Windows path check)",
            "CI on Linux fails; not a security hole", "Make the test OS-aware"]],
          [7, 62, 58, 47])]),

    p("8. Limitations and next steps", H2),
    *bullets([
        "&bull; Not tested: COLOC and GWAMA (they compare datasets), mvSuSiE / SuSiEx, CADD-based ranking "
        "(the 2.4 GB CADD file was not installed), and email delivery.",
        "&bull; Height was stopped during processing (it would take ~15 h); its later steps are projected, not measured. "
        "Long SuSiE and heritability runs were capped at 10 min and projected from the measured time per locus.",
        "&bull; Rerun on the production machine before launch: <font face='Courier'>benchmark/bench.py</font> and "
        "<font face='Courier'>benchmark/bench_real.py</font> in the repository.",
    ]),
]

doc = SimpleDocTemplate(OUT, pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm,
                        topMargin=16 * mm, bottomMargin=16 * mm,
                        title="LYNXgwas Server - Load Test Report", author="LYNXgwas team")
doc.build(story, onFirstPage=footer, onLaterPages=footer)
print("wrote", OUT)
