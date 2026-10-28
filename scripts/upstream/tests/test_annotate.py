import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import annotate  # noqa: E402
import upstream_common as uc  # noqa: E402


def text(*lines):
    return ("\n".join(lines) + "\n").encode("utf-8")


class FakeCtx:
    """Blame and ledger answers without a repository."""

    def __init__(self, blame=None, origins=None):
        self._blame = blame or {}
        self._origins = origins or {}

    def blame(self, upath):
        return self._blame

    def origin(self, sha):
        return self._origins.get(sha, ("unrecorded", "not in the ledger", ""))


PENDING = "1" * 40
CLOUD = "2" * 40
PORTED = "3" * 40


def regions_of(ours, base, theirs, ctx=None):
    f = uc.FileResult("M", "x.rs", "x.rs", "conflict", ours=ours, base=base, theirs=theirs)
    diff3, n, _ = uc.merge3(ours, base, theirs, style="diff3")
    assert n, "fixture must conflict"
    return annotate.analyze(ctx or FakeCtx(), f, diff3, "x.rs")


class ParseTest(unittest.TestCase):
    def test_diff3_and_merge_style(self):
        ours, base, theirs = text("a", "ours", "z"), text("a", "base", "z"), text("a", "theirs", "z")
        d3, _, _ = uc.merge3(ours, base, theirs, style="diff3")
        (r,) = annotate.parse_regions(d3)
        self.assertEqual((r["start"], r["end"], r["ours"], r["base"], r["theirs"]),
                         (2, 8, ["ours"], ["base"], ["theirs"]))
        m, _, _ = uc.merge3(ours, base, theirs)
        (r,) = annotate.parse_regions(m.replace(b"\n", b"\r\n"))
        self.assertEqual((r["base"], r["theirs"]), (None, ["theirs"]))

    def test_locate_maps_to_the_written_file(self):
        regions = [{"start": 1, "end": 2}]
        written = text("x", "<<<<<<< roboco", "a", "=======", "b", ">>>>>>> upstream")
        self.assertEqual(annotate.locate(regions, written)[0]["start"], 2)
        self.assertIsNone(annotate.locate([{"start": 1, "end": 2}] * 2, written)[0]["start"])


class AnalyzeTest(unittest.TestCase):
    def test_already_in_roboco(self):
        (r,) = regions_of(text("fn a() {}", "let x = roboco_value();", "fn z() {}", "let y = upstream_value();"),
                          text("fn a() {}", "let x = 1;", "fn z() {}"),
                          text("fn a() {}", "let y = upstream_value();", "fn z() {}"))
        self.assertEqual((r["hint"], r["action"]), ("already", "keep ours"))

    def test_missing_prerequisite(self):
        base = text("fn a() {}", "    prerequisite_call(alpha);", "    prerequisite_call(beta);", "fn z() {}")
        theirs = text("fn a() {}", "    prerequisite_call(alpha, gamma);", "    prerequisite_call(beta);", "fn z() {}")
        ours = text("fn a() {}", "    roboco_call();", "fn z() {}")
        ctx = FakeCtx({"prerequisite_call(alpha);": [PENDING], "prerequisite_call(beta);": [PENDING]},
                      {PENDING: ("pending", "ticketed wave10-05", "Windows paths")})
        (r,) = regions_of(ours, base, theirs, ctx)
        self.assertEqual(r["hint"], "prerequisite")
        self.assertEqual(r["action"], "port the prerequisite first")
        self.assertEqual(r["reasons"]["prerequisite"]["commits"][0]["sha"], PENDING[:9])
        self.assertIn("roboco-only", r["reasons"])

    def test_cloud_code(self):
        base = text("Config {", "    port: 1,", "}")
        theirs = text("Config {", "    port: 2,", "    edge_token: None,", "    org_id: None,", "}")
        ours = text("Config {", "    port: 1,", "    windows: true,", "}")
        (r,) = regions_of(ours, base, theirs)
        self.assertEqual((r["hint"], r["action"]), ("removed", "drop upstream's side"))
        self.assertEqual(r["reasons"]["removed"]["kinds"], ["cloud"])

    def test_one_cloud_word_does_not_decide_a_big_region(self):
        base = text("fn a() {}", "fn z() {}")
        theirs = text("fn a() {}", *[f"    let v{i} = compute_{i}();" for i in range(6)],
                      "    // cloud fallback", "fn z() {}")
        ours = text("fn a() {}", "    let roboco = mine();", "fn z() {}")
        (r,) = regions_of(ours, base, theirs)
        self.assertIn("removed", r["reasons"])
        self.assertEqual(r["hint"], "roboco-only")

    def test_declined_origin(self):
        base = text("fn a() {}", "    old_declined_feature();", "fn z() {}")
        theirs = text("fn a() {}", "    old_declined_feature(2);", "fn z() {}")
        ours = text("fn a() {}", "    roboco_feature();", "fn z() {}")
        ctx = FakeCtx({"old_declined_feature();": [CLOUD]}, {CLOUD: ("declined", "declined", "WebRTC")})
        (r,) = regions_of(ours, base, theirs, ctx)
        self.assertEqual(r["hint"], "removed")

    def test_rename_only(self):
        (r,) = regions_of(text("a", 'const NAME: &str = "Roboco desktop";', "z"),
                          text("a", 'const NAME: &str = "desktop";', "z"),
                          text("a", 'const NAME: &str = "Zeron desktop";', "z"))
        self.assertEqual(r["hint"], "rename")

    def test_ported_and_prefork(self):
        base = text("fn a() {}", "    ported_line_one();", "    prefork_line_two();", "fn z() {}")
        theirs = text("fn a() {}", "    ported_line_one(1);", "    prefork_line_two(2);", "fn z() {}")
        ours = text("fn a() {}", "    roboco_version();", "fn z() {}")
        ctx = FakeCtx({"ported_line_one();": [PORTED], "prefork_line_two();": ["4" * 40]},
                      {PORTED: ("ported", "ported abc123456", "x"), "4" * 40: ("prefork", "pre-fork", "")})
        (r,) = regions_of(ours, base, theirs, ctx)
        self.assertEqual(r["hint"], "ported")
        self.assertIn("prefork", r["reasons"])

    def test_unknown_origin_and_drift(self):
        (r,) = regions_of(text("a", "    roboco_only();", "z"), text("a", "    gone();", "z"),
                          text("a", "    gone(1);", "z"))
        self.assertEqual(r["hint"], "roboco-only")
        self.assertIn("lacked", r["reasons"])
        (r,) = regions_of(text("a", "    indented_call();", "z"), text("a", "indented_call();", "z"),
                          text("a", "  indented_call();", "z"))
        self.assertEqual((r["hint"], r["action"]), ("drift", "either side"))


class ReportTest(unittest.TestCase):
    def record(self):
        return {"sha": "a" * 40, "target": "b" * 40, "subject": "Fix | pipes", "class": "REAL",
                "summary": "files=3", "options": ["residual (threshold 1)"],
                "files": [
                    {"path": "x.rs", "state": "conflict", "reason": "", "how": "",
                     "residual": {"used": True, "kept": 1, "why": "",
                                  "dropped": [{"upstream_lines": [3, 9], "added": 6, "removed": 0,
                                               "first": "pub(crate) enum FileLinkResolution {", "lacking": []},
                                              {"upstream_lines": [12, 11], "base_lines": [10, 10], "added": 0,
                                               "removed": 1, "first": "/// See `old` | here", "lacking": []}]},
                     "regions_detail": [{"start": 4, "end": 12, "hint": "prerequisite",
                                         "action": "port the prerequisite first", "why": "w",
                                         "reasons": {"prerequisite": {"commits": [
                                             {"sha": "111111111", "status": "ticketed wave10-05",
                                              "subject": "s", "lines": 3}]}}}]},
                    {"path": "y.rs", "state": "clean", "reason": "", "how": "mergiraf"},
                    {"path": "Cargo.lock", "state": "regenerate", "reason": "", "how": "",
                     "lock": {"describe": ["upstream bumped: serde 1.0.1 -> 1.0.2"],
                              "commands": ["cargo metadata --format-version 1"]}},
                    {"path": "z.rs", "state": "missing", "reason": "no Roboco file", "how": ""},
                ]}

    def test_markdown(self):
        md = annotate.markdown(self.record())
        self.assertIn("| `x.rs` | 4-12 | port the prerequisite first |", md)
        self.assertIn("111111111 (ticketed wave10-05, 3l)", md)
        self.assertIn("## Residual mode", md)
        self.assertIn("upstream lines 3-9 (+6/-0): `pub(crate) enum FileLinkResolution {`", md)
        self.assertIn("upstream^ lines 10-10 (removed) (+0/-1): `` /// See `old` | here ``", md)
        self.assertIn("## Resolved by mergiraf", md)
        self.assertIn("`cargo metadata --format-version 1`", md)
        self.assertIn("`z.rs`: no Roboco file", md)

    def test_write(self):
        with tempfile.TemporaryDirectory() as d:
            md, js = annotate.write(os.path.join(d, "reports"), self.record())
            self.assertTrue(md.endswith("aaaaaaaaa.md"))
            with open(js, encoding="utf-8") as fh:
                self.assertEqual(json.load(fh)["class"], "REAL")


if __name__ == "__main__":
    unittest.main()
