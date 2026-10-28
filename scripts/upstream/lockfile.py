"""Cargo.lock: regenerate, never merge.

translate.py keeps Roboco's Cargo.lock as it is and turns upstream's lockfile
change into a plan: the registry packages upstream moved to another version
(`cargo update -p name@old --precise new` where Roboco is still on upstream's
old version), then one `cargo metadata` so cargo settles the lock against the
merged manifests (new or removed dependencies, renamed crates). `--refresh-lock`
runs the plan in the worktree; otherwise the porter does. CI builds Android
with `--locked`, so a stale lock fails there.

Git-sourced packages (the GPUI forks) are listed but never updated here:
Roboco pins its own forks by rev (AGENTS.md, "GPUI forks").
"""
import re
import subprocess

import upstream_common as uc

NAMES = uc.LOCKFILES

_FIELD = re.compile(r'^(name|version|source) = "([^"]*)"\s*$', re.M)


def is_lockfile(path):
    return path.rsplit("/", 1)[-1] in NAMES


def packages(data):
    """{(name, version): source} of a Cargo.lock (stdlib only: no tomllib on 3.9/3.10)."""
    out = {}
    if not data:
        return out
    text = data.decode("utf-8", "replace").replace("\r\n", "\n")
    for block in text.split("[[package]]")[1:]:
        fields = dict(_FIELD.findall(block.split("\n[", 1)[0]))
        if "name" in fields and "version" in fields:
            out[(fields["name"], fields["version"])] = fields.get("source", "")
    return out


def _by_name(pkgs):
    names = {}
    for (name, version), source in pkgs.items():
        names.setdefault(name, []).append((version, source))
    return names


def plan(ours, base, theirs):
    """What upstream's lockfile change means for Roboco's lock.

    Returns {"updates": [(name, old, new)], "added": [...], "removed": [...],
    "git": [...], "already": [...]} where updates are registry bumps Roboco can
    replay (it still has upstream's old version and lacks the new one).
    """
    o, b, t = packages(ours), packages(base), packages(theirs)
    ob, bb, tb = _by_name(o), _by_name(b), _by_name(t)
    res = {"updates": [], "added": [], "removed": [], "git": [], "already": []}
    for name in sorted(set(bb) | set(tb)):
        if set(bb.get(name, [])) == set(tb.get(name, [])):
            continue
        old = {v for v, _ in bb.get(name, [])}
        new = {v for v, _ in tb.get(name, [])}
        sources = {s for _, s in bb.get(name, []) + tb.get(name, [])}
        if sources == {""}:
            continue  # workspace / path package: follows the manifests
        if any(s.startswith("git+") for s in sources):
            res["git"].append(name)
            continue
        if old == new:
            continue  # same versions from another registry: cargo metadata settles it
        if not old:
            res["added"].append(name)
            continue
        if not new:
            res["removed"].append(name)
            continue
        mine = {v for v, _ in ob.get(name, [])}
        gone, came = sorted(old - new), sorted(new - old)
        if came and set(came) <= mine:
            res["already"].append(name)
            continue
        if len(gone) == 1 and len(came) == 1 and gone[0] in mine:
            res["updates"].append((name, gone[0], came[0]))
        else:
            res["added" if came else "removed"].append(name)
    return res


def commands(p):
    """The cargo commands that replay a plan, settle step first."""
    cmds = [["cargo", "metadata", "--format-version", "1"]]
    for name, old, new in p["updates"]:
        cmds.append(["cargo", "update", "-p", f"{name}@{old}", "--precise", new])
    return cmds


def describe(p):
    """Human lines for a plan."""
    out = []
    if p["updates"]:
        out.append("upstream bumped: " + ", ".join(f"{n} {a} -> {b}" for n, a, b in p["updates"]))
    if p["added"]:
        out.append("added/changed by upstream (cargo metadata resolves them from the manifests): "
                   + ", ".join(p["added"]))
    if p["removed"]:
        out.append("dropped by upstream: " + ", ".join(p["removed"]))
    if p["already"]:
        out.append("already at upstream's version in Roboco: " + ", ".join(p["already"]))
    if p["git"]:
        out.append("git pins upstream moved (Roboco pins its own forks; see AGENTS.md): " + ", ".join(p["git"]))
    return out


def refresh(top, p, timeout=600):
    """Run the plan in a worktree. Returns [(command, ok, last stderr line)]."""
    results = []
    for cmd in commands(p):
        try:
            r = subprocess.run(cmd, cwd=top, capture_output=True, timeout=timeout)
            ok = r.returncode == 0
            err = r.stderr.decode("utf-8", "replace").strip().splitlines()
            results.append((" ".join(cmd), ok, "" if ok or not err else err[-1]))
        except (OSError, subprocess.TimeoutExpired) as e:
            results.append((" ".join(cmd), False, str(e)))
    return results
