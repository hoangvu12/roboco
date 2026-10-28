import fnmatch
import os
import re
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import upstream_common as uc  # noqa: E402

AGENTS = os.path.join(uc.REPO_ROOT, "AGENTS.md")


def agents_rename_bullets():
    """Bullet lines of AGENTS.md's 'Rename mapping for retained code' list."""
    with open(AGENTS, encoding="utf-8") as fh:
        text = fh.read()
    m = re.search(r"^Rename mapping for retained code:\s*\n\n((?:- .*\n)+)", text, re.M)
    if not m:
        raise AssertionError("AGENTS.md has no 'Rename mapping for retained code:' list")
    return [ln[2:] for ln in m.group(1).splitlines()]


class RenameMapMirrorsAgents(unittest.TestCase):
    """Every `a` -> `b` pair AGENTS.md lists must come out of rename-map.tsv."""

    def setUp(self):
        self.m = uc.RenameMap()

    def test_every_agents_pair(self):
        checked = 0
        for line in agents_rename_bullets():
            segments = line.split("->")
            self.assertGreater(len(segments), 1, line)
            for left, right in zip(segments, segments[1:]):
                rights = re.findall(r"`([^`]+)`", right)
                for tok in re.findall(r"`([^`]+)`", left):
                    out = self.m.text_str(tok)
                    if out == tok:
                        continue  # already-Roboco token from the previous pair
                    ok = out in rights or any(fnmatch.fnmatchcase(out, r) for r in rights if "*" in r)
                    self.assertTrue(ok, f"{tok!r} -> {out!r}, AGENTS.md says one of {rights}: {line}")
                    checked += 1
        self.assertGreaterEqual(checked, 15)

    def test_roboco_side_is_stable(self):
        for line in agents_rename_bullets():
            for right in line.split("->")[1:]:
                for tok in re.findall(r"`([^`]+)`", right):
                    if "roboco" in tok.lower():
                        self.assertEqual(self.m.text_str(tok), tok)

    def test_rebrand_boundaries_stay(self):
        for s in ("https://github.com/zeronsh/zeron/pull/313", "zeronsh/zui", "ZERON_GPU_STATS=1",
                  "git branch -f zeron/main upstream/main"):
            self.assertEqual(self.m.text_str(s), s)

    def test_cursor_shim_gets_prefixed_once(self):
        self.assertEqual(self.m.text_str('env("CURSOR_SDK_SHIM_EXECUTABLE")'),
                         'env("ROBOCO_CURSOR_SDK_SHIM_EXECUTABLE")')
        self.assertEqual(self.m.text_str("ZERON_CURSOR_SDK_SHIM_EXECUTABLE"),
                         "ROBOCO_CURSOR_SDK_SHIM_EXECUTABLE")
        self.assertEqual(self.m.text_str("ROBOCO_CURSOR_SDK_SHIM_EXECUTABLE"),
                         "ROBOCO_CURSOR_SDK_SHIM_EXECUTABLE")

    def test_code_line(self):
        self.assertEqual(
            self.m.text_str('use zeron_proto::Zeron; let d = env::var("ZERON_DATA_DIR"); // zeronsh'),
            'use roboco_proto::Roboco; let d = env::var("ROBOCO_DATA_DIR"); // zeronsh')

    def test_paths(self):
        p = self.m.path
        self.assertEqual(p("apps/zeron/src/main.rs"), "apps/roboco/src/main.rs")
        self.assertEqual(p("crates/mcp/src/tools.rs"), "crates/roboco-mcp/src/tools.rs")
        self.assertEqual(p("crates/client/src/catalog.rs"), "crates/mobile-client/src/catalog.rs")
        self.assertEqual(p("crates/ui/src/shell.rs"), "crates/ui/src/shell.rs")
        self.assertEqual(p("crates/mcpx/src/a.rs"), "crates/mcpx/src/a.rs")
        self.assertEqual(p("dist/zeron.desktop"), "dist/roboco.desktop")

    def test_file_bytes(self):
        m = self.m
        self.assertEqual(m.text_bytes(b"zeron\n", "a.rs"), b"roboco\n")
        self.assertEqual(m.text_bytes(b"\x89PNG\0zeron", "icon.png"), b"\x89PNG\0zeron")
        self.assertEqual(m.text_bytes(b"\xff\xfezeron", "x.bin"), b"\xff\xfezeron")
        self.assertEqual(m.text_bytes(b"CURSOR_SDK_SHIM_EXECUTABLE zeron", "docs/research/n.md"),
                         b"CURSOR_SDK_SHIM_EXECUTABLE zeron")
        self.assertIsNone(m.text_bytes(None, "a.rs"))

    def test_table_kinds_are_known(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "bad.tsv")
            with open(path, "w", encoding="utf-8") as fh:
                fh.write("bogus\ta\tb\n")
            with self.assertRaises(ValueError):
                uc.RenameMap(path)

if __name__ == "__main__":
    unittest.main()
