# LYNXgwas: local SNP-heritability of one locus from GWAS summary statistics (HESS; Shi et al. 2016).
#
#   Rscript hess_local.R <manifest.json>
#
# The manifest (written by LocalServer) gives the locus's harmonised GWAS file, its matched reference
# panel (PLINK prefix), the sample size, the maximum number of LD eigenvectors and the output folder.
#   1. z = beta/se per SNP, SNPs with per-SNP N < 80% of the maximum dropped (meta-analyses), oriented
#      to the panel's A1 (palindromic and multi-allelic SNPs dropped), monomorphic panel SNPs dropped;
#      Sample size: the per-SNP N column when present (median after the filter), else the configured N;
#      a PGC-style NEFF column (half the effective N; detected against the cases/controls) is doubled,
#      the same rule as LYNXgwas's LD score regression;
#   2. full LD of the locus from the panel (bigsnpr), then SNPs whose z contradicts the LD removed: each
#      z is predicted from all others under the regularised LD 0.9 R + 0.1 I (conditional mean and
#      variance from its inverse, via Cholesky), and SNPs with an allele-switch log-likelihood ratio
#      -2 z mu / v > 2 and |z| > 2 are removed (at most 5%) — the estimate is sensitive to LD mismatch;
#   3. HESS: with standardised effects b = z / sqrt(n) and the LD eigen-decomposition R = U L U', keep
#      the top k eigenvectors (eigenvalue > 1e-3 x the largest, at most k_max) and estimate
#          h2 = (n * sum_i (u_i' b)^2 / l_i - k) / (n - k),
#      with the approximate variance (n / (n - k))^2 * (2 k (1 - h2)^2 / n^2 + 4 h2 (1 - h2) / n);
#   4. writes result.tsv (one row) and prints a summary.
args <- commandArgs(trailingOnly = TRUE)
suppressPackageStartupMessages(library(jsonlite))
`%||%` <- function(a, b) if (is.null(a)) b else a
man <- fromJSON(args[1], simplifyVector = FALSE)
if (nzchar(man$extra_lib %||% "")) .libPaths(c(man$extra_lib, .libPaths()))
fail <- function(msg) { cat("ERROR:", msg, "\n"); quit(status = 1) }
need <- c("bigsnpr", "data.table")
miss <- need[!vapply(need, requireNamespace, TRUE, quietly = TRUE)]
if (length(miss)) fail(paste("R packages not installed:", paste(miss, collapse = ", ")))
suppressPackageStartupMessages({ library(bigsnpr); library(data.table) })
COMP <- c(A = "T", T = "A", C = "G", G = "C")
work <- man$run_dir

# ── 1. z-scores oriented to the panel ───────────────────────────────────
bim <- fread(paste0(man$matched_ref, ".bim"), header = FALSE, col.names = c("CHR", "SNP", "CM", "BP", "A1", "A2"), data.table = FALSE)
bim$key <- paste0(sub("^chr", "", bim$CHR), ":", bim$BP)
bim <- bim[!bim$key %in% bim$key[duplicated(bim$key)], ]
bim <- bim[!(paste0(bim$A1, bim$A2) %in% c("AT", "TA", "CG", "GC")), ]
rownames(bim) <- bim$key
g <- fread(man$harmonized, data.table = FALSE)
g$beta <- suppressWarnings(as.numeric(g$beta)); g$se <- suppressWarnings(as.numeric(g$se))
g <- g[is.finite(g$beta) & is.finite(g$se) & g$se > 0, , drop = FALSE]
n_used <- as.numeric(man$sample_n)
if ("n" %in% names(g)) {
  nn <- suppressWarnings(as.numeric(g$n))
  if (sum(is.finite(nn)) > 0.5 * nrow(g)) {
    g <- g[is.finite(nn) & nn >= 0.8 * max(nn, na.rm = TRUE), , drop = FALSE]
    n_used <- median(suppressWarnings(as.numeric(g$n)), na.rm = TRUE)
  }
}
n_def <- "configured or per-SNP N"
ca <- as.numeric(man$n_cases %||% 0); co <- as.numeric(man$n_controls %||% 0)
if (ca > 0 && co > 0 && grepl("neff", tolower(man$n_col %||% ""))) {
  r_half <- n_used / (4 / (1 / ca + 1 / co))
  if (r_half > 0.4 && r_half < 0.6) { n_used <- 2 * n_used; n_def <- "PGC NEFF column doubled" }
}
if (!is.finite(n_used) || n_used <= 0) fail("Sample size (N) is required: set it in the project configuration")
g$key <- paste0(sub("^chr", "", g$chr), ":", g$pos)
g <- g[!duplicated(g$key) & g$key %in% bim$key, , drop = FALSE]
b <- bim[g$key, ]
ea <- toupper(g$ea); nea <- toupper(g$nea)
same <- (ea == b$A1 & nea == b$A2) | (COMP[ea] == b$A1 & COMP[nea] == b$A2)
swap <- (ea == b$A2 & nea == b$A1) | (COMP[ea] == b$A2 & COMP[nea] == b$A1)
same[is.na(same)] <- FALSE; swap[is.na(swap)] <- FALSE
z <- ifelse(same, g$beta / g$se, ifelse(swap, -g$beta / g$se, NA))
keep <- is.finite(z)
if (sum(keep) < 50) fail(sprintf("Only %d SNPs align with the reference panel: too sparse for local heritability", sum(keep)))
z <- z[keep]; keys <- g$key[keep]
ord <- order(bim[keys, "BP"]); z <- z[ord]; keys <- keys[ord]

# ── 2. LD from the panel, then the LD consistency check ─────────────────
unlink(file.path(work, c("ref_bigsnp.bk", "ref_bigsnp.rds")))
rds <- snp_readBed(paste0(man$matched_ref, ".bed"), backingfile = file.path(work, "ref_bigsnp"))
obj <- snp_attach(rds)
ix <- match(bim[keys, "SNP"], obj$map$marker.ID)
ok <- !is.na(ix)
z <- z[ok]; keys <- keys[ok]; ix <- ix[ok]
maf <- snp_MAF(obj$genotypes, ind.col = ix)
ok <- is.finite(maf) & maf > 0
z <- z[ok]; keys <- keys[ok]; ix <- ix[ok]
R <- as.matrix(snp_cor(obj$genotypes, ind.col = ix, size = length(ix), ncores = 1L))
R <- (R + t(R)) / 2; R[!is.finite(R)] <- 0; diag(R) <- 1
removed <- 0L
Om <- tryCatch(chol2inv(chol(0.9 * R + 0.1 * diag(nrow(R)))), error = function(e) NULL)
if (!is.null(Om)) {
  v <- 1 / diag(Om); mu <- z - as.vector(Om %*% z) * v
  llr <- -2 * z * mu / v
  bad <- which(llr > 2 & abs(z) > 2)
  if (length(bad)) {
    bad <- head(bad[order(-llr[bad])], max(1L, ceiling(0.05 * length(z))))
    z <- z[-bad]; keys <- keys[-bad]; R <- R[-bad, -bad, drop = FALSE]
    removed <- length(bad)
  }
}

# ── 3. HESS ─────────────────────────────────────────────────────────────
# Only the top k_max eigenpairs are used: compute just those (RSpectra) when the locus is large.
k_max <- as.integer(man$k_max %||% 50)
if (length(z) > 3 * k_max && requireNamespace("RSpectra", quietly = TRUE)) {
  e <- RSpectra::eigs_sym(R, k = k_max, which = "LA")
  o <- order(e$values, decreasing = TRUE); e <- list(values = e$values[o], vectors = e$vectors[, o, drop = FALSE])
} else {
  e <- eigen(R, symmetric = TRUE)
}
lam <- e$values
k <- min(k_max, sum(lam > 1e-3 * lam[1]))
bhat <- z / sqrt(n_used)
proj <- crossprod(e$vectors[, seq_len(k), drop = FALSE], bhat)
q <- sum(proj^2 / lam[seq_len(k)])
h2 <- (n_used * q - k) / (n_used - k)
v <- (n_used / (n_used - k))^2 * (2 * k * (1 - h2)^2 / n_used^2 + 4 * h2 * (1 - h2) / n_used)
se <- sqrt(max(v, 0))
p <- pnorm(-h2 / se)    # one-sided, h2 > 0
res <- data.frame(h2_local = signif(h2, 6), h2_local_se = signif(se, 6), p_h2 = signif(p, 4),
                  k = k, n_snps = length(z), n = round(n_used), snps_removed_ld_check = removed,
                  ld_source = "1000 Genomes reference panel", lead_abs_z = signif(max(abs(z)), 5), n_definition = n_def)
fwrite(res, file.path(work, "result.tsv"), sep = "\t")
cat(sprintf("Local h2 = %.3g (SE %.2g, p %.2g) from %d SNPs, k = %d eigenvectors, N = %.0f; %d SNPs removed by the LD check\n",
            h2, se, p, length(z), k, n_used, removed))
