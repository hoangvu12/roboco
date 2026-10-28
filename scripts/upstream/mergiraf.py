"""Optional mergiraf fallback for translate.py (opt-in, never required).

mergiraf (https://mergiraf.org) is a syntax-aware merge tool: an external,
pre-1.0, GPL-3 binary. It is never vendored or installed by these scripts.
translate.py runs it only when asked (`--mergiraf [PATH]`, or the
ROBOCO_MERGIRAF environment variable naming the binary), only on files git
left conflicted, and keeps its output only when it resolves the whole file.
Anything else (not installed, a crash, a timeout, a partial merge) keeps
git's conflict as it was.
"""
import os
import shutil
import subprocess
import tempfile

ENV = "ROBOCO_MERGIRAF"
# Measured on wave 10 (report-2026-10-29-less-recoding.md, option 2): Rust and
# TOML resolve; Cargo.lock, Markdown, JS, YAML, JSON almost never do.
EXTENSIONS = (".rs", ".toml", ".kt", ".kts")
SKIP_NAMES = ("Cargo.lock",)
DEFAULT_TIMEOUT = 300


def find(path=None):
    """The mergiraf binary to run: `path`, else $ROBOCO_MERGIRAF, else `mergiraf` on PATH. None if absent."""
    cand = path or os.environ.get(ENV) or "mergiraf"
    if os.path.isfile(cand):
        return cand
    return shutil.which(cand)


def supported(path):
    name = path.rsplit("/", 1)[-1]
    return name not in SKIP_NAMES and name.endswith(EXTENSIONS)


def count_markers(data):
    """Conflict regions in mergiraf output (it uses git's 7-char markers)."""
    return sum(1 for ln in data.split(b"\n") if ln.startswith(b"<<<<<<<"))


def merge(exe, path, base, ours, theirs, timeout=DEFAULT_TIMEOUT):
    """mergiraf's merge of one file, or (None, why) unless it fully resolves it.

    `exe` is the binary (or a command list). Returns (merged bytes, "") or (None, why).
    """
    name = path.rsplit("/", 1)[-1]
    with tempfile.TemporaryDirectory() as d:
        files = []
        for tag, data in (("base", base), ("ours", ours), ("theirs", theirs)):
            p = os.path.join(d, f"{tag}_{name}")
            with open(p, "wb") as fh:
                fh.write(data)
            files.append(p)
        out = os.path.join(d, "merged_" + name)
        try:
            cmd = list(exe) if isinstance(exe, (list, tuple)) else [exe]
            r = subprocess.run(cmd + ["merge", *files, "-o", out, "-p", path,
                                      "-s", "upstream^", "-x", "roboco", "-y", "upstream"],
                               capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            return None, f"timed out after {timeout}s"
        except OSError as e:
            return None, f"could not run: {e}"
        if not os.path.exists(out):
            err = r.stderr.decode("utf-8", "replace").strip().splitlines()
            return None, f"no output (exit {r.returncode}{': ' + err[-1] if err else ''})"
        with open(out, "rb") as fh:
            data = fh.read()
    n = count_markers(data)
    if n:
        return None, f"left {n} conflict(s)"
    if not data.strip() and (ours.strip() or theirs.strip()):
        return None, "empty output"
    return data, ""
