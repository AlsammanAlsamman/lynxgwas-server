#!/usr/bin/env python3
"""Load and sizing benchmark for LYNXgwas Server.

Drives a real ServerMain through its HTTP API (sign-up, project, upload, column mapping, loci
identification, QC, processing, page reads) while sampling the server process's CPU time and
memory from /proc. Each scenario runs in a fresh JVM so peak memory is per scenario.

Usage (from the repo root, after build.sh):
  python3 benchmark/bench.py --data DIR [--sizes 1M,5M,10M] [--xmx 8g] [--out benchmark/results]
                             [--concurrent 4] [--concurrent-size 5M] [--load-clients 10,50,100]

DIR must contain annotation.gff3 and gwas_<size>.tsv (see gen_gff3.py / gen_gwas.py).
Linux only (/proc). Standard library only.
"""
import argparse
import http.client
import json
import os
import re
import shutil
import signal
import statistics
import subprocess
import sys
import threading
import time
import urllib.parse
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CLK_TCK = os.sysconf("SC_CLK_TCK")
PAGE_KB = os.sysconf("SC_PAGE_SIZE") // 1024
PASSWORD = "Blue-Tiger-Harbor-71"
COLUMNS = {"col.chr": "CHR", "col.pos": "BP", "col.pvalue": "P", "col.rsid": "SNP", "col.ea": "A1",
           "col.nea": "A2", "col.beta": "BETA", "col.se": "SE", "col.n": "N", "col.maf": "FRQ",
           "col.varid": "", "col.or": "", "col.info": "",
           "trait.type": "binary", "effect.type": "beta", "genome.build": "GRCh37"}


# ── Process sampling ─────────────────────────────────────────────────────

class Monitor:
    """Samples CPU ticks and RSS of a pid (and its children) every `interval` seconds."""

    def __init__(self, pid, interval=0.25):
        self.pid, self.interval = pid, interval
        self.samples = []          # (t, cpu_seconds, rss_mb, threads)
        self._stop = threading.Event()
        self._t = threading.Thread(target=self._run, daemon=True)

    def start(self):
        self._t.start()
        return self

    def stop(self):
        self._stop.set()
        self._t.join()

    def _tree(self):
        pids, todo = [], [self.pid]
        while todo:
            p = todo.pop()
            pids.append(p)
            try:
                for tid in os.listdir(f"/proc/{p}/task"):
                    kids = Path(f"/proc/{p}/task/{tid}/children").read_text().split()
                    todo.extend(int(k) for k in kids)
            except OSError:
                pass
        return pids

    def _read(self):
        cpu, rss, threads = 0.0, 0.0, 0
        for p in self._tree():
            try:
                stat = Path(f"/proc/{p}/stat").read_text()
                f = stat[stat.rindex(")") + 2:].split()
                # utime+stime, plus cutime+cstime so finished child processes (R, PLINK, GCTA) still count
                cpu += (int(f[11]) + int(f[12]) + int(f[13]) + int(f[14])) / CLK_TCK
                threads += int(f[17])
                rss += int(Path(f"/proc/{p}/statm").read_text().split()[1]) * PAGE_KB / 1024
            except (OSError, ValueError, IndexError):
                pass
        return cpu, rss, threads

    def _run(self):
        while not self._stop.is_set():
            cpu, rss, th = self._read()
            self.samples.append((time.monotonic(), cpu, rss, th))
            time.sleep(self.interval)

    def window(self, t0, t1):
        """Stats for samples between monotonic times t0..t1."""
        s = [x for x in self.samples if t0 - self.interval <= x[0] <= t1 + self.interval]
        if len(s) < 2:
            cpu, rss, th = self._read()
            return {"cpu_s": 0.0, "avg_cores": 0.0, "peak_cores": 0.0, "peak_rss_mb": round(rss), "end_rss_mb": round(rss)}
        cpu_s = s[-1][1] - s[0][1]
        wall = max(s[-1][0] - s[0][0], 1e-6)
        peak_cores = 0.0
        j = 0
        for i in range(len(s)):          # max CPU over any ~1 s window
            while s[i][0] - s[j][0] > 1.0:
                j += 1
            if s[i][0] - s[j][0] >= 0.75:
                peak_cores = max(peak_cores, (s[i][1] - s[j][1]) / (s[i][0] - s[j][0]))
        return {"cpu_s": round(cpu_s, 1), "avg_cores": round(cpu_s / wall, 2), "peak_cores": round(peak_cores, 2),
                "peak_rss_mb": round(max(x[2] for x in s)), "end_rss_mb": round(s[-1][2]),
                "peak_threads": max(x[3] for x in s)}


# ── Server lifecycle ─────────────────────────────────────────────────────

class Server:
    def __init__(self, workdir, port, xmx, gff3, log):
        self.workdir, self.port, self.xmx, self.gff3, self.log = Path(workdir), port, xmx, gff3, log
        self.proc = None

    @property
    def origin(self):
        return f"http://127.0.0.1:{self.port}"

    def prepare(self):
        if self.workdir.exists():
            shutil.rmtree(self.workdir)
        self.workdir.mkdir(parents=True)
        for name in ("bin", "lib", "output", "config", "assets", "web", "tools"):
            if (REPO / name).exists():
                (self.workdir / name).symlink_to(REPO / name)
        (self.workdir / "projects").mkdir()
        (self.workdir / "server.properties").write_text(
            f"bind.host=127.0.0.1\nport={self.port}\npublic.origin={self.origin}\n"
            "secure.cookies=false\ntrust.proxy=true\ndata.dir=server-data\npublic.projects=*\n"
            f"gff3.file={self.gff3}\nref.panel.path=\nsmtp.host=\n"
            "max.projects.per.user=5\nmax.jobs.per.user=1\nmax.jobs.total=4\n")

    def start(self):
        cp = f"bin:{REPO / 'lib'}/*"
        cmd = ["java", f"-Xmx{self.xmx}", "-Djava.awt.headless=true", "-cp", cp, "ServerMain"]
        self.proc = subprocess.Popen(cmd, cwd=self.workdir, stdout=open(self.log, "ab"), stderr=subprocess.STDOUT,
                                     env={**os.environ, "LYNX_SERVER_CONFIG": "server.properties"})
        t0 = time.monotonic()
        while time.monotonic() - t0 < 60:
            try:
                c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=2)
                c.request("GET", "/api/auth/me")
                if c.getresponse().status == 200:
                    return time.monotonic() - t0
            except OSError:
                time.sleep(0.2)
        raise RuntimeError("server did not start; see " + str(self.log))

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.send_signal(signal.SIGTERM)
            try:
                self.proc.wait(20)
            except subprocess.TimeoutExpired:
                self.proc.kill()


# ── HTTP client (one simulated user, with its own client IP) ─────────────

class User:
    def __init__(self, server, email, ip):
        self.s, self.email, self.ip = server, email, ip
        self.cookie, self.csrf = "", ""

    def _req(self, method, path, body=None, headers=None, timeout=3600):
        c = http.client.HTTPConnection("127.0.0.1", self.s.port, timeout=timeout)
        h = {"X-Forwarded-For": self.ip, "Origin": self.s.origin}
        if self.cookie:
            h["Cookie"] = self.cookie
        if self.csrf:
            h["X-Lynx-CSRF"] = self.csrf
        h.update(headers or {})
        c.request(method, path, body=body, headers=h)
        r = c.getresponse()
        data = r.read()
        for k, v in r.getheaders():
            if k.lower() == "set-cookie" and "=" in v:
                pair = v.split(";", 1)[0]
                self.cookie = "" if pair.endswith("=") else pair
        c.close()
        return r.status, data

    def get(self, path):
        return self._req("GET", path)

    def post(self, path, obj):
        return self._req("POST", path, json.dumps(obj).encode(), {"Content-Type": "application/json"})

    def upload(self, path, file):
        size = os.path.getsize(file)
        with open(file, "rb") as f:
            return self._req("POST", path, f, {"Content-Type": "application/octet-stream", "Content-Length": str(size)})

    def sign_up(self, outbox):
        st, b = self.post("/api/auth/register", {"email": self.email, "password": PASSWORD})
        assert st == 200, (st, b)
        code = None
        for _ in range(100):
            for f in sorted(Path(outbox).glob("*.eml"), reverse=True) if Path(outbox).exists() else []:
                t = f.read_text()
                if t.startswith(f"To: {self.email}\n"):
                    m = re.search(r"\n\s+(\d{6})\n", t)
                    code = m and m.group(1)
                    break
            if code:
                break
            time.sleep(0.1)
        assert code, "no verification code in outbox"
        st, b = self.post("/api/auth/verify", {"email": self.email, "code": code})
        assert st == 200, (st, b)
        st, b = self.get("/api/auth/me")
        self.csrf = json.loads(b)["csrf"]

    def poll(self, path, done, every=0.5, limit=7200):
        t0 = time.monotonic()
        while time.monotonic() - t0 < limit:
            st, b = self.get(path)
            j = json.loads(b) if st == 200 else {}
            if done(j):
                return j
            time.sleep(every)
        raise TimeoutError(path)


# ── Scenario steps ───────────────────────────────────────────────────────

def timed(mon, label, fn, results, extra=None):
    t0 = time.monotonic()
    out = fn()
    t1 = time.monotonic()
    row = {"step": label, "wall_s": round(t1 - t0, 2), **mon.window(t0, t1), **(extra or {})}
    results.append(row)
    print(f"    {label:<28} {row['wall_s']:>8.2f}s  cpu {row['cpu_s']:>7.1f}s  "
          f"avg {row['avg_cores']:>5.2f} / peak {row['peak_cores']:>5.2f} cores  rss peak {row['peak_rss_mb']:>6} MB",
          flush=True)
    return out


def project_pipeline(user, mon, gwas, results, name):
    st, b = user.post("/api/my/projects", {"name": name, "description": "benchmark"})
    assert st == 200, b
    pid = json.loads(b)["id"]

    st, b = timed(mon, "upload", lambda: user.upload(f"/api/upload/{pid}/gwas", gwas), results,
                  {"file_mb": round(os.path.getsize(gwas) / 2**20)})
    assert st == 200, b
    st, b = user.post(f"/api/project/{pid}/config", {**COLUMNS, "_name": name})
    assert st == 200, b

    def loci():
        st, b = user.post("/api/loci-identify", {"project_id": pid})
        assert st == 200, b
        return user.poll(f"/api/loci-progress?project={pid}", lambda j: j.get("done"))
    lp = timed(mon, "loci-identify", loci, results)
    assert not lp.get("error"), lp
    results[-1]["loci"] = lp.get("loci_found")

    st, b = timed(mon, "qc (lambda GC)", lambda: user.get(f"/api/project/{pid}/qc"), results)
    assert st == 200, b

    def process():
        st, b = user.post("/api/projects/process", {"id": pid})
        assert st == 200, b
        return user.poll(f"/api/project/{pid}/progress", lambda j: j.get("done"))
    pr = timed(mon, "process project", process, results)
    assert not str(pr.get("phase", "")).startswith("Error"), pr

    def reads():
        st, b = user.get(f"/api/project/{pid}/manifest")
        assert st == 200, b
        idx = [int(x) for x in re.findall(rb'"index":\s*(\d+)', b)]
        assert idx, "manifest lists no loci"
        for n in idx[:10]:
            assert user.get(f"/api/project/{pid}/locus/{n}")[0] == 200
        assert user.get(f"/api/project/{pid}/genome-skyline")[0] == 200
        return idx
    loci_idx = timed(mon, "open viewer (12 reads)", reads, results)
    return pid, loci_idx


def scenario_sizes(args, sizes):
    rows = []
    for size in sizes:
        gwas = Path(args.data) / f"gwas_{size}.tsv"
        print(f"\n== Single user, {size} SNPs ({gwas.stat().st_size >> 20} MB), -Xmx{args.xmx} ==", flush=True)
        srv = Server(Path(args.out) / "workdir", args.port, args.xmx, Path(args.data) / "annotation.gff3",
                     Path(args.out) / f"server-{size}.log")
        srv.prepare()
        startup = srv.start()
        mon = Monitor(srv.proc.pid).start()
        res = []
        try:
            idle = mon.window(time.monotonic() - 1, time.monotonic())
            res.append({"step": "startup (idle)", "wall_s": round(startup, 2), **idle})
            u = User(srv, "bench1@example.org", "10.0.0.1")
            u.sign_up(srv.workdir / "server-data" / "outbox")
            project_pipeline(u, mon, gwas, res, f"bench-{size}")
            tot = mon.window(mon.samples[0][0], time.monotonic())
            res.append({"step": "WHOLE RUN", "wall_s": round(time.monotonic() - mon.samples[0][0], 1), **tot})
        finally:
            mon.stop()
            srv.stop()
        for r in res:
            r["scenario"] = f"single-{size}"
        rows += res
    return rows


def scenario_concurrent(args):
    n, size = args.concurrent, args.concurrent_size
    gwas = Path(args.data) / f"gwas_{size}.tsv"
    print(f"\n== {n} users processing {size} at the same time, -Xmx{args.xmx} ==", flush=True)
    srv = Server(Path(args.out) / "workdir", args.port, args.xmx, Path(args.data) / "annotation.gff3",
                 Path(args.out) / "server-concurrent.log")
    srv.prepare()
    srv.start()
    mon = Monitor(srv.proc.pid).start()
    per_user, errors = {}, []
    try:
        users = [User(srv, f"bench{i}@example.org", f"10.0.1.{i}") for i in range(1, n + 1)]
        for u in users:
            u.sign_up(srv.workdir / "server-data" / "outbox")

        def run(u):
            try:
                res = []
                project_pipeline(u, mon, gwas, res, f"conc-{u.ip}")
                per_user[u.email] = res
            except Exception as e:      # report, don't hide
                errors.append(f"{u.email}: {e!r}")
        t0 = time.monotonic()
        ths = [threading.Thread(target=run, args=(u,)) for u in users]
        for t in ths:
            t.start()
        for t in ths:
            t.join()
        t1 = time.monotonic()
        tot = {"scenario": f"concurrent-{n}x{size}", "step": f"ALL {n} USERS (end to end)",
               "wall_s": round(t1 - t0, 1), **mon.window(t0, t1)}
    finally:
        mon.stop()
        srv.stop()
    rows = []
    for email, res in per_user.items():
        for r in res:
            rows.append({"scenario": f"concurrent-{n}x{size}", "user": email, **r})
    rows.append(tot)
    if errors:
        print("  ERRORS:", *errors, sep="\n   ")
        rows.append({"scenario": f"concurrent-{n}x{size}", "step": "errors", "errors": errors})
    return rows


def scenario_http_load(args, clients_list, duration=20):
    """Many viewers reading a processed project (each with its own client IP, like behind a proxy)."""
    size = args.concurrent_size
    print(f"\n== Read load on a processed {size} project ==", flush=True)
    srv = Server(Path(args.out) / "workdir", args.port, args.xmx, Path(args.data) / "annotation.gff3",
                 Path(args.out) / "server-httpload.log")
    srv.prepare()
    srv.start()
    mon = Monitor(srv.proc.pid).start()
    rows = []
    try:
        owner = User(srv, "owner@example.org", "10.0.2.1")
        owner.sign_up(srv.workdir / "server-data" / "outbox")
        pid, idx = project_pipeline(owner, mon, Path(args.data) / f"gwas_{size}.tsv", [], "load")
        locus_paths = [f"/api/project/{pid}/locus/{n}" for n in idx]
        page = [f"/api/project/{pid}/manifest", f"/api/project/{pid}/genome-skyline", "/api/auth/me"]
        # Realistic viewers: open a locus page (4 requests), read it for 1-3 s, repeat.
        for nc in clients_list:
            rows.append(load_round(srv, owner, mon, size, f"{nc} viewers (1-3 s think time)", nc, duration,
                                   lambda k: page + [locus_paths[k % len(locus_paths)]], think=(1.0, 3.0)))
        # Capacity: 32 clients, no think time, a new client IP per request (as if all different people).
        rows.append(load_round(srv, owner, mon, size, "max throughput (32 clients, no pause)", 32, duration,
                               lambda k: [(page + locus_paths)[k % (len(page) + len(locus_paths))]], think=None,
                               rotate_ip=True))
    finally:
        mon.stop()
        srv.stop()
    return rows


def load_round(srv, owner, mon, size, label, nc, duration, batch_for, think, rotate_ip=False):
    import random
    lat, codes, stop, lock = [], {}, threading.Event(), threading.Lock()

    def client(i):
        rnd = random.Random(i)
        u = User(srv, owner.email, f"10.9.{i // 250}.{i % 250 + 1}")
        u.cookie, u.csrf = owner.cookie, owner.csrf
        k = i
        while not stop.is_set():
            for p in batch_for(k):
                if rotate_ip:
                    u.ip = f"10.{k % 250}.{(k // 250) % 250}.{i + 1}"
                t = time.monotonic()
                try:
                    st, _ = u.get(p)
                except OSError:
                    st = "conn-error"
                with lock:
                    lat.append(time.monotonic() - t)
                    codes[st] = codes.get(st, 0) + 1
                k += 1
            if think:
                stop.wait(rnd.uniform(*think))
    ths = [threading.Thread(target=client, args=(i,), daemon=True) for i in range(nc)]
    t0 = time.monotonic()
    for t in ths:
        t.start()
    time.sleep(duration)
    stop.set()
    for t in ths:
        t.join(30)
    t1 = time.monotonic()
    lat.sort()
    q = lambda f: round(lat[min(len(lat) - 1, int(f * len(lat)))] * 1000, 1) if lat else None
    row = {"scenario": f"http-load-{size}", "step": label, "wall_s": round(t1 - t0, 1),
           "requests": len(lat), "req_per_s": round(len(lat) / (t1 - t0)), "p50_ms": q(.5), "p95_ms": q(.95),
           "p99_ms": q(.99), "status_codes": {str(k): v for k, v in codes.items()}, **mon.window(t0, t1)}
    print(f"    {label:<40} {row['req_per_s']:>6} req/s  p50 {row['p50_ms']} ms  p95 {row['p95_ms']} ms  "
          f"p99 {row['p99_ms']} ms  cpu avg {row['avg_cores']} cores  rss {row['peak_rss_mb']} MB  {row['status_codes']}",
          flush=True)
    return row


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", default=str(REPO / "benchmark" / "results"))
    ap.add_argument("--sizes", default="1M,5M,10M")
    ap.add_argument("--xmx", default="8g")
    ap.add_argument("--port", type=int, default=8799)
    ap.add_argument("--concurrent", type=int, default=4)
    ap.add_argument("--concurrent-size", default="5M")
    ap.add_argument("--load-clients", default="10,50,100")
    ap.add_argument("--skip", default="", help="comma list of: sizes,concurrent,load")
    args = ap.parse_args()
    Path(args.out).mkdir(parents=True, exist_ok=True)
    skip = set(filter(None, args.skip.split(",")))

    rows = []
    machine = {"cpus": os.cpu_count(), "mem_total_gb": round(os.sysconf("SC_PHYS_PAGES") * os.sysconf("SC_PAGE_SIZE") / 2**30, 1),
               "java": subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()[0],
               "xmx": args.xmx}
    print("Machine:", machine)
    if "sizes" not in skip:
        rows += scenario_sizes(args, [s for s in args.sizes.split(",") if s])
    if "concurrent" not in skip and args.concurrent > 0:
        rows += scenario_concurrent(args)
    if "load" not in skip:
        rows += scenario_http_load(args, [int(x) for x in args.load_clients.split(",") if x])

    out = Path(args.out) / f"results-{time.strftime('%Y%m%d-%H%M%S')}-xmx{args.xmx}.json"
    out.write_text(json.dumps({"machine": machine, "rows": rows}, indent=1))
    shutil.rmtree(Path(args.out) / "workdir", ignore_errors=True)
    print("\nSaved", out)


if __name__ == "__main__":
    sys.exit(main())
