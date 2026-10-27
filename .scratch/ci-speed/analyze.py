"""CI timing analysis over data/runs-*.json + data/jobs/<run>.json (gh api dumps).

Usage: python -I analyze.py <data dir> [section]
"""
import json, os, sys, statistics as st
from datetime import datetime
from collections import defaultdict

D = sys.argv[1]
SECTION = sys.argv[2] if len(sys.argv) > 2 else "all"


def t(s):
    return datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ") if s else None


def mins(a, b):
    if not a or not b:
        return None
    return (t(b) - t(a)).total_seconds() / 60


def pct(xs, p):
    xs = sorted(xs)
    if not xs:
        return None
    k = (len(xs) - 1) * p
    lo = int(k)
    hi = min(lo + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def fmt(x):
    return "-" if x is None else f"{x:.1f}"


wfs = sorted(f[5:-5] for f in os.listdir(D) if f.startswith("runs-"))
runs = {}
for wf in wfs:
    runs[wf] = json.load(open(os.path.join(D, f"runs-{wf}.json")))["workflow_runs"]


def jobs_of(rid):
    p = os.path.join(D, "jobs", f"{rid}.json")
    if not os.path.exists(p):
        return []
    return json.load(open(p)).get("jobs", [])


if SECTION in ("all", "runs"):
    print("## Runs per workflow (last 30)")
    print("| workflow | runs | success | failure | cancelled | other | events | wall p50 (succ) | wall max (succ) | queue p50 |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for wf in wfs:
        rs = runs[wf]
        c = defaultdict(int)
        ev = defaultdict(int)
        walls, queues = [], []
        for r in rs:
            c[r["conclusion"] or r["status"]] += 1
            ev[r["event"]] += 1
            js = [j for j in jobs_of(r["id"]) if j.get("run_attempt", 1) == r.get("run_attempt", 1)]
            if r["conclusion"] == "success" and js:
                st_ = [j["started_at"] for j in js if j.get("started_at") and j.get("conclusion") not in ("skipped",)]
                en_ = [j["completed_at"] for j in js if j.get("completed_at") and j.get("conclusion") not in ("skipped",)]
                if st_ and en_:
                    walls.append(mins(r["run_started_at"], max(en_)))
            for j in js:
                if j.get("started_at") and j.get("created_at") and j.get("conclusion") != "skipped":
                    q = mins(j["created_at"], j["started_at"])
                    if q is not None and q >= 0:
                        queues.append(q)
        other = len(rs) - c["success"] - c["failure"] - c["cancelled"]
        evs = ",".join(f"{k}:{v}" for k, v in sorted(ev.items()))
        print(f"| {wf} | {len(rs)} | {c['success']} | {c['failure']} | {c['cancelled']} | {other} | {evs} | {fmt(pct(walls,.5))} | {fmt(max(walls) if walls else None)} | {fmt(pct(queues,.5))} |")
    print()

if SECTION in ("all", "jobs"):
    print("## Jobs (successful job runs only)")
    print("| workflow | job | n | p50 min | min | max | runner-min total (all concl.) |")
    print("|---|---|---|---|---|---|---|")
    for wf in wfs:
        per = defaultdict(list)
        tot = defaultdict(float)
        for r in runs[wf]:
            for j in jobs_of(r["id"]):
                d = mins(j.get("started_at"), j.get("completed_at"))
                if d is None or j.get("conclusion") == "skipped":
                    continue
                tot[j["name"]] += d
                if j.get("conclusion") == "success":
                    per[j["name"]].append(d)
        for name in sorted(set(per) | set(tot)):
            xs = per.get(name, [])
            print(f"| {wf} | {name} | {len(xs)} | {fmt(pct(xs,.5))} | {fmt(min(xs) if xs else None)} | {fmt(max(xs) if xs else None)} | {tot[name]:.0f} |")
    print()

if SECTION in ("all", "steps"):
    print("## Steps (successful jobs; p50/max minutes; steps >= 0.5 min p50 or >= 2 max)")
    for wf in wfs:
        per = defaultdict(lambda: defaultdict(list))
        for r in runs[wf]:
            for j in jobs_of(r["id"]):
                if j.get("conclusion") != "success":
                    continue
                for s in j.get("steps", []):
                    d = mins(s.get("started_at"), s.get("completed_at"))
                    if d is None or s.get("conclusion") == "skipped":
                        continue
                    per[j["name"]][s["name"]].append(d)
        for job, steps in per.items():
            rows = []
            for sname, xs in steps.items():
                p50 = pct(xs, .5)
                if p50 >= .5 or max(xs) >= 2:
                    rows.append((p50, sname, xs))
            if not rows:
                continue
            print(f"### {wf} / {job}")
            print("| step | n | p50 | min | max |")
            print("|---|---|---|---|---|")
            for p50, sname, xs in rows:
                print(f"| {sname} | {len(xs)} | {p50:.1f} | {min(xs):.1f} | {max(xs):.1f} |")
            print()

if SECTION in ("all", "detail"):
    wf = sys.argv[3]
    job = sys.argv[4]
    for r in runs[wf]:
        for j in jobs_of(r["id"]):
            if j["name"] != job:
                continue
            d = mins(j.get("started_at"), j.get("completed_at"))
            steps = {s["name"]: mins(s.get("started_at"), s.get("completed_at")) for s in j.get("steps", [])}
            keys = sys.argv[5].split("|") if len(sys.argv) > 5 else []
            extra = " ".join(f"{k[:18]}={fmt(steps.get(k))}" for k in keys)
            print(r["id"], r["event"], r["head_branch"][:28], r["created_at"], j.get("conclusion"), fmt(d), extra)
