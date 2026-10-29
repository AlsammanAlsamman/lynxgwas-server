#!/usr/bin/env python3
"""Synthetic GENCODE-like GFF3 for load testing (same line count and attribute style).

Usage: gen_gff3.py OUT.gff3 [N_GENES] [SEED]

Default 62,000 genes gives ~3 M lines / ~1 GB, close to gencode.v37lift37.annotation.gff3,
so parsing cost and memory match production. Gene structures are random, not biological.
"""
import random
import sys

CHR_LEN = [249250621, 243199373, 198022430, 191154276, 180915260, 171115067, 159138663,
           146364022, 141213431, 135534747, 135006516, 133851895, 115169878, 107349540,
           102531392, 90354753, 81195210, 78077248, 59128983, 63025520, 48129895, 51304566]
TX_TYPES = ["protein_coding"] * 5 + ["lncRNA"] * 3 + ["processed_pseudogene", "retained_intron"]


def attrs(**kv):
    return ";".join(f"{k}={v}" for k, v in kv.items())


def write_gene(f, rnd, chrom, gnum, start):
    gid = f"ENSG{gnum:011d}.5"
    gname = f"GENE{gnum}"
    strand = rnd.choice("+-")
    length = int(rnd.lognormvariate(10, 1.2)) + 500
    end = start + length
    gtype = rnd.choice(TX_TYPES)
    common = dict(gene_id=gid, gene_type=gtype, gene_name=gname, level=2, hgnc_id=f"HGNC:{gnum % 50000}")
    f.write(f"chr{chrom}\tHAVANA\tgene\t{start}\t{end}\t.\t{strand}\t.\t"
            f"{attrs(ID=gid, **common, havana_gene=f'OTTHUMG{gnum:011d}.2')}\n")
    for t in range(max(1, int(rnd.expovariate(1 / 3.5)))):
        tid = f"ENST{gnum * 10 + t:011d}.3"
        ttype = rnd.choice(TX_TYPES)
        ts = start + rnd.randint(0, max(1, length // 10))
        te = end - rnd.randint(0, max(1, length // 10))
        tattrs = dict(ID=tid, Parent=gid, **common, transcript_id=tid, transcript_type=ttype,
                      transcript_name=f"{gname}-{201 + t}", tag="basic,CCDS", transcript_support_level=1)
        f.write(f"chr{chrom}\tHAVANA\ttranscript\t{ts}\t{te}\t.\t{strand}\t.\t{attrs(**tattrs)}\n")
        n_ex = max(1, int(rnd.gauss(6, 3)))
        step = max(2, (te - ts) // n_ex)
        for e in range(n_ex):
            es = ts + e * step
            ee = min(te, es + max(1, step // 3))
            base = dict(Parent=tid, gene_id=gid, transcript_id=tid, gene_type=gtype, gene_name=gname,
                        transcript_type=ttype, exon_number=e + 1, exon_id=f"ENSE{gnum * 100 + e:011d}.1")
            f.write(f"chr{chrom}\tHAVANA\texon\t{es}\t{ee}\t.\t{strand}\t.\t"
                    f"{attrs(ID=f'exon:{tid}:{e + 1}', **base)}\n")
            if ttype == "protein_coding" and 0 < e < n_ex - 1:
                f.write(f"chr{chrom}\tHAVANA\tCDS\t{es}\t{ee}\t.\t{strand}\t0\t"
                        f"{attrs(ID=f'CDS:{tid}', **base)}\n")
        if ttype == "protein_coding":
            for kind in ("five_prime_UTR", "three_prime_UTR"):
                f.write(f"chr{chrom}\tHAVANA\t{kind}\t{ts}\t{ts + 50}\t.\t{strand}\t.\t"
                        f"{attrs(ID=f'UTR:{tid}', Parent=tid, gene_id=gid, transcript_id=tid)}\n")
    return end


def main():
    out = sys.argv[1]
    n_genes = int(sys.argv[2]) if len(sys.argv) > 2 else 62_000
    rnd = random.Random(int(sys.argv[3]) if len(sys.argv) > 3 else 7)
    total = sum(CHR_LEN)
    gnum = 0
    with open(out, "w") as f:
        f.write("##gff-version 3\n#description: synthetic GENCODE-like annotation for benchmarking\n")
        for chrom, clen in enumerate(CHR_LEN, start=1):
            n = int(n_genes * clen / total)
            spacing = clen // (n + 1)
            for i in range(n):
                gnum += 1
                write_gene(f, rnd, chrom, gnum, i * spacing + rnd.randint(1, max(2, spacing // 2)))
    print(f"wrote {gnum} genes -> {out}")


if __name__ == "__main__":
    main()
