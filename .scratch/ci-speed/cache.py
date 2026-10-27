import json,sys,statistics as st
from datetime import datetime
from collections import defaultdict
D=sys.argv[1]
def t(s): return datetime.strptime(s,"%Y-%m-%dT%H:%M:%SZ") if s else None
def m(a,b): return (t(b)-t(a)).total_seconds()/60 if a and b else None
print("| workflow / job | runs w/ restore | restore<=0.15m (miss-ish) | job p50 hit (succ) | job p50 miss (succ) | save step p50 (when >0) |")
print("|---|---|---|---|---|---|")
for wf in sys.argv[2:]:
    rs=json.load(open(f"{D}/runs-{wf}.json"))["workflow_runs"]
    agg=defaultdict(lambda: {"n":0,"miss":0,"hit":[],"missd":[],"save":[]})
    for r in rs:
        for j in json.load(open(f"{D}/jobs/{r['id']}.json")).get("jobs",[]):
            steps=j.get("steps",[])
            rest=[s for s in steps if s["name"].startswith("Run Swatinem/rust-cache")]
            post=[s for s in steps if s["name"].startswith("Post Run Swatinem/rust-cache")]
            if not rest or rest[0].get("conclusion")!="success": continue
            d=m(rest[0]["started_at"],rest[0]["completed_at"])
            a=agg[j["name"][:40]]; a["n"]+=1
            miss = d<=0.15
            a["miss"]+=miss
            jd=m(j["started_at"],j["completed_at"])
            if j.get("conclusion")=="success":
                (a["missd"] if miss else a["hit"]).append(jd)
            if post and post[0].get("started_at"):
                pd=m(post[0]["started_at"],post[0]["completed_at"])
                if pd and pd>0.05: a["save"].append(pd)
    for k,a in agg.items():
        f=lambda xs: f"{st.median(xs):.1f} (n={len(xs)})" if xs else "-"
        print(f"| {wf} / {k} | {a['n']} | {a['miss']} | {f(a['hit'])} | {f(a['missd'])} | {f(a['save'])} |")
