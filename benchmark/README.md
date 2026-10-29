# Server sizing benchmark

Measures wall time, CPU and memory of LYNXgwas Server for the steps a user goes through
(upload → loci identification → QC → processing → viewing), several users at once, and many
viewers reading results. It drives a real `ServerMain` over HTTP and samples the JVM from `/proc`.

Not covered: LD, fine-mapping (SuSiE, FINEMAP, coloc, COJO, MAGMA), heritability. These need
PLINK, R and the reference panel, which this benchmark does not set up.

## Run it (Linux, from the repo root, after `sh build.sh`)

```sh
mkdir -p /tmp/bench-data
python3 benchmark/gen_gff3.py  /tmp/bench-data/annotation.gff3 100000   # GENCODE-sized (~3.2 M lines)
python3 benchmark/gen_gwas.py  1000000  /tmp/bench-data/gwas_1M.tsv
python3 benchmark/gen_gwas.py  5000000  /tmp/bench-data/gwas_5M.tsv
python3 benchmark/gen_gwas.py  10000000 /tmp/bench-data/gwas_10M.tsv
python3 benchmark/bench.py --data /tmp/bench-data --xmx 8g          # ~7 min
sh benchmark/time_tests.sh                                          # per-test timings
```

`gen_gwas.py` needs numpy. To use the real annotation instead of the synthetic one, copy or link
your GENCODE GFF3 to `/tmp/bench-data/annotation.gff3`.

Options: `--sizes 1M,5M,10M`, `--xmx 2g`, `--concurrent 4 --concurrent-size 5M`,
`--load-clients 10,50,100,200`, `--skip sizes,concurrent,load`.

Notes
- Each scenario starts a fresh JVM, so memory peaks are per scenario.
- "rss" is the server process's resident memory. With a large `-Xmx` the JVM grows its heap
  lazily, so rss mostly reflects `-Xmx`. Run with a smaller `--xmx` to find the real minimum.
- Load clients send `X-Forwarded-For` (`trust.proxy=true`) so each viewer counts as its own IP
  for the per-IP rate limit (600 burst, 300/min), as it would behind the reverse proxy.
- The Python load generator is the bottleneck above ~1,000 req/s. Use `wrk` or `k6` to find the
  server's real ceiling.

`results/` holds the run made on 2026-10-06 (8 CPUs, 23 GB RAM, OpenJDK 21).

## Full pipeline on real data (`bench_real.py`)

Runs the whole user journey (upload, loci with PLINK clumping, QC, processing with LD, LD base,
FINEMAP, SuSiE, MAGMA, COJO, SNP ranking, local heritability, LDSC) on real GWAS files, with the
real reference panel and tools. Needs a setup JSON: `gff3`, `ref_panel` (PLINK prefix), `ldsc`,
`plink`, `path_dirs` (folders holding `Rscript`, `gcta64`, `magma`), and `datasets` (file, column
mapping, sample size). Each "run on all loci" analysis stops after `--tool-cap-min` minutes and
its full time is projected from the time per locus.

```sh
python3 benchmark/bench_real.py --setup real_setup.json --tool-cap-min 10 --concurrent
```

Notes: the server needs `gcta64.exe` on PATH for COJO on Linux (bug), files starting with `##`
lines must be cleaned first (bug), and per-chromosome panel copies (`<panel>_chr1..22`) make
loci identification ~4x faster. `results-real/` holds the 2026-10-06 run;
`LYNXgwas_Server_Load_Test_Report.pdf` is built by `make_report.py`.
