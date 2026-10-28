#!/usr/bin/env python3
"""Weekly upstream triage against the ledger (docs/reference/upstream-ledger.tsv).

Subcommands (anything that writes the ledger is a dry run without --write):

  scan         List upstream commits not yet decided: everything in
               b765f6ffb..zeron/main missing from the ledger, plus rows still
               `new`, `later` or `android-lane`. Routes each by path-rules.tsv
               (auto-skip / Android lane / review), scores presence, runs the
               translated cherry-pick, and prints a markdown triage page
               grouped by upstream PR. --out writes the page to a file;
               --write records the new commits in the ledger (auto-skips as
               `skipped:<reason>`, iOS-only as `android-lane`, the rest `new`).
  apply PAGE   Read the decisions marked on a triage page and update the
               ledger: take -> `queued <batch>`, skip[:reason] ->
               `skipped:<reason>`, decline -> `declined`, later -> `later`,
               `ported [sha]`, `superseded`, `android-lane`, `missing`.
  sync         Flip rows to `ported <roboco sha>` for every Roboco commit on
               --rev carrying `Upstream-Commit: <sha>` or `(cherry picked from
               commit <sha>)`, and list `queued` rows not yet found.
  mark SHA STATUS [--note TEXT]   Set one row by hand.
  check-rules  Replay path-rules.tsv over every ledger row and report how the
               auto-skip and Android lanes agree with the recorded statuses.
  stats        Rows by status (commit ledger and PR ledger).

Examples:
  python scripts/upstream/triage.py scan --out .scratch/upstream-triage/2026-11-04.md
  python scripts/upstream/triage.py apply .scratch/upstream-triage/2026-11-04.md --write
  python scripts/upstream/triage.py sync --rev origin/main --write
"""
import argparse
import datetime
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import upstream_common as uc  # noqa: E402

DECISION_HELP = ("`take`, `skip:<reason>`, `decline`, `later`, `ported [roboco sha]`, "
                 "`superseded`, or leave empty to decide next week")


# --- page rendering and parsing (pure; covered by tests) -------------------------------------


def md_cell(s):
    return (s or "").replace("|", "\\|").replace("\n", " ").strip()


def split_row(line):
    cells = re.split(r"(?<!\\)\|", line.strip().strip("|"))
    return [c.strip().replace("\\|", "|") for c in cells]


def render_page(meta, groups, lane, skipped):
    """meta: dict(date, batch, range, mirror, target, counts...)
    groups: [(heading, [item])]; lane / skipped: [item]
    item: dict(sha, subject, translate, presence, details)"""
    out = [f"# Upstream triage {meta['date']}: `{meta['range']}`", "",
           f"<!-- triage-batch: {meta['batch']} -->",
           f"Mirror `{uc.MIRROR}` = `{meta['mirror']}`; Roboco target `{meta['target']}`. "
           f"{meta['new']} commit(s) to look at: {meta['review']} to decide, "
           f"{len(lane)} Android lane, {len(skipped)} auto-skipped.", "",
           f"Fill each **decision** cell: {DECISION_HELP}. The note cell goes into the ledger. "
           f"Then: `python scripts/upstream/triage.py apply <this file> --write`.", "",
           "translate: CLEAN / TRIVIAL / REAL from `translate.py` (x = conflicted files, "
           "m = files with no Roboco target). presence: share of the added lines already in Roboco.", ""]
    head = "| decision | sha | subject | translate | presence | details | note |"
    sep = "|---|---|---|---|---|---|---|"
    if groups:
        out.append("## To decide")
        out.append("")
    for heading, items in groups:
        out += [f"### {heading}", "", head, sep]
        out += [_row(it) for it in items]
        out.append("")
    if lane:
        out += ["## Android lane (iOS-only: does the iPhone behaviour belong in `apps/android`?)", "",
                head, sep]
        out += [_row(it) for it in lane]
        out.append("")
    if skipped:
        out += ["## Auto-skipped (recorded by `scan --write`; no decision needed)", "",
                "| sha | subject | reason |", "|---|---|---|"]
        out += [f"| `{it['sha'][:9]}` | {md_cell(it['subject'])} | {md_cell(it['details'])} |"
                for it in skipped]
        out.append("")
    return "\n".join(out)


def _row(it):
    return (f"| {md_cell(it.get('decision', ''))} | `{it['sha'][:9]}` | {md_cell(it['subject'])} | "
            f"{md_cell(it['translate'])} | {md_cell(it['presence'])} | {md_cell(it['details'])} | "
            f"{md_cell(it.get('note', ''))} |")


def parse_page(text):
    """(batch, [(sha_prefix, decision, note)]) from a marked triage page."""
    m = re.search(r"<!--\s*triage-batch:\s*([A-Za-z0-9._-]+)\s*-->", text)
    batch = m.group(1) if m else None
    rows = []
    for line in text.splitlines():
        if not line.lstrip().startswith("|"):
            continue
        cells = split_row(line)
        if len(cells) < 7:
            continue
        sha = cells[1].strip("` ")
        if not re.fullmatch(r"[0-9a-f]{7,40}", sha):
            continue
        rows.append((sha, cells[0].strip(), cells[6].strip()))
    return batch, rows


def decision_status(decision, batch):
    """Ledger status for a page decision; None for an empty cell; ValueError if unknown."""
    d = decision.strip().strip("`").strip()
    low = d.lower()
    if not d:
        return None
    if low == "take":
        return f"queued {batch}"
    if low in ("skip", "skipped"):
        return "skipped:user"
    if low.startswith(("skip:", "skipped:")):
        reason = re.sub(r"[^a-z0-9/._-]+", "-", low.split(":", 1)[1].strip()).strip("-")
        return "skipped:" + (reason or "user")
    if low in ("decline", "declined"):
        return "declined"
    if low in ("later", "superseded", "android-lane", "missing", "partial"):
        return low
    m = re.fullmatch(r"ported(?:\s+([0-9a-f]{7,40}))?", low)
    if m:
        return f"ported {m.group(1)[:9]}" if m.group(1) else "ported"
    if uc.valid_status(d):
        return d
    raise ValueError(f"unknown decision {decision!r} (use {DECISION_HELP})")


# --- scan ------------------------------------------------------------------------------------


def translate_cell(res):
    cls = res.classify()
    bits = []
    if res.count("conflict"):
        bits.append(f"{res.count('conflict')}x/{res.regions}r")
    if res.count("missing"):
        bits.append(f"{res.count('missing')}m")
    return cls + (" " + " ".join(bits) if bits else "")


def details_cell(res, route_note):
    parts = []
    conf = [f"{f.path} ({f.regions}r)" for f in res.files if f.state == "conflict"]
    miss = [f.path for f in res.files if f.state == "missing"]
    skipped = [f for f in res.files if f.state == "skipped"]
    live = len(res.files) - len(skipped)
    parts.append(f"{live} file(s)" + (f" +{len(skipped)} skipped" if skipped else ""))
    if conf:
        parts.append("conflicts: " + ", ".join(conf[:3]) + (" …" if len(conf) > 3 else ""))
    if miss:
        parts.append("no target: " + ", ".join(miss[:2]) + (" …" if len(miss) > 2 else ""))
    if route_note:
        parts.append(route_note)
    return "; ".join(parts)


def load_pr_ledger(root):
    path = os.path.join(root, uc.PR_LEDGER)
    out = {}
    if os.path.exists(path):
        for row in uc.read_table(path)[1:]:
            if len(row) >= 5:
                out[row[0]] = row[4]
    return out


def cmd_scan(args, repo, root, ledger):
    rng = args.range or f"{uc.FORK_POINT}..{uc.MIRROR}"
    target = args.target or repo.default_target()
    if not repo.rev(target):
        sys.exit(f"unknown target {target}")
    commits = repo.commits(rng)
    todo = []
    for sha, date, subject in commits:
        row = ledger.find(sha)
        if row is None or row["status"] in uc.OPEN_STATUSES:
            todo.append((sha, date, subject, row))
    prs = uc.upstream_prs(repo, rng)
    pr_ledger = load_pr_ledger(root)
    renames, rules = uc.RenameMap(), uc.PathRules()
    presence = {}
    if todo and not args.no_presence:
        shas = ["--no-walk=unsorted"] + [t[0] for t in todo]
        for sha, _, score, _, _ in uc.presence_scores(repo, shas, target, renames, rules):
            presence[sha] = score
    groups, order, lane, skipped, additions = {}, [], [], [], []
    for sha, date, subject, row in todo:
        paths = []
        for st, src, dst, _ in repo.changes(sha):
            paths.append(dst)
            if src != dst:
                paths.append(src)
        lane_name, why = rules.route(subject, paths)
        score = presence.get(sha)
        item = dict(sha=sha, subject=subject, presence="-" if score is None else
                    f"{score:.2f} {uc.presence_verdict(score)}", translate="", details="")
        if row and row.get("note"):
            item["note"] = row["note"]
        pr = prs.get(sha)
        if lane_name == "auto-skip":
            item["details"] = why
            skipped.append(item)
            additions.append((sha, date, pr, subject, f"skipped:{why.split(',')[0]}", f"auto: {why}"))
            continue
        res = None
        if not args.no_translate and lane_name == "review":
            res = uc.replay(repo, sha, target, renames, rules)
            item["translate"] = translate_cell(res)
            item["details"] = details_cell(res, why)
        else:
            item["translate"] = "-"
            item["details"] = why
        if lane_name == "android-lane":
            lane.append(item)
            additions.append((sha, date, pr, subject, "android-lane", ""))
            continue
        key = pr if pr else 0
        if key not in groups:
            groups[key] = []
            order.append(key)
        groups[key].append(item)
        note = f"triage: {item['translate']}, presence {item['presence']}"
        additions.append((sha, date, pr, subject, "new", note))
    titled = []
    for key in order:
        items = groups[key]
        if key:
            prev = pr_ledger.get(f"#{key}")
            heading = (f"zeron#{key}: {items[0]['subject']} "
                       f"([PR](https://github.com/zeronsh/zeron/pull/{key}))"
                       + (f" — PR ledger: `{prev}`" if prev else ""))
        else:
            heading = "No upstream PR (pushed to main)"
        titled.append((heading, items))
    meta = dict(date=args.date, batch=args.date, range=rng,
                mirror=repo.git("rev-parse", "--short=9", uc.MIRROR).strip() if repo.rev(uc.MIRROR) else "?",
                target=f"{target} @ {repo.git('rev-parse', '--short=9', target).strip()}",
                new=len(todo), review=sum(len(i) for _, i in titled))
    page = render_page(meta, titled, lane, skipped)
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(page + "\n")
        print(f"wrote {args.out}: {meta['review']} to decide, {len(lane)} Android lane, "
              f"{len(skipped)} auto-skipped")
    else:
        print(page)
    changed = 0
    for sha, date, pr, subject, status, note in additions:
        if ledger.find(sha) is not None:
            continue
        ledger.add(dict(upstream_sha=sha, date=date, pr=f"#{pr}" if pr else "", subject=subject,
                        status=status, audit=f"triage {args.date}", note=note))
        changed += 1
    finish(args, ledger, f"{changed} new ledger row(s)", out=sys.stderr if not args.out else sys.stdout)


# --- apply / sync / mark -------------------------------------------------------------------------


def cmd_apply(args, repo, root, ledger):
    with open(args.page, encoding="utf-8") as fh:
        batch, rows = parse_page(fh.read())
    batch = args.batch or batch or args.date
    changes, errors = 0, []
    for sha, decision, note in rows:
        try:
            status = decision_status(decision, batch)
        except ValueError as e:
            errors.append(f"{sha}: {e}")
            continue
        if status is None:
            continue
        row = ledger.find(sha)
        if row is None:
            full = repo.rev(sha)
            if not full:
                errors.append(f"{sha}: not a commit in this repository")
                continue
            info = repo.git("log", "-1", "--format=%ad%x09%s", "--date=short", full).strip().split("\t", 1)
            row = ledger.add(dict(upstream_sha=full, date=info[0], pr="", subject=info[1],
                                  status="new", audit=f"triage {batch}", note=""))
        if row["status"] == status and (not note or row["note"] == note):
            continue
        print(f"{row['upstream_sha'][:9]}  {row['status']} -> {status}" + (f"  ({note})" if note else ""))
        ledger.set_status(row["upstream_sha"], status, note if note else None)
        changes += 1
    for e in errors:
        print("error: " + e, file=sys.stderr)
    if errors:
        sys.exit(1)
    finish(args, ledger, f"{changes} row(s) changed")


def cmd_sync(args, repo, root, ledger):
    args.rev = args.rev or repo.default_target()
    rng = f"{uc.FORK_POINT}..{args.rev}"
    ports = uc.roboco_ports(repo, rng)
    changes = 0
    found = set()
    for up, roboco in ports.items():
        try:
            row = ledger.find(up)
        except uc.LedgerError:
            row = None
        if row is None:
            continue
        found.add(row["upstream_sha"])
        if row["status"].startswith("ported"):
            continue
        status = f"ported {roboco[:9]}"
        print(f"{row['upstream_sha'][:9]}  {row['status']} -> {status}")
        ledger.set_status(row["upstream_sha"], status)
        changes += 1
    waiting = [r for r in ledger.rows if r["status"].startswith("queued") and r["upstream_sha"] not in found]
    for r in waiting:
        print(f"{r['upstream_sha'][:9]}  still {r['status']}: no Roboco commit on {args.rev} cites it")
    finish(args, ledger, f"{changes} row(s) flipped to ported")


def cmd_mark(args, repo, root, ledger):
    row, old = ledger.set_status(args.sha, args.status, args.note)
    print(f"{row['upstream_sha'][:9]}  {old} -> {row['status']}")
    finish(args, ledger, "1 row changed" if old != row["status"] or args.note else "no change")


def finish(args, ledger, what, out=sys.stdout):
    if getattr(args, "write", False):
        ledger.save(args.ledger_path)
        print(f"{what}; wrote {args.ledger_path}", file=out)
    else:
        print(f"{what} (dry run: pass --write to update {os.path.relpath(args.ledger_path)})", file=out)


# --- check-rules / stats -------------------------------------------------------------------------


def cmd_check_rules(args, repo, root, ledger):
    rules = uc.PathRules()
    lanes = {"auto-skip": {}, "android-lane": {}, "review": {}}
    wrong = []
    for row in ledger.rows:
        sha = row["upstream_sha"]
        paths = []
        for st, src, dst, _ in repo.changes(sha):
            paths += [dst] + ([src] if src != dst else [])
        lane, why = rules.route(row["subject"], paths)
        kind = uc.status_kind(row["status"]).split(":")[0]
        lanes[lane][kind] = lanes[lane].get(kind, 0) + 1
        if lane == "auto-skip" and not row["status"].startswith("skipped:"):
            wrong.append((sha, why, row["status"], row["subject"]))
    for lane, kinds in lanes.items():
        n = sum(kinds.values())
        print(f"{lane:13s} {n:4d}  " + ", ".join(f"{k} {v}" for k, v in sorted(kinds.items(), key=lambda x: -x[1])))
    for sha, why, status, subject in wrong:
        print(f"  auto-skip ({why}) but ledger says {status}: {sha[:9]} {subject[:60]}")
    print(f"auto-skips that disagree with the ledger: {len(wrong)}")
    return 1 if wrong else 0


def cmd_stats(args, repo, root, ledger):
    counts = ledger.counts()
    print(f"{args.ledger_path}: {len(ledger.rows)} rows")
    for k, v in sorted(counts.items(), key=lambda x: (-x[1], x[0])):
        print(f"  {v:4d}  {k}")
    pr = load_pr_ledger(root)
    if pr:
        c = {}
        for st in pr.values():
            k = uc.status_kind(st)
            c[k] = c.get(k, 0) + 1
        print(f"{uc.PR_LEDGER}: {len(pr)} PRs")
        for k, v in sorted(c.items(), key=lambda x: (-x[1], x[0])):
            print(f"  {v:4d}  {k}")


def main(argv=None):
    uc.utf8_stdio()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo", default=".", help="Roboco checkout (default: cwd)")
    ap.add_argument("--ledger", help="ledger path (default: docs/reference/upstream-ledger.tsv in --repo)")
    ap.add_argument("--date", default=datetime.date.today().isoformat(),
                    help="triage date, also the default batch id (default: today)")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("scan", help="write the triage page for undecided upstream commits")
    p.add_argument("--range", help=f"upstream range (default: {uc.FORK_POINT}..{uc.MIRROR})")
    p.add_argument("--target", help="Roboco rev to translate onto (default: main, else origin/main)")
    p.add_argument("--out", help="write the page here instead of stdout")
    p.add_argument("--no-translate", action="store_true", help="skip the translated cherry-pick")
    p.add_argument("--no-presence", action="store_true", help="skip the presence check")
    p.add_argument("--write", action="store_true", help="record the new commits in the ledger")

    p = sub.add_parser("apply", help="apply the decisions marked on a triage page")
    p.add_argument("page")
    p.add_argument("--batch", help="batch id for `take` (default: the page's, else --date)")
    p.add_argument("--write", action="store_true", help="update the ledger")

    p = sub.add_parser("sync", help="flip rows to ported from Upstream-Commit trailers")
    p.add_argument("--rev", help="Roboco rev to read (default: main, else origin/main)")
    p.add_argument("--write", action="store_true", help="update the ledger")

    p = sub.add_parser("mark", help="set one row's status")
    p.add_argument("sha")
    p.add_argument("status")
    p.add_argument("--note")
    p.add_argument("--write", action="store_true", help="update the ledger")

    sub.add_parser("check-rules", help="replay path-rules.tsv over the ledger")
    sub.add_parser("stats", help="rows by status")
    args = ap.parse_args(argv)

    repo = uc.Repo(args.repo)
    root = repo.toplevel()
    args.ledger_path = args.ledger or uc.ledger_path(root)
    ledger = uc.Ledger.load(args.ledger_path)
    return {"scan": cmd_scan, "apply": cmd_apply, "sync": cmd_sync, "mark": cmd_mark,
            "check-rules": cmd_check_rules, "stats": cmd_stats}[args.cmd](args, repo, root, ledger) or 0


if __name__ == "__main__":
    sys.exit(main())
