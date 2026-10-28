import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import triage  # noqa: E402


def item(sha, subject, **kw):
    return dict(sha=sha, subject=subject, translate=kw.get("translate", "CLEAN"),
                presence=kw.get("presence", "0.05 probably-missing"), details=kw.get("details", "1 file(s)"),
                decision=kw.get("decision", ""), note=kw.get("note", ""))


class PageTest(unittest.TestCase):
    def page(self):
        meta = dict(date="2026-11-04", batch="2026-11-04", range="31d26eeb6..zeron/main",
                    mirror="ab189159f", target="main @ 1234567", new=4, review=2)
        groups = [("zeron#1089: Folded bubbles", [item("636b16516" + "0" * 31, "Fold | bubbles")]),
                  ("No upstream PR", [item("1295d5773" + "0" * 31, "Access modes", translate="REAL 3x/5r")])]
        lane = [item("deadbeef0" + "0" * 31, "iOS: composer", translate="-", details="ios")]
        skipped = [item("ab189159f" + "0" * 31, "Bump version to v0.2.128", details="version-bump")]
        return triage.render_page(meta, groups, lane, skipped)

    def test_round_trip(self):
        page = self.page()
        batch, rows = triage.parse_page(page)
        self.assertEqual(batch, "2026-11-04")
        self.assertEqual([r[0] for r in rows], ["636b16516", "1295d5773", "deadbeef0"])
        self.assertTrue(all(r[1] == "" for r in rows))
        self.assertIn("Fold \\| bubbles", page)  # pipes in subjects are escaped

    def test_marked_page(self):
        page = self.page()
        page = page.replace("|  | `636b16516` |", "| take | `636b16516` |")
        page = page.replace("|  | `1295d5773` |", "| skip: needs Muse | `1295d5773` |")
        lines = page.splitlines()
        lines = [ln[:-2] + " iPhone only |" if "`deadbeef0`" in ln else ln for ln in lines]
        lines = [ln.replace("|  | `deadbeef0`", "| later | `deadbeef0`") for ln in lines]
        _, rows = triage.parse_page("\n".join(lines))
        self.assertEqual(rows, [("636b16516", "take", ""), ("1295d5773", "skip: needs Muse", ""),
                                ("deadbeef0", "later", "iPhone only")])

    def test_decisions(self):
        d = triage.decision_status
        self.assertIsNone(d("", "b"))
        self.assertEqual(d("take", "2026-11-04"), "queued 2026-11-04")
        self.assertEqual(d("Take", "w45"), "queued w45")
        self.assertEqual(d("skip", "b"), "skipped:user")
        self.assertEqual(d("skip: needs Muse", "b"), "skipped:needs-muse")
        self.assertEqual(d("skip:cloud", "b"), "skipped:cloud")
        self.assertEqual(d("decline", "b"), "declined")
        self.assertEqual(d("later", "b"), "later")
        self.assertEqual(d("ported", "b"), "ported")
        self.assertEqual(d("ported 9d6669f6851248808e", "b"), "ported 9d6669f68")
        self.assertEqual(d("superseded", "b"), "superseded")
        self.assertEqual(d("ticketed wave10-27", "b"), "ticketed wave10-27")
        with self.assertRaises(ValueError):
            d("maybe", "b")


if __name__ == "__main__":
    unittest.main()
