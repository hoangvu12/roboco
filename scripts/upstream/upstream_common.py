"""Shared pieces of the upstream sync tools (translate.py, presence.py, triage.py).

Python 3.9+, stdlib only, Windows and Linux. Nothing here writes to a
repository; the scripts that write say so and default to a dry run.

- RenameMap / PathRules: the two data tables next to this file.
- Repo: read-only git plumbing (blobs through one `git cat-file --batch`).
- replay(): the rename-aware 3-way merge of one upstream commit onto a rev.
- Ledger: docs/reference/upstream-ledger.tsv (format in upstream-ports.md).
"""
import fnmatch
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(HERE))
RENAME_MAP = os.path.join(HERE, "rename-map.tsv")
PATH_RULES = os.path.join(HERE, "path-rules.tsv")
LEDGER = os.path.join("docs", "reference", "upstream-ledger.tsv")
PR_LEDGER = os.path.join("docs", "reference", "upstream-prs.tsv")
MIRROR = "zeron/main"
FORK_POINT = "b765f6ffb"  # last upstream commit Roboco merged (2026-09-15)

# Prototype thresholds (.scratch/upstream-sync-research, report section c).
TRIVIAL_MAX_REGIONS = 3
TRIVIAL_MAX_LINES = 30
TRIVIAL_FILES = ("Cargo.lock",)
PRESENCE_PORTED = 0.6
PRESENCE_MISSING = 0.3


def utf8_stdio():
    """Windows consoles default to cp1252; subjects carry arrows and emoji."""
    for s in (sys.stdout, sys.stderr):
        try:
            s.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass


def read_table(path):
    """Rows of a `#`-commented TSV, as lists of cells (trailing empties kept)."""
    rows = []
    with open(path, encoding="utf-8") as fh:
        for ln in fh:
            ln = ln.rstrip("\r\n")
            if not ln.strip() or ln.lstrip().startswith("#"):
                continue
            rows.append(ln.split("\t"))
    return rows


def is_binary(data):
    return b"\0" in data[:8000]


# --- rename map -------------------------------------------------------------------


class RenameMap:
    """The AGENTS.md rename mapping as data (rename-map.tsv)."""

    def __init__(self, path=RENAME_MAP):
        self.protect, self.regex, self.text, self.paths, self.verbatim = [], [], [], [], []
        for row in read_table(path):
            kind, src, dst = row[0], row[1], row[2] if len(row) > 2 else ""
            if kind == "protect":
                self.protect.append(src)
            elif kind == "regex":
                self.regex.append((re.compile(src), dst))
            elif kind == "text":
                self.text.append((src, dst))
            elif kind == "path":
                self.paths.append((src, dst))
            elif kind == "verbatim":
                self.verbatim.append(src)
            else:
                raise ValueError(f"{path}: unknown kind {kind!r}")

    def _rewrite(self, s, use_regex=True):
        marks = []
        for i, tok in enumerate(self.protect):
            mark = f"\x00P{i}\x00"
            if tok in s:
                s = s.replace(tok, mark)
                marks.append((mark, tok))
        if use_regex:
            for rx, dst in self.regex:
                s = rx.sub(dst, s)
        for src, dst in self.text:
            s = s.replace(src, dst)
        for mark, tok in marks:
            s = s.replace(mark, tok)
        return s

    def text_str(self, s):
        """Translate a piece of text (a line, a subject)."""
        return self._rewrite(s)

    def text_bytes(self, data, path=""):
        """Translate file contents. Binary, non-UTF-8 and verbatim files pass through."""
        if data is None or is_binary(data):
            return data
        if any(path.startswith(p) for p in self.verbatim):
            return data
        try:
            s = data.decode("utf-8")
        except UnicodeDecodeError:
            return data
        return self._rewrite(s).encode("utf-8")

    def path(self, p):
        """Map an upstream path to its Roboco path."""
        for src, dst in self.paths:
            if p.startswith(src):
                p = dst + p[len(src):]
                break
        return self._rewrite(p, use_regex=False)


# --- path rules ---------------------------------------------------------------------


def _match(pattern, path):
    if pattern.endswith("/"):
        return path.startswith(pattern)
    if any(c in pattern for c in "*?["):
        return fnmatch.fnmatchcase(path, pattern)
    return path == pattern


class PathRules:
    """Auto-skip and Android-lane routing (path-rules.tsv)."""

    def __init__(self, path=PATH_RULES):
        self.skip, self.ios, self.subject = [], [], []
        for row in read_table(path):
            kind, pat, val = row[0], row[1], row[2]
            if kind == "skip":
                self.skip.append((pat, val))
            elif kind == "ios":
                self.ios.append((pat, val))
            elif kind == "subject":
                self.subject.append((re.compile(pat), val))
            else:
                raise ValueError(f"{path}: unknown kind {kind!r}")

    def file_reason(self, path):
        """`ios`, a skip reason, or None when the file is portable."""
        for pat, _ in self.ios:
            if _match(pat, path):
                return "ios"
        for pat, why in self.skip:
            if _match(pat, path):
                return why
        return None

    def route(self, subject, paths):
        """Lane for a commit: ("auto-skip", reason) / ("android-lane", "ios") / ("review", note)."""
        for rx, why in self.subject:
            if rx.search(subject):
                return "auto-skip", why
        reasons = [self.file_reason(p) for p in paths]
        if not paths:
            return "review", "no file changes"
        if all(r is not None for r in reasons):
            if "ios" in reasons:
                return "android-lane", "ios"
            return "auto-skip", ",".join(sorted(set(reasons)))
        if "ios" in reasons:
            return "review", "also has an iOS slice: check the Android lane"
        return "review", ""


# --- git ---------------------------------------------------------------------------------


class GitError(RuntimeError):
    pass


class Repo:
    """Read-only git access. All objects come from one `git cat-file --batch`."""

    def __init__(self, path="."):
        self.path = path
        self._cat = None

    def git(self, *args, check=True):
        r = subprocess.run(["git", "-C", self.path, *args], capture_output=True)
        if check and r.returncode != 0:
            raise GitError(f"git {' '.join(args)}: {r.stderr.decode('utf-8', 'replace').strip()}")
        return r.stdout.decode("utf-8", "replace")

    def toplevel(self):
        return self.git("rev-parse", "--show-toplevel").strip()

    def rev(self, rev):
        return self.git("rev-parse", "--verify", "--quiet", rev + "^{commit}").strip()

    def default_target(self):
        """`main`, or `origin/main` in a clone without a local main branch."""
        return "main" if self.rev("main") else "origin/main"

    def blob(self, rev, path):
        """Bytes of `rev:path`, or None when it doesn't exist (or is a tree: see `exists`)."""
        kind, data = self._object(f"{rev}:{path}")
        return data if kind == "blob" else None

    def exists(self, rev, path):
        return self._object(f"{rev}:{path}")[0] is not None

    def _object(self, spec):
        if self._cat is None:
            self._cat = subprocess.Popen(["git", "-C", self.path, "cat-file", "--batch"],
                                         stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        self._cat.stdin.write(spec.encode("utf-8") + b"\n")
        self._cat.stdin.flush()
        header = self._cat.stdout.readline().decode("utf-8", "replace").split()
        if len(header) < 3 or header[-1] == "missing":
            return None, None
        data = self._cat.stdout.read(int(header[2]))
        self._cat.stdout.read(1)
        return header[1], data

    def changes(self, sha):
        """[(status, src, dst, new_mode)] for sha against its first parent, renames detected."""
        out = self.git("diff-tree", "-r", "-M", "--no-commit-id", "--raw", "-z", f"{sha}^", sha)
        parts = out.split("\0")
        rows, i = [], 0
        while i < len(parts) - 1:
            meta = parts[i].lstrip(":").split()
            st = meta[4][0]
            new_mode = meta[1]
            if st in "RC":
                rows.append((st, parts[i + 1], parts[i + 2], new_mode))
                i += 3
            else:
                rows.append((st, parts[i + 1], parts[i + 1], new_mode))
                i += 2
        return rows

    def commits(self, rng, reverse=True):
        """[(sha, date, subject)] of non-merge commits in a range, oldest first."""
        args = ["log", "--no-merges", "--format=%H%x09%ad%x09%s", "--date=short"]
        if reverse:
            args.append("--reverse")
        out = self.git(*args, rng)
        return [tuple(ln.split("\t", 2)) for ln in out.splitlines() if ln]

    def subject(self, sha):
        return self.git("log", "-1", "--format=%s", sha).strip()

    def close(self):
        if self._cat:
            self._cat.stdin.close()
            self._cat.wait()
            self._cat = None


def upstream_prs(repo, rng):
    """{sha: pr_number}: `(#N)` in the subject, else the `Merge pull request #N` that brought it."""
    prs = {}
    for ln in repo.git("log", "--no-merges", "--format=%H%x09%s", rng).splitlines():
        sha, _, subject = ln.partition("\t")
        m = re.search(r"\(#(\d+)\)\s*$", subject)
        if m:
            prs[sha] = int(m.group(1))
    for ln in repo.git("log", "--merges", "--format=%H %P%x09%s", rng).splitlines():
        head, _, subject = ln.partition("\t")
        parents = head.split()[1:]
        m = re.match(r"Merge pull request #(\d+)", subject)
        if m and len(parents) >= 2:
            for c in repo.git("rev-list", "--no-merges", f"{parents[0]}..{parents[1]}").split():
                prs.setdefault(c, int(m.group(1)))
    return prs


# --- the translated cherry-pick ---------------------------------------------------------------


def count_conflicts(merged):
    """(regions, lines) of conflict markers in merge output (lines exclude marker lines)."""
    regions = lines = 0
    inside = False
    for ln in merged.split(b"\n"):
        if ln.startswith(b"<<<<<<< "):
            regions += 1
            inside = True
        elif ln.startswith(b">>>>>>> "):
            inside = False
        elif inside and not ln.startswith(b"=======") and not ln.startswith(b"|||||||"):
            lines += 1
    return regions, lines


def merge3(ours, base, theirs, style="merge", labels=("roboco", "upstream^", "upstream")):
    """`git merge-file -p` on bytes. Returns (merged, regions, lines). Binary: no text merge."""
    if is_binary(ours) or is_binary(base) or is_binary(theirs):
        if ours == base:
            return theirs, 0, 0
        if theirs == base or theirs == ours:
            return ours, 0, 0
        return ours, 1, 0
    with tempfile.TemporaryDirectory() as d:
        names = []
        for n, b in (("ours", ours), ("base", base), ("theirs", theirs)):
            f = os.path.join(d, n)
            with open(f, "wb") as h:
                h.write(b)
            names.append(f)
        args = ["git", "merge-file", "-p"]
        if style == "diff3":
            args.append("--diff3")
        for lab in labels:
            args += ["-L", lab]
        r = subprocess.run(args + names, capture_output=True)
    # exit status = number of conflicts (capped at 127); >127 means an error
    if r.returncode < 0 or r.returncode > 127:
        raise GitError(f"git merge-file failed: {r.stderr.decode('utf-8', 'replace').strip()}")
    regions, lines = count_conflicts(r.stdout)
    return r.stdout, regions, lines


class FileResult:
    __slots__ = ("status", "upath", "path", "state", "reason", "regions", "lines",
                 "merged", "mode", "old_path", "base", "theirs", "ours")

    def __init__(self, status, upath, path, state, **kw):
        self.status, self.upath, self.path, self.state = status, upath, path, state
        self.reason = kw.get("reason", "")
        self.regions = kw.get("regions", 0)
        self.lines = kw.get("lines", 0)
        self.merged = kw.get("merged")
        self.mode = kw.get("mode", "100644")
        self.old_path = kw.get("old_path")
        self.base = kw.get("base")
        self.theirs = kw.get("theirs")
        self.ours = kw.get("ours")

    def label(self):
        if self.state == "conflict":
            return f"CONFLICT {self.regions}r/{self.lines}l" + (f" ({self.reason})" if self.reason else "")
        if self.state in ("skipped", "missing"):
            return f"{self.state.upper()} {self.reason}".strip()
        return self.state.upper()


class Replay:
    """Result of replaying one upstream commit onto a Roboco rev."""

    def __init__(self, sha, target):
        self.sha, self.target = sha, target
        self.files = []

    def count(self, state):
        return sum(1 for f in self.files if f.state == state)

    @property
    def regions(self):
        return sum(f.regions for f in self.files)

    @property
    def lines(self):
        return sum(f.lines for f in self.files)

    def classify(self):
        """AUTO-SKIP / ALREADY-IN / CLEAN / TRIVIAL / REAL (report section c)."""
        live = [f for f in self.files if f.state != "skipped"]
        if not live:
            return "AUTO-SKIP"
        missing = self.count("missing")
        conflicted = [f for f in live if f.state == "conflict"]
        if not missing and not conflicted:
            return "CLEAN" if any(f.state in ("clean", "added", "deleted") for f in live) else "ALREADY-IN"
        nontrivial = [f for f in conflicted if not f.path.startswith(TRIVIAL_FILES)]
        if not missing and (not nontrivial or (self.regions <= TRIVIAL_MAX_REGIONS
                                              and self.lines <= TRIVIAL_MAX_LINES)):
            return "TRIVIAL"
        return "REAL"

    def summary(self):
        live = len(self.files) - self.count("skipped")
        return (f"files={live} (+{self.count('skipped')} skipped) "
                f"clean={self.count('clean') + self.count('added') + self.count('deleted')} "
                f"noop={self.count('noop')} conflicted={self.count('conflict')} "
                f"({self.regions}r/{self.lines}l) missing={self.count('missing')}")


def replay(repo, sha, target, renames=None, rules=None, mode="full", style="merge"):
    """Three-way merge of upstream `sha` onto Roboco `target`, per file, in memory.

    ours = the Roboco file at `target`; base / theirs = the upstream file before /
    after `sha`, both translated through the rename map. This is what
    `git cherry-pick` would do if upstream history were already renamed.
    mode: "full" (paths + text), "paths" (paths only), "raw" (a plain cherry-pick).
    """
    renames = renames or RenameMap()
    rules = rules or PathRules()
    res = Replay(sha, target)

    def mp(p):
        return p if mode == "raw" else renames.path(p)

    def tx(data, p):
        return renames.text_bytes(data, p) if mode == "full" else data

    for st, src, dst, new_mode in repo.changes(sha):
        why = rules.file_reason(dst) or rules.file_reason(src)
        msrc, mdst = mp(src), mp(dst)
        if why:
            res.files.append(FileResult(st, dst, mdst, "skipped", reason=why))
            continue
        base = tx(repo.blob(f"{sha}^", src), src) if st != "A" else b""
        theirs = tx(repo.blob(sha, dst), dst) if st != "D" else None
        if st == "D":
            ours = repo.blob(target, msrc)
            if ours is None or ours == base:
                res.files.append(FileResult(st, src, msrc, "deleted" if ours is not None else "noop"))
            else:
                res.files.append(FileResult(st, src, msrc, "conflict", regions=1,
                                            reason="deleted upstream, changed in Roboco"))
            continue
        old = None
        ours = repo.blob(target, mdst)
        if ours is None and st in "RC" and msrc != mdst:
            ours = repo.blob(target, msrc)
            old = msrc if (ours is not None and st == "R") else None
        common = dict(mode=new_mode, old_path=old, base=base, theirs=theirs, ours=ours)
        if ours is None:
            parts = mdst.split("/")
            top = "/".join(parts[:2]) if len(parts) > 2 else parts[0]
            if st == "A" and (len(parts) == 1 or repo.exists(target, top)):
                res.files.append(FileResult(st, dst, mdst, "added", merged=theirs, **common))
            elif len(parts) > 1 and not repo.exists(target, top):
                res.files.append(FileResult(st, dst, mdst, "missing",
                                            reason=f"{top}/ is not in Roboco", **common))
            else:
                res.files.append(FileResult(st, dst, mdst, "missing", reason="no Roboco file", **common))
            continue
        if ours == theirs:
            res.files.append(FileResult(st, dst, mdst, "noop", **common))
            continue
        merged, regions, lines = merge3(ours, base, theirs, style=style)
        if regions:
            res.files.append(FileResult(st, dst, mdst, "conflict", regions=regions, lines=lines,
                                        merged=merged, **common))
        else:
            res.files.append(FileResult(st, dst, mdst, "clean", merged=merged, **common))
    return res


# --- presence --------------------------------------------------------------------------------


def presence_scores(repo, log_args, target, renames=None, rules=None):
    """[(sha, subject, score, hits, total)] for commits from `git log -p <log_args>`.

    Score = share of the commit's translated added lines (>= 12 chars, not just
    punctuation) already present, whitespace-stripped, in the mapped Roboco
    file at `target`. Measured on the wave-10 audit: PORTED median 0.93,
    MISSING 0.08; >= 0.6 never hit a missing commit.
    """
    renames = renames or RenameMap()
    rules = rules or PathRules()
    out = repo.git("log", "--no-merges", "-p", "--format=@@@%H %s", *log_args)
    cache = {}

    def lines_of(path):
        if path not in cache:
            b = repo.blob(target, path)
            cache[path] = None if b is None else {
                x.strip() for x in b.decode("utf-8", "replace").splitlines()}
        return cache[path]

    rows = []
    for blk in ("\n" + out).split("\n@@@")[1:]:
        head = blk.split("\n", 1)[0]
        sha, _, subject = head.partition(" ")
        tot = hit = 0
        path = None
        for ln in blk.split("\n"):
            if ln.startswith("diff --git "):
                p = ln.split(" b/", 1)[-1]
                path = None if rules.file_reason(p) or p.startswith(".github/") else renames.path(p)
            elif path and ln.startswith("+") and not ln.startswith("+++"):
                t = renames.text_str(ln[1:]).strip()
                if len(t) < 12 or re.fullmatch(r"[\W_]+", t):
                    continue
                have = lines_of(path)
                tot += 1
                hit += bool(have and t in have)
        if sha:
            rows.append((sha, subject, (hit / tot) if tot else None, hit, tot))
    return rows


def presence_verdict(score):
    if score is None:
        return "n/a"
    if score >= PRESENCE_PORTED:
        return "probably-ported"
    if score < PRESENCE_MISSING:
        return "probably-missing"
    return "check"


# --- ledger ------------------------------------------------------------------------------------

LEDGER_COLUMNS = ["upstream_sha", "date", "pr", "subject", "status", "audit", "note"]
STATUS_RE = re.compile(
    r"^(?:ported(?: [0-9a-f]{7,40})?"
    r"|skipped:[a-z0-9][a-z0-9/._-]*"
    r"|superseded|declined|missing|partial|android-lane|new|later"
    r"|ticketed [a-z0-9][a-z0-9.-]*-\d+(?:,\d+)*(?:,[a-z0-9][a-z0-9.-]*-\d+(?:,\d+)*)*"
    r"|queued [A-Za-z0-9._-]+)$")
OPEN_STATUSES = ("new", "later", "android-lane")


def valid_status(s):
    return bool(STATUS_RE.match(s))


class LedgerError(ValueError):
    pass


class Ledger:
    """docs/reference/upstream-ledger.tsv: one row per upstream commit, in upstream order."""

    def __init__(self, rows=None, columns=None):
        self.columns = columns or list(LEDGER_COLUMNS)
        self.rows = rows or []
        self._index = None

    @classmethod
    def parse(cls, text, source="ledger"):
        lines = [ln for ln in text.replace("\r\n", "\n").split("\n")]
        if not lines or not lines[0].startswith("upstream_sha\t"):
            raise LedgerError(f"{source}: first line must be the header row")
        cols = lines[0].split("\t")
        missing = [c for c in LEDGER_COLUMNS if c not in cols]
        if missing:
            raise LedgerError(f"{source}: missing columns {missing}")
        rows, seen = [], set()
        for n, ln in enumerate(lines[1:], start=2):
            if not ln:
                continue
            cells = ln.split("\t")
            if len(cells) != len(cols):
                raise LedgerError(f"{source}:{n}: {len(cells)} cells, expected {len(cols)}")
            row = dict(zip(cols, cells))
            sha = row["upstream_sha"]
            if not re.fullmatch(r"[0-9a-f]{40}", sha):
                raise LedgerError(f"{source}:{n}: upstream_sha must be a full 40-char sha")
            if sha in seen:
                raise LedgerError(f"{source}:{n}: duplicate row for {sha}")
            if not valid_status(row["status"]):
                raise LedgerError(f"{source}:{n}: bad status {row['status']!r}")
            seen.add(sha)
            rows.append(row)
        return cls(rows, cols)

    @classmethod
    def load(cls, path):
        with open(path, encoding="utf-8", newline="") as fh:
            return cls.parse(fh.read(), path)

    def dumps(self):
        out = ["\t".join(self.columns)]
        for r in self.rows:
            cells = [clean_cell(r.get(c, "")) for c in self.columns]
            out.append("\t".join(cells))
        return "\n".join(out) + "\n"

    def save(self, path):
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(self.dumps())

    def _idx(self):
        if self._index is None:
            self._index = {r["upstream_sha"]: r for r in self.rows}
        return self._index

    def __contains__(self, sha):
        return self.find(sha) is not None

    def find(self, sha_or_prefix):
        """The row for a full sha or an unambiguous prefix (>= 7 chars), else None."""
        s = sha_or_prefix.lower()
        idx = self._idx()
        if s in idx:
            return idx[s]
        if len(s) < 7:
            raise LedgerError(f"sha prefix {s!r} is too short")
        hits = [r for k, r in idx.items() if k.startswith(s)]
        if len(hits) > 1:
            raise LedgerError(f"sha prefix {s!r} is ambiguous")
        return hits[0] if hits else None

    def add(self, row):
        if row["upstream_sha"] in self._idx():
            raise LedgerError(f"{row['upstream_sha']} is already in the ledger")
        if not valid_status(row["status"]):
            raise LedgerError(f"bad status {row['status']!r}")
        full = {c: row.get(c, "") for c in self.columns}
        self.rows.append(full)
        self._idx()[full["upstream_sha"]] = full
        return full

    def set_status(self, sha, status, note=None):
        """Change one row. Returns (row, old_status)."""
        if not valid_status(status):
            raise LedgerError(f"bad status {status!r}")
        row = self.find(sha)
        if row is None:
            raise LedgerError(f"{sha} is not in the ledger")
        old = row["status"]
        row["status"] = status
        if note is not None:
            row["note"] = note
        return row, old

    def counts(self):
        out = {}
        for r in self.rows:
            k = status_kind(r["status"])
            out[k] = out.get(k, 0) + 1
        return out


def status_kind(status):
    """Group a status for stats: `ported`, `skipped:landing`, `ticketed wave10`, ..."""
    if status.startswith("skipped:"):
        return status
    if status.startswith("ticketed "):
        return "ticketed " + re.sub(r"-\d.*$", "", status.split()[1])
    return status.split()[0]


def clean_cell(s):
    return re.sub(r"[\t\r\n]+", " ", s or "").strip()


def short(sha, n=9):
    return sha[:n]


def ledger_path(repo_root):
    return os.path.join(repo_root, LEDGER)


def roboco_ports(repo, rng):
    """{upstream_sha_or_prefix: roboco_sha} from `Upstream-Commit:` trailers and
    `(cherry picked from commit X)` lines in a Roboco range, newest port wins."""
    out = repo.git("log", "--format=@@@%H%n%B", rng)
    found = {}
    for blk in out.split("@@@")[1:]:
        head, _, body = blk.partition("\n")
        for m in re.finditer(r"^(?:Upstream-Commit:\s*|\(cherry picked from commit )([0-9a-f]{7,40})",
                             body, re.M | re.I):
            found.setdefault(m.group(1).lower(), head)
    return found
