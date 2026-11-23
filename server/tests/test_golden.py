"""Gate 1 (golden, byte for byte) and gate 2 (twice-run determinism) for every offline scan."""

import json
import unittest

from helpers import load_json  # noqa: F401  (sets sys.path)

from suprevolt import scans as S
from suprevolt import selftest as T
from suprevolt.util import sha256_hex


class GoldenTest(unittest.TestCase):
    def test_every_golden_matches_byte_for_byte(self):
        cases = T.cases()
        self.assertGreaterEqual(len(cases), 15)
        for scan, name in cases:
            with self.subTest(case=f"{scan}/{name}"):
                with open(T.case_paths(scan, name)["expected"], encoding="utf-8") as f:
                    self.assertEqual(T.canonical_case(scan, name), f.read())

    def test_every_offline_scan_has_a_golden(self):
        have = {s for s, _ in T.cases()}
        for m in S.CATALOG:
            if m.DETERMINISTIC:
                self.assertIn(m.ID, have, f"{m.ID} has no golden case")

    def test_run_twice_in_process_and_fresh_with_two_hash_seeds(self):
        for scan, name in T.cases():
            with self.subTest(case=f"{scan}/{name}"):
                a = sha256_hex(T.canonical_case(scan, name).encode())
                b = sha256_hex(T.canonical_case(scan, name).encode())
                c = T.fresh_hash(scan, name, "0")
                d = T.fresh_hash(scan, name, "1")
                self.assertEqual({a}, {b, c, d})


def out(scan, name):
    return json.loads(T.canonical_case(scan, name))


class GoldenValuesFromTheDocTest(unittest.TestCase):
    """The values in SUPREVOLT-ARCHITECTURE.md section 4.2, checked field by field."""

    def test_dna_sequence_atcg(self):
        r = out("dna.sequence", "atcg12")
        o = r["output"]
        self.assertEqual([c["bases"] for c in o["codons"]], ["ATC", "GAT", "CGA", "TCG"])
        self.assertEqual([c["start"] for c in o["codons"]], [0, 3, 6, 9])
        self.assertEqual(o["gc_content"], 0.5)
        self.assertTrue(o["valid"])
        self.assertEqual(r["confidence"], 256)

    def test_dna_sequence_atcgx(self):
        o = out("dna.sequence", "atcgx")["output"]
        self.assertEqual(o["lexer_errors"], [{"base": "X", "index": 4}])
        self.assertEqual(o["gc_content"], 0.5)
        self.assertFalse(o["valid"])

    def test_dna_binary_hi(self):
        o = out("dna.binary", "hi")["output"]
        self.assertEqual(o["dna"], "TACATCCT")
        self.assertTrue(o["round_trip"])
        self.assertEqual(o["gc_content"], 0.375)

    def test_refix_unresolved_dependency_keeps_the_trailing_period_quirk(self):
        r = out("refix.gradle", "unresolved_dependency")
        hits = r["output"]["hits"]
        self.assertEqual(len(hits), 1)
        h = hits[0]
        self.assertEqual(h["signature_id"], "gradle.unresolved_dependency")
        self.assertEqual(h["fixture_form"], "SEARCH_MAVEN_CENTRAL:com.squareup.okhttp3:okhttp")
        # The signature regex swallows the sentence's period. Pinned until the regex is fixed.
        self.assertEqual(h["captures"]["version"], "9.9.9.")
        self.assertEqual(h["suspect"], ["version"])
        self.assertEqual(r["confidence"], 192)

    def test_refix_build_successful(self):
        r = out("refix.gradle", "build_successful")
        self.assertEqual(r["output"]["hits"], [])
        self.assertEqual((r["value"], r["confidence"]), (0, 224))

    def test_edapt_missing_colon(self):
        o = out("code.edapt", "missing_colon")["output"]
        self.assertEqual(len(o["fixes"]), 1)
        self.assertEqual(o["fixes"][0]["line"], 1)
        self.assertEqual(o["fixes"][0]["reason"],
                         "if/elif/else/for/while/def/class/try/except/finally headers must end in a colon")
        self.assertEqual(o["code"], "def f(x):\n    return x\n")

    def test_edapt_clean_kotlin(self):
        o = out("code.edapt", "clean_kotlin")["output"]
        self.assertEqual((o["fixes"], o["bracket_problems"]), ([], []))

    def test_ever_run_weekly_sales(self):
        r = out("ever.run", "weekly_sales")
        shown = {s["name"]: s["line"] for s in r["output"]["shown"]}
        self.assertEqual(shown["total"], "total = z [0/256]")
        self.assertEqual(shown["best"], "best = 610 [256/256]")
        self.assertEqual(shown["third_day"], "third_day = 380 [256/256]")
        self.assertEqual(r["state"], "unknown")

    def test_ever_run_weekly_sales_with_thursday(self):
        r = out("ever.run", "weekly_sales_thursday")
        shown = {s["name"]: s for s in r["output"]["shown"]}
        self.assertEqual((shown["total"]["value"], shown["total"]["confidence"]), ("2615", 256))

    def test_ever_heal_stray_semicolon(self):
        r = out("ever.heal", "stray_semicolon")
        c = r["output"]["corrections"]
        self.assertEqual(len(c), 1)
        self.assertEqual(c[0]["name"], "stray_semicolon")
        self.assertTrue(r["output"]["parsed"])

    def test_ever_heal_healed_source_runs(self):
        from suprevolt.scans.ever_run import execute
        with open(T.case_paths("ever.heal", "stray_semicolon")["in"], encoding="utf-8") as f:
            res = execute(f.read())
        self.assertTrue(res["ok"], res)
        self.assertIn("after_2_years = 11236 [256/256]", [s["line"] for s in res["shown"]])

    def test_accord_classify(self):
        r = out("accord.check", "classify")
        self.assertTrue(r["output"]["accepted"])
        # TAC sha256 pinned on first run (vendored ClaudRick commit 0532507)
        self.assertEqual(r["output"]["functions"][0]["tac_sha256"],
                         "8408b3f87ef145b59a471e641567e153662ca28a324d7997bfba3c68d0e80242")

    def test_fineprint_lexicon_d01(self):
        r = out("fineprint.lexicon", "D01-gym-membership")
        phrases = {(h["subtype"], h["phrase"]) for h in r["output"]["hits"]}
        self.assertIn(("auto_renewal", "renews automatically"), phrases)

    def test_fineprint_lexicon_controls_are_clear(self):
        for name in ("C01-library-newsletter", "C02-fair-refund-policy"):
            r = out("fineprint.lexicon", name)
            self.assertFalse([h for h in r["output"]["hits"] if h["severity"] in ("high", "critical")])
            self.assertLess(r["value"], 25)

    def test_fingerprint(self):
        o = out("blob.fingerprint", "atcg12")["output"]
        self.assertEqual(o["sha256"], "0bede8e29d425bf8b40864b197132d67655166023c8c6b88f03489733d0bca4d")
        self.assertEqual((o["type"], o["lines"]), ("text/plain", 1))


if __name__ == "__main__":
    unittest.main()
