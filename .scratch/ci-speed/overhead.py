"""Fixed-overhead steps per job (successful jobs): setup/toolchain/cache/SDK steps.

Usage: python -I overhead.py <data dir>
Prints p50/max minutes for steps whose name matches the overhead patterns,
grouped by workflow/job. Complements analyze.py (which hides < 0.5 min steps).
"""
import json, os, sys
from datetime import datetime
from collections import defaultdict

D = sys.argv[1]
PATS = ["checkout", "toolchain", "rust-cache", "Swatinem", "Post ", "nextest", "cargo-ndk",
        "setup-java", "setup-go", "setup-gradle", "Gradle wrapper", "SDK", "KVM", "Set up job",
        "Complete job", "GPUI system", "apt", "fxc", "Android Rust", "Android targets", "avd", "cache@"]


def t(s):
    return datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ") if s else None


def pct(xs, p):
    xs = sorted(xs)
    k = (len(xs) - 1) * p
    lo = int(k)
    hi = min(lo + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


per = defaultdict(lambda: defaultdict(list))
tot = defaultdict(list)
for f in os.listdir(D):
    if not f.startswith("runs-"):
        continue
    wf = f[5:-5]
    for r in json.load(open(os.path.join(D, f)))["workflow_runs"]:
        p = os.path.join(D, "jobs", f"{r['id']}.json")
        if not os.path.exists(p):
            continue
        for j in json.load(open(p)).get("jobs", []):
            if j.get("conclusion") != "success":
                continue
            s_sum = 0.0
            for s in j.get("steps", []):
                if not s.get("started_at") or not s.get("completed_at") or s.get("conclusion") == "skipped":
                    continue
                if not any(x.lower() in s["name"].lower() for x in PATS):
                    continue
                d = (t(s["completed_at"]) - t(s["started_at"])).total_seconds() / 60
                per[(wf, j["name"])][s["name"]].append(d)
                s_sum += d
            tot[(wf, j["name"])].append(s_sum)

for key in sorted(per):
    xs = tot[key]
    print(f"### {key[0]} / {key[1]}  (overhead sum p50 {pct(xs, .5):.2f}, max {max(xs):.2f}, n={len(xs)})")
    for sname, ys in sorted(per[key].items(), key=lambda kv: -pct(kv[1], .5)):
        print(f"  {pct(ys, .5):5.2f} p50  {max(ys):5.2f} max  n={len(ys):2d}  {sname}")
    print()
