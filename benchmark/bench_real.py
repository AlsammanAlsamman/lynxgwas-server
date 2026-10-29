#!/usr/bin/env python3
"""Full-pipeline benchmark of LYNXgwas Server on real GWAS files and real reference data.

Same measurement as bench.py (fresh JVM, /proc sampling of the server and every child process
it starts: PLINK, R, GCTA, MAGMA), but through the whole user journey: upload, loci
identification (PLINK clumping), QC, processing with LD, base artifacts, then every per-locus
analysis over all loci, and genome-wide LDSC heritability.

Usage (repo root, after build.sh):
  python3 benchmark/bench_real.py --setup benchmark/real_setup.json [--datasets adhd,scz]
         [--tool-cap-min 10] [--concurrent] [--xmx 6g]

The setup JSON names the reference files, tool folders and datasets (see real_setup.example.json).
A batch analysis still running after --tool-cap-min minutes is cancelled; its full time is
projected from the measured time per locus (marked "projected").
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bench import Monitor, Server, User, timed  # noqa: E402

TOOLS = ["finemap", "susie_finemapping", "magma_gene", "cojo_conditional", "snp_rank", "heritability_local"]


class RealServer(Server):
    """Server configured with the real reference panel, GFF3, tools on PATH and LDSC scores."""

    def __init__(self, setup, workdir, port, xmx, log):
        super().__init__(workdir, port, xmx, setup["gff3"], log)
        self.setup = setup

    def prepare(self):
        super().prepare()
        props = (self.workdir / "server.properties").read_text()
        props = props.replace("ref.panel.path=\n", f"ref.panel.path={self.setup['ref_panel']}\n"
                                                   f"ref.panel.population=EUR\n")
        (self.workdir / "server.properties").write_text(props)
        (self.workdir / "plink").symlink_to(self.setup["plink"])          # PlinkSubsetter looks in <app>/

    def start(self):
        path = os.pathsep.join(self.setup["path_dirs"] + [os.environ["PATH"]])
        env_extra = {"PATH": path, "LYNXGWAS_HOME": str(self.workdir)}
        if self.setup.get("ldsc"):
            env_extra["LYNXGWAS_LDSC"] = self.setup["ldsc"]
        saved = dict(os.environ)
        os.environ.update(env_extra)
        try:
            return super().start()
        finally:
            os.environ.clear()
            os.environ.update(saved)


def run_batch(user, mon, pid, tool, cap_s, results):
    """POST run-all for one tool; poll; cancel at cap and project the full time."""
    def go():
        st, b = user.post(f"/api/project/{pid}/analysis/run-all", {"tool": tool, "parallel": "2"})
        if st == 409 or st == 429:      # one heavy job per user: wait for the previous one
            time.sleep(2)
            st, b = user.post(f"/api/project/{pid}/analysis/run-all", {"tool": tool, "parallel": "2"})
        assert st == 202, (tool, st, b)
        t0 = time.monotonic()
        while True:
            st, b = user.get(f"/api/project/{pid}/analysis/run-all-progress?tool={tool}")
            j = json.loads(b)
            if j.get("finished"):
                return j
            if time.monotonic() - t0 > cap_s and not j.get("cancelled"):
                user.get(f"/api/project/{pid}/analysis/run-all-cancel?tool={tool}")
                user.post(f"/api/project/{pid}/analysis/run-all-cancel?tool={tool}", {})
            time.sleep(1)
    j = timed(mon, f"analysis: {tool}", go, results)
    row = results[-1]
    row.update({"loci_total": j["total"], "loci_done": j["done"], "ok": j["ok"], "failed": j["failed"],
                "skipped": j["skipped"], "errors": j["errors"][:5]})
    if j["done"] and j["done"] < j["total"]:
        row["projected_full_s"] = round(row["wall_s"] / j["done"] * j["total"])
        row["projected"] = True
    per = row["wall_s"] / j["done"] if j["done"] else None
    print(f"      -> {j['done']}/{j['total']} loci, ok {j['ok']}, failed {j['failed']}, skipped {j['skipped']}"
          + (f", {per:.1f} s/locus" if per else "")
          + (f", projected full run {row['projected_full_s']} s" if row.get("projected") else "")
          + (f"\n         errors: {j['errors'][:2]}" if j["errors"] else ""), flush=True)


def full_pipeline(user, mon, ds, results, cap_s, tools):
    name = ds["name"]
    st, b = user.post("/api/my/projects", {"name": name, "description": ds.get("description", "")})
    assert st == 200, b
    pid = json.loads(b)["id"]
    gwas = ds["file"]
    st, b = timed(mon, "upload", lambda: user.upload(f"/api/upload/{pid}/gwas", gwas), results,
                  {"file_mb": round(os.path.getsize(gwas) / 2**20)})
    assert st == 200, (st, b)
    cols = {"col.varid": "", "col.or": "", "col.info": "", "col.beta": "", "col.rsid": "", "ld.enabled": "true",
            **ds["columns"], "_name": name}
    st, b = user.post(f"/api/project/{pid}/config", cols)
    assert st == 200, (st, b)

    def loci():
        st, b = user.post("/api/loci-identify", {"project_id": pid})
        assert st == 200, b
        return user.poll(f"/api/loci-progress?project={pid}", lambda j: j.get("done"))
    lp = timed(mon, "loci-identify (PLINK clump)", loci, results)
    assert not lp.get("error"), lp
    results[-1]["loci"] = lp.get("loci_found")
    print(f"      -> {lp.get('loci_found')} loci", flush=True)

    st, b = timed(mon, "qc (lambda GC)", lambda: user.get(f"/api/project/{pid}/qc"), results)
    assert st == 200, b

    def process():
        st, b = user.post("/api/projects/process", {"id": pid})
        assert st == 200, b
        return user.poll(f"/api/project/{pid}/progress", lambda j: j.get("done"), every=1)
    pr = timed(mon, "process (incl. LD)", process, results)
    assert not str(pr.get("phase", "")).startswith("Error"), pr

    def base():
        st, b = user.post(f"/api/project/{pid}/analysis/build-base", {"threads": "2"})
        assert st in (200, 202), (st, b)
        return user.poll(f"/api/project/{pid}/analysis/build-base-progress", lambda j: j.get("done"), every=1)
    bp = timed(mon, "build base (LD matrices)", base, results)
    assert not str(bp.get("phase", "")).startswith("Error"), bp

    for tool in tools:
        run_batch(user, mon, pid, tool, cap_s, results)

    def ldsc():
        st, b = user.post(f"/api/project/{pid}/heritability/run", {})
        assert st == 202, (st, b)
        return user.poll(f"/api/project/{pid}/heritability",
                         lambda j: j.get("status") == "done" or str(j.get("status", "")).startswith("error"), every=1)
    h = timed(mon, "heritability (LDSC, genome)", ldsc, results)
    results[-1]["status"] = h.get("status")
    if h.get("status") != "done":
        print(f"      -> {h.get('status')}", flush=True)

    st, b = user.get(f"/api/project/{pid}/manifest")
    return pid


def disk_mb(path):
    """Disk used under path, in MB (du), for per-project storage sizing."""
    out = subprocess.run(["du", "-sm", str(path)], capture_output=True, text=True).stdout.split()
    return int(out[0]) if out else None


def one_dataset(args, setup, ds, port_offset=0):
    print(f"\n== {ds['name']}: {os.path.getsize(ds['file']) >> 20} MB, -Xmx{args.xmx} ==", flush=True)
    out = Path(args.out)
    srv = RealServer(setup, out / f"workdir-{ds['key']}", args.port + port_offset, args.xmx, out / f"server-{ds['key']}.log")
    srv.prepare()
    startup = srv.start()
    mon = Monitor(srv.proc.pid).start()
    res = [{"step": "startup", "wall_s": round(startup, 2)}]
    try:
        u = User(srv, f"{ds['key']}@example.org", "10.0.0.1")
        u.sign_up(srv.workdir / "server-data" / "outbox")
        full_pipeline(u, mon, ds, res, args.tool_cap_min * 60, args.tools)
        res.append({"step": "WHOLE RUN", "wall_s": round(time.monotonic() - mon.samples[0][0], 1),
                    "project_disk_mb": disk_mb(srv.workdir / "projects"),
                    **mon.window(mon.samples[0][0], time.monotonic())})
        print(f"    WHOLE RUN {res[-1]['wall_s']} s, peak rss {res[-1]['peak_rss_mb']} MB, "
              f"project disk {res[-1]['project_disk_mb']} MB", flush=True)
    except Exception as e:
        res.append({"step": "ERROR", "error": repr(e)})
        print("    ERROR:", repr(e), flush=True)
    finally:
        mon.stop()
        srv.stop()
        shutil.rmtree(srv.workdir, ignore_errors=True)
    for r in res:
        r["scenario"] = f"real-{ds['key']}"
    return res


def concurrent(args, setup, datasets):
    """All datasets at once, one user each, on one server (like several people starting together)."""
    print(f"\n== {len(datasets)} users at once ({', '.join(d['key'] for d in datasets)}), -Xmx{args.xmx} ==", flush=True)
    out = Path(args.out)
    srv = RealServer(setup, out / "workdir-concurrent", args.port, args.xmx, out / "server-concurrent.log")
    srv.prepare()
    srv.start()
    mon = Monitor(srv.proc.pid).start()
    per, errors = {}, []
    try:
        users = {d["key"]: User(srv, f"{d['key']}@example.org", f"10.0.1.{i + 1}") for i, d in enumerate(datasets)}
        for u in users.values():
            u.sign_up(srv.workdir / "server-data" / "outbox")

        def run(d):
            res = []
            try:
                full_pipeline(users[d["key"]], mon, d, res, args.tool_cap_min * 60, args.tools)
            except Exception as e:
                errors.append(f"{d['key']}: {e!r}")
            per[d["key"]] = res
        t0 = time.monotonic()
        ths = [threading.Thread(target=run, args=(d,)) for d in datasets]
        for t in ths:
            t.start()
        for t in ths:
            t.join()
        tot = {"step": "ALL USERS (end to end)", "wall_s": round(time.monotonic() - t0, 1),
               "project_disk_mb": disk_mb(srv.workdir / "projects"), **mon.window(t0, time.monotonic())}
    finally:
        mon.stop()
        srv.stop()
        shutil.rmtree(srv.workdir, ignore_errors=True)
    rows = [{"scenario": "real-concurrent", "user": k, **r} for k, v in per.items() for r in v]
    rows.append({"scenario": "real-concurrent", **tot, "errors": errors})
    print(f"    ALL USERS {tot['wall_s']} s, cpu avg {tot['avg_cores']} / peak {tot['peak_cores']} cores, "
          f"peak rss {tot['peak_rss_mb']} MB, errors {errors}", flush=True)
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--setup", required=True)
    ap.add_argument("--out", default=str(Path(__file__).resolve().parent / "results-real"))
    ap.add_argument("--datasets", default="")
    ap.add_argument("--tools", default=",".join(TOOLS))
    ap.add_argument("--tool-cap-min", type=float, default=10)
    ap.add_argument("--xmx", default="6g")
    ap.add_argument("--port", type=int, default=8811)
    ap.add_argument("--concurrent", action="store_true")
    ap.add_argument("--skip-single", action="store_true")
    args = ap.parse_args()
    args.tools = [t for t in args.tools.split(",") if t]
    setup = json.loads(Path(args.setup).read_text())
    keys = [k for k in args.datasets.split(",") if k] or [d["key"] for d in setup["datasets"]]
    datasets = [d for d in setup["datasets"] if d["key"] in keys]
    Path(args.out).mkdir(parents=True, exist_ok=True)

    rows = []
    if not args.skip_single:
        for d in datasets:
            rows += one_dataset(args, setup, d)
    if args.concurrent:
        rows += concurrent(args, setup, datasets)
    f = Path(args.out) / f"real-{time.strftime('%Y%m%d-%H%M%S')}-xmx{args.xmx}.json"
    f.write_text(json.dumps({"setup": {k: v for k, v in setup.items() if k != "datasets"},
                             "datasets": datasets, "tool_cap_min": args.tool_cap_min, "rows": rows}, indent=1))
    print("\nSaved", f)


if __name__ == "__main__":
    main()
