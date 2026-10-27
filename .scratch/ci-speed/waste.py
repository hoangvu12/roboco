import json,sys
from datetime import datetime
from collections import defaultdict
D=sys.argv[1]
def t(s): return datetime.strptime(s,"%Y-%m-%dT%H:%M:%SZ") if s else None
print("| workflow | span | runner-min success | failure | cancelled | total | runs/day |")
print("|---|---|---|---|---|---|---|")
for wf in sys.argv[2:]:
    rs=json.load(open(f"{D}/runs-{wf}.json"))["workflow_runs"]
    by=defaultdict(float)
    for r in rs:
        for j in json.load(open(f"{D}/jobs/{r['id']}.json")).get("jobs",[]):
            if j.get("started_at") and j.get("completed_at"):
                d=(t(j["completed_at"])-t(j["started_at"])).total_seconds()/60
                if d>0: by[j.get("conclusion")]+=d
    a=min(r["created_at"] for r in rs); b=max(r["created_at"] for r in rs)
    days=max((t(b)-t(a)).total_seconds()/86400,1)
    tot=sum(by.values())
    print(f"| {wf} | {a[:10]}..{b[:10]} | {by['success']:.0f} | {by['failure']:.0f} | {by['cancelled']:.0f} | {tot:.0f} | {len(rs)/days:.1f} |")
