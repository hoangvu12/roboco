#!/usr/bin/env python3
"""Is an upstream change already in Roboco?

Translates each upstream commit's added lines through rename-map.tsv and
counts how many already exist (whitespace-stripped) in the mapped Roboco
file at --target. Lines under 12 characters or made only of punctuation are
ignored, as are files path-rules.tsv excludes and .github/.

Verdicts: >= 0.6 probably-ported, < 0.3 probably-missing, otherwise check.
On the wave-10 audit, PORTED commits scored a median 0.93 and MISSING 0.08,
and no missing commit scored >= 0.6. PARTIAL commits spread over the whole
range: a high score never proves a slice wasn't lost. The ledger, not the
score, is the record.

Read-only. Examples:
  python scripts/upstream/presence.py 85006f4b4 970f41fa7
  python scripts/upstream/presence.py --range 31d26eeb6..zeron/main --target main
  python scripts/upstream/presence.py --range b765f6ffb..31d26eeb6 --by-ledger --out presence.tsv
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import upstream_common as uc  # noqa: E402


def main(argv=None):
    uc.utf8_stdio()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("shas", nargs="*", help="upstream commits to score")
    ap.add_argument("--range", help="score every non-merge commit in a range (e.g. 31d26eeb6..zeron/main)")
    ap.add_argument("--repo", default=".", help="repository (default: cwd)")
    ap.add_argument("--target", help="Roboco rev to look in (default: main, else origin/main)")
    ap.add_argument("--out", help="also write a TSV here")
    ap.add_argument("--by-ledger", action="store_true",
                    help="print score spreads per ledger status (calibration against recorded decisions)")
    ap.add_argument("--ledger", help="ledger path (default: docs/reference/upstream-ledger.tsv in --repo)")
    args = ap.parse_args(argv)
    if not args.shas and not args.range:
        ap.error("give upstream commits or --range")
    repo = uc.Repo(args.repo)
    args.target = args.target or repo.default_target()
    if not repo.rev(args.target):
        sys.exit(f"unknown target {args.target}")
    log_args = [args.range] if args.range else ["--no-walk=unsorted", *args.shas]
    rows = uc.presence_scores(repo, log_args, args.target)
    ledger = None
    if args.by_ledger:
        path = args.ledger or uc.ledger_path(repo.toplevel())
        ledger = uc.Ledger.load(path)
    lines = []
    for sha, subject, score, hit, tot in rows:
        verdict = uc.presence_verdict(score)
        status = ""
        if ledger:
            row = ledger.find(sha)
            status = row["status"] if row else "(not in ledger)"
        s = "-" if score is None else f"{score:.2f}"
        lines.append((sha, s, f"{hit}/{tot}", verdict, status, subject))
        print(f"{sha[:9]}  {s:>4}  {hit:>4}/{tot:<4} {verdict:<16} {status[:24]:<24} {subject[:70]}")
    if args.out:
        with open(args.out, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("sha\tscore\tlines\tverdict\tledger_status\tsubject\n")
            for ln in lines:
                fh.write("\t".join(uc.clean_cell(c) for c in ln) + "\n")
    if ledger:
        spread = {}
        for sha, subject, score, _, _ in rows:
            row = ledger.find(sha)
            if row and score is not None:
                spread.setdefault(uc.status_kind(row["status"]).split(":")[0], []).append(score)
        print()
        for k, v in sorted(spread.items()):
            v.sort()
            print(f"{k:16s} n={len(v):3d} median={v[len(v) // 2]:.2f} "
                  f">=0.6:{sum(x >= uc.PRESENCE_PORTED for x in v):3d} "
                  f"<0.3:{sum(x < uc.PRESENCE_MISSING for x in v):3d}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
