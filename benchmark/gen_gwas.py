#!/usr/bin/env python3
"""Synthetic GWAS summary statistics for load testing.

Usage: gen_gwas.py N_SNPS OUT.tsv [N_PEAKS] [SEED]

SNPs are spread over chr1-22 in proportion to GRCh37 chromosome lengths, sorted by position.
P-values are null (uniform, slight inflation) except around N_PEAKS association peaks, where
-log10(p) decays with distance from a lead SNP, so loci identification finds realistic loci.
"""
import sys
import numpy as np

CHR_LEN = [249250621, 243199373, 198022430, 191154276, 180915260, 171115067, 159138663,
           146364022, 141213431, 135534747, 135006516, 133851895, 115169878, 107349540,
           102531392, 90354753, 81195210, 78077248, 59128983, 63025520, 48129895, 51304566]
BASES = np.array(list("ACGT"))
CHUNK = 500_000


def chrom_counts(n):
    w = np.array(CHR_LEN, dtype=float)
    c = np.floor(n * w / w.sum()).astype(int)
    c[0] += n - c.sum()
    return c


def peak_pvalues(pos, chrom, peaks, rng):
    """-log10(p) boost for SNPs near a peak on this chromosome."""
    boost = np.zeros(len(pos))
    for pc, center, lead in peaks:
        if pc != chrom:
            continue
        d = np.abs(pos - center)
        near = d < 500_000
        width = rng.uniform(30_000, 120_000)
        boost[near] = np.maximum(boost[near], lead * np.exp(-d[near] / width) * rng.uniform(0.6, 1.0, near.sum()))
    return boost


def main():
    n = int(sys.argv[1])
    out = sys.argv[2]
    n_peaks = int(sys.argv[3]) if len(sys.argv) > 3 else 80
    rng = np.random.default_rng(int(sys.argv[4]) if len(sys.argv) > 4 else 42)

    counts = chrom_counts(n)
    w = np.array(CHR_LEN, dtype=float)
    peak_chr = rng.choice(np.arange(1, 23), size=n_peaks, p=w / w.sum())
    peaks = [(int(c), int(rng.uniform(1e6, CHR_LEN[c - 1] - 1e6)), rng.uniform(8, 30)) for c in peak_chr]

    snp_id = 0
    with open(out, "w") as f:
        f.write("SNP\tCHR\tBP\tA1\tA2\tFRQ\tBETA\tSE\tP\tN\n")
        for chrom, cn in enumerate(counts, start=1):
            pos_all = np.sort(rng.choice(CHR_LEN[chrom - 1] - 1000, size=cn, replace=False) + 500)
            for s in range(0, cn, CHUNK):
                pos = pos_all[s:s + CHUNK]
                m = len(pos)
                a1 = rng.integers(0, 4, m)
                a2 = (a1 + rng.integers(1, 4, m)) % 4
                frq = rng.uniform(0.01, 0.99, m)
                se = rng.uniform(0.005, 0.05, m)
                null_logp = -np.log10(rng.uniform(1e-300, 1, m)) * 1.04
                logp = np.maximum(null_logp, peak_pvalues(pos, chrom, peaks, rng))
                p = np.power(10.0, -logp)
                beta = se * np.sqrt(np.maximum(logp, 0) * 4.6) * rng.choice([-1, 1], m)
                nn = rng.integers(40_000, 60_000, m)
                ids = np.arange(snp_id, snp_id + m) + 1_000_000
                snp_id += m
                rows = [f"rs{i}\t{chrom}\t{bp}\t{x}\t{y}\t{fq:.4f}\t{b:.5f}\t{e:.5f}\t{pv:.4g}\t{k}"
                        for i, bp, x, y, fq, b, e, pv, k in
                        zip(ids, pos, BASES[a1], BASES[a2], frq, beta, se, p, nn)]
                f.write("\n".join(rows))
                f.write("\n")
    print(f"wrote {n} SNPs, {n_peaks} peaks -> {out}")


if __name__ == "__main__":
    main()
