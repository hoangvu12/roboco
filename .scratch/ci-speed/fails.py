import json,os,sys
from datetime import datetime
D=sys.argv[1]
def t(s): return datetime.strptime(s,"%Y-%m-%dT%H:%M:%SZ") if s else None
for wf in sys.argv[2:]:
    print("==",wf)
    for r in json.load(open(f"{D}/runs-{wf}.json"))["workflow_runs"]:
        if r["conclusion"]=="success": continue
        js=json.load(open(f"{D}/jobs/{r['id']}.json")).get("jobs",[])
        out=[]
        for j in js:
            if j.get("conclusion") in ("failure","cancelled","timed_out"):
                d=(t(j["completed_at"])-t(j["started_at"])).total_seconds()/60 if j.get("started_at") and j.get("completed_at") else -1
                bad=[s["name"] for s in j.get("steps",[]) if s.get("conclusion") in ("failure","cancelled")]
                out.append(f"{j['name'][:30]}[{j['conclusion']} {d:.0f}m a{j.get('run_attempt')}] {bad[:2]}")
        print(r["id"],r["event"],r["head_branch"][:25],r["created_at"][:16],r["conclusion"],"|"," ; ".join(out))
