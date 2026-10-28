#!/usr/bin/env python3
"""Presence check prototype: is an upstream commit already in Roboco?

For every upstream commit in a range, translate its added lines through the
rename map (translate-proto.py) and count how many already exist, verbatim
after stripping whitespace, in the mapped Roboco file at a target rev. Lines
shorter than 12 characters or made only of punctuation are ignored.

Measured against the 2026-10-28 audit: PORTED commits score a median of 0.93,
MISSING ones 0.08. Use >= 0.6 as "probably ported", < 0.3 as "probably missing",
and send everything in between to a human. PARTIAL commits spread across the
whole range, so a high score never proves a slice wasn't lost.

Usage:
  python presence-proto.py --repo <roboco> [--range b765f6ffb..upstream/main]
                           [--target main] [--classes audit-class.tsv] --out presence.tsv
  audit-class.tsv (optional): sha <TAB> class, to print per-class score spreads.
"""
import argparse, collections, importlib.util, os, re, subprocess

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("tp", os.path.join(here, "translate-proto.py"))
tp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tp)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--range", default="b765f6ffb..upstream/main")
    ap.add_argument("--target", default="main")
    ap.add_argument("--classes")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    repo = tp.Repo(a.repo)
    cls = {}
    if a.classes:
        for line in open(a.classes, encoding="utf-8"):
            s, c = line.rstrip("\n").split("\t")
            cls[s[:9]] = c.strip().split(":")[0].split()[0]
    log = subprocess.run(["git", "-C", a.repo, "log", "--no-merges", "-p", "--format=@@@%h %s", a.range],
                         capture_output=True, check=True).stdout.decode("utf-8", "replace")
    cache = {}

    def lines_of(path):
        if path not in cache:
            b = repo.blob(a.target, path)
            cache[path] = None if b is None else {x.strip() for x in b.decode("utf-8", "replace").splitlines()}
        return cache[path]

    rows, spread = [], collections.defaultdict(list)
    for blk in log.split("@@@")[1:]:
        head = blk.split("\n", 1)[0]
        sha, _, subject = head.partition(" ")
        tot = hit = 0
        path = None
        for ln in blk.split("\n"):
            if ln.startswith("diff --git "):
                p = ln.split(" b/", 1)[-1]
                path = None if tp.skip_reason(p) or p.startswith(".github/") else tp.map_path(p, "full")
            elif path and ln.startswith("+") and not ln.startswith("+++"):
                t = tp.translate(ln[1:].encode("utf-8"), "full").decode("utf-8").strip()
                if len(t) < 12 or re.fullmatch(r"[\W_]+", t):
                    continue
                have = lines_of(path)
                tot += 1
                hit += bool(have and t in have)
        if not tot:
            continue
        score = hit / tot
        verdict = "probably-ported" if score >= 0.6 else "probably-missing" if score < 0.3 else "check"
        rows.append((sha, f"{score:.2f}", verdict, cls.get(sha[:9], ""), subject))
        if sha[:9] in cls:
            spread[cls[sha[:9]]].append(score)
    with open(a.out, "w", encoding="utf-8") as h:
        h.write("sha\tscore\tverdict\taudit_class\tsubject\n")
        for r in rows:
            h.write("\t".join(r) + "\n")
    for c, v in sorted(spread.items()):
        v.sort()
        n = len(v)
        print(f"{c:12s} n={n:3d} median={v[n // 2]:.2f} >=0.6:{sum(x >= 0.6 for x in v):3d} <0.3:{sum(x < 0.3 for x in v):3d}")


if __name__ == "__main__":
    main()
