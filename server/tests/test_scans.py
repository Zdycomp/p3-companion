"""Scans, verdict, cache keys, AI parsing with recorded replies, and the ever.run sandbox."""

import json
import os
import shutil
import subprocess
import tempfile
import unittest

from helpers import CLAUDRICK, DATA, cfg, item, load_json, read

from suprevolt import ai
from suprevolt.config import AiConfig
from suprevolt.loop import Engine, job_id_for, prepare
from suprevolt.scans import BY_ID, CATALOG, ever_run, fineprint_ai, fineprint_lexicon, refix_gradle
from suprevolt.util import docx_text, sha256_hex
from suprevolt.verdict import percent, verdict

PINNED_LEXICON_RECALL = 34          # of 34 planted phrases in the ReVision analyzer corpus (v1 table)


class LexiconTest(unittest.TestCase):
    def test_recall_against_ground_truth_is_at_least_the_pinned_number(self):
        gt = load_json(os.path.join(DATA, "analyzer-ground-truth.json"))
        found = total = 0
        for doc in gt["documents"]:
            text = docx_text(read(os.path.join(DATA, doc["file"])))
            hits = fineprint_lexicon.find(text)
            for p in doc["planted"]:
                total += 1
                at = text.replace("’", "'").find(p["phrase"].replace("’", "'"))
                self.assertGreaterEqual(at, 0, p["phrase"])
                end = at + len(p["phrase"])
                found += any(h["category"] == p["category"] and h["offset"] < end
                             and at < h["offset"] + len(h["phrase"]) for h in hits)
        self.assertEqual(total, 34)
        self.assertGreaterEqual(found, PINNED_LEXICON_RECALL)

    def test_negation_guard(self):
        self.assertEqual(fineprint_lexicon.find("There are no restocking fees."), [])
        self.assertTrue(fineprint_lexicon.find("A restocking fee applies."))

    def test_risk_is_exact_and_bounded(self):
        self.assertEqual(fineprint_lexicon.risk_of([]), 0)
        self.assertEqual(fineprint_lexicon.risk_of([{"severity": "critical"}]), 50)
        self.assertEqual(fineprint_lexicon.risk_of([{"severity": "critical"}] * 20), 100)


class RefixTest(unittest.TestCase):
    def test_js_regex_translation(self):
        self.assertEqual(refix_gradle.js_to_py(r"(\d+)"), r"([0-9]+)")
        self.assertEqual(refix_gradle.js_to_py(r"[\d\.]+"), r"[0-9\.]+")

    def test_unmatched_group_interpolates_as_undefined_like_javascript(self):
        self.assertEqual(refix_gradle._interp("{a}-{b}-{c}", {"a": "x", "b": None}), "x-undefined-{c}")

    def test_trailing_period_bug_is_flagged_not_fixed(self):
        r = BY_ID["refix.gradle"].run(item("FAILURE: Build failed\n> Could not resolve a.b:c:1.0.\n"), cfg())
        h = r.output["hits"][0]
        self.assertEqual(h["captures"]["version"], "1.0.")
        self.assertEqual(r.confidence, 192)
        clean = BY_ID["refix.gradle"].run(item("FAILURE: Build failed\n> Could not resolve a.b:c:1.0\n"), cfg())
        self.assertEqual(clean.confidence, 256)

    @unittest.skipUnless(shutil.which("node") and os.path.isdir(os.path.join(CLAUDRICK, "refix2")),
                         "dev only: needs Node and the ClaudRick clone")
    def test_parity_with_the_real_match_js(self):
        demo = os.path.join(CLAUDRICK, "refix2", "demo")
        logs = [os.path.join(demo, f) for f in sorted(os.listdir(demo))]
        js = ("const {loadSignatures,scan}=require(process.argv[1]+'/src/match.js');"
              "const fs=require('fs');const s=loadSignatures(process.argv[1]+'/signatures/gradle.json');"
              "console.log(JSON.stringify(process.argv.slice(2).map(f=>scan(fs.readFileSync(f,'utf8'),s))));")
        r = subprocess.run(["node", "-e", js, os.path.join(CLAUDRICK, "refix2"), *logs],
                           capture_output=True, timeout=60, check=True)
        want = json.loads(r.stdout)
        sigs = refix_gradle.load_signatures()
        for log, w in zip(logs, want):
            got = refix_gradle.scan(read(log, "r"), sigs)
            for g in got:
                g.pop("_caps_all")
            self.assertEqual(got, w, log)


class AcceptsTest(unittest.TestCase):
    def accepting(self, it, c=None):
        c = c or cfg()
        return [m.ID for m in CATALOG if m.accepts(it, c)]

    def test_kinds(self):
        self.assertEqual(self.accepting(item("ACGTACGTACGTAAAC\n")), ["blob.fingerprint", "dna.sequence", "dna.binary"])
        self.assertIn("code.edapt", self.accepting(item("def f(x)\n", "a.py")))
        self.assertNotIn("code.edapt", self.accepting(item("let x = 1\n", "a.js")))  # unfinished fixer upstream
        self.assertIn("ever.heal", self.accepting(item("let x = 1\n", "x.ever")))
        self.assertIn("accord.check", self.accepting(item("x", "a.accord")))
        self.assertIn("refix.gradle", self.accepting(item("FAILURE: Build failed\n")))
        prose = self.accepting(item("This offer renews automatically."))
        self.assertEqual([s for s in prose if "." in s and s.split(".")[0] in ("fineprint", "jargonary")],
                         ["fineprint.lexicon", "fineprint.ai", "jargonary.ai"])
        png = b"\x89PNG\r\n\x1a\n" + b"\0\0\0\rIHDR" + (4).to_bytes(4, "big") * 2 + b"\x08\x02\0\0\0"
        self.assertEqual(self.accepting(item(png)), ["blob.fingerprint", "dna.binary", "image.read"])

    def test_dna_binary_cap(self):
        self.assertNotIn("dna.binary", self.accepting(item(b"\x01" * (64 * 1024 + 1))))
        self.assertNotIn("dna.binary", self.accepting(item(b"\x01" * (16 * 1024 + 1)), cfg(profile="render")))


class VerdictTest(unittest.TestCase):
    def r(self, scan, state, value, conf):
        return {"scan": scan, "state": state, "value": value, "confidence": conf, "note": "x"}

    def test_unknown_poisons(self):
        v = verdict([self.r("a", "ok", 10, 256), self.r("fineprint.ai", "unknown", None, 0)])
        self.assertEqual(v["level"], "UNKNOWN")
        self.assertIn("fineprint.ai: x", v["reasons"])
        v = verdict([self.r("a", "ok", 10, 256), self.r("b", "error", None, 0)])
        self.assertEqual(v["level"], "UNKNOWN")

    def test_n_a_does_not_poison(self):
        v = verdict([self.r("a", "ok", 10, 256), self.r("b", "n/a", None, None)])
        self.assertEqual((v["level"], v["risk"], v["confidence"]), ("CLEAR", 10, 256))

    def test_levels_and_unconfirmed(self):
        self.assertEqual(verdict([self.r("a", "ok", 30, 200)])["level"], "CAUTION")
        self.assertEqual(verdict([self.r("a", "ok", 60, 200)])["level"], "DANGER")
        self.assertEqual(verdict([self.r("a", "ok", 80, 120), self.r("b", "ok", 0, 256)])["level"],
                         "DANGER_UNCONFIRMED")

    def test_percent_from_the_same_integer(self):
        self.assertEqual(percent(218), 85)
        self.assertEqual(percent(256), 100)
        self.assertEqual(percent(0), 0)


class CacheKeyTest(unittest.TestCase):
    def test_crlf_and_lf_are_one_blob_and_one_job(self):
        a, _ = prepare(b"line one\r\nline two\r\n")
        b, _ = prepare(b"line one\nline two\n")
        self.assertEqual(a, b)
        ja = job_id_for(sha256_hex(a), ["all"], "", "")
        self.assertEqual(ja, job_id_for(sha256_hex(b), ["all"], "", ""))
        c, _ = prepare(b"line one\nline two\n!")
        self.assertNotEqual(ja, job_id_for(sha256_hex(c), ["all"], "", ""))

    def test_requested_order_does_not_matter(self):
        self.assertEqual(job_id_for("x", ["fineprint.lexicon", "blob.fingerprint"], "", ""),
                         job_id_for("x", ["blob.fingerprint", "fineprint.lexicon"], "", ""))

    def test_ai_model_change_is_a_new_cache_key(self):
        it = item("Some text.")
        k1 = fineprint_ai.cfg_key(it, cfg(ai=AiConfig(anthropic_key="k", claude_model="m1")))
        k2 = fineprint_ai.cfg_key(it, cfg(ai=AiConfig(anthropic_key="k", claude_model="m2")))
        self.assertNotEqual(k1, k2)
        self.assertNotIn("k", json.dumps(k1).replace("key", ""))   # the key itself is never in a cache key


class EngineTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.engine = Engine(cfg(data_dir=self.dir))

    def tearDown(self):
        self.engine.store.db.close()
        shutil.rmtree(self.dir, ignore_errors=True)

    def run_text(self, text, scans, filename=""):
        data, kind = prepare(text.encode())
        job = self.engine.submit(data, kind, scans, filename)
        self.engine.run_job(self.engine.store.next_job())
        return self.engine.job_view(self.engine.store.job(job["id"]))

    def test_fixed_catalog_order_and_cache(self):
        v = self.run_text("Your plan renews automatically each year.", ["fineprint.lexicon", "blob.fingerprint"])
        self.assertEqual([r["scan"] for r in v["results"]], ["blob.fingerprint", "fineprint.lexicon"])
        self.assertFalse(any(r["cached"] for r in v["results"]))
        self.assertEqual(v["verdict"]["level"], "CAUTION")
        v2 = self.run_text("Your plan renews automatically each year.", ["fineprint.lexicon"])
        self.assertTrue(v2["results"][0]["cached"])
        self.assertEqual(v2["results"][0]["output"], v["results"][1]["output"])

    def test_all_on_prose_without_a_key_is_unknown_and_says_why(self):
        v = self.run_text("Hello there, this is a friendly note.", ["all"])
        self.assertEqual(v["verdict"]["level"], "UNKNOWN")
        self.assertTrue(any(r.startswith("fineprint.ai: no AI key") for r in v["verdict"]["reasons"]))
        states = {r["scan"]: r["state"] for r in v["results"]}
        self.assertEqual(states["refix.gradle"], "n/a")
        self.assertEqual(states["fineprint.ai"], "unknown")

    def test_only_n_a_skips_do_not_poison(self):
        v = self.run_text("ACGTACGTACGTACGT", ["all"])
        self.assertNotEqual(v["verdict"]["level"], "UNKNOWN")
        self.assertIn("n/a", {r["state"] for r in v["results"]})

    def test_sweep_audits_and_flags_a_broken_cache(self):
        self.run_text("ACGTACGTACGTACGT", ["dna.sequence"])
        rep = self.engine.sweep()
        self.assertEqual((rep["audited"], rep["mismatched"]), (1, 0))
        self.assertEqual(self.engine.health()["determinism"], "ok")
        self.engine.store.db.execute("UPDATE results SET json='{\"tampered\":1}'")
        rep = self.engine.sweep()
        self.assertEqual(rep["mismatched"], 1)
        self.assertEqual(self.engine.health()["determinism"], "broken")
        self.assertEqual(self.engine.health()["broken_scans"], ["dna.sequence"])
        v = self.run_text("ACGTACGTACGTACGT", ["dna.sequence", "blob.fingerprint"])
        self.assertFalse([r for r in v["results"] if r["scan"] == "dna.sequence"][0]["cached"])

    def test_image_text_becomes_a_child_blob(self):
        replies = [json.dumps({"content": [{"type": "text", "text": json.dumps(
            {"text": "This plan renews automatically each year.", "language": "English",
             "translation": "This plan renews automatically each year."})}], "stop_reason": "end_turn"})]
        self.engine.cfg.ai = AiConfig(anthropic_key="test", transport=fake(replies, also_fineprint=True))
        png = b"\x89PNG\r\n\x1a\n" + b"\0\0\0\rIHDR" + (4).to_bytes(4, "big") * 2 + b"\x08\x02\0\0\0"
        job = self.engine.submit(png, "image/png", ["all"])
        self.engine.run_job(self.engine.store.next_job())
        v = self.engine.job_view(self.engine.store.job(job["id"]))
        children = [r for r in v["results"] if r["parent"]]
        self.assertEqual([r["scan"] for r in children], ["fineprint.lexicon", "fineprint.ai", "jargonary.ai"])
        self.assertEqual(children[0]["state"], "ok")
        self.assertGreater(children[0]["value"], 0)


def fake(replies, also_fineprint=False, status=200):
    """A recorded transport: answers image.read with the stored reply, other prompts by their kind."""
    calls = []

    def transport(url, headers, body, timeout):
        calls.append((url, headers, json.loads(body)))
        text = json.dumps(json.loads(body))
        if "Read all the text" in text:
            return status, replies[0].encode()
        if "Jargonary" in text:
            return status, claude_reply({"simplified_text": "s", "jargon_glossary": [], "key_takeaway": "k",
                                         "tone": "pushy", "sarcasm": False, "intent": "i", "manipulation_risk": 40})
        return status, claude_reply({"overall_risk_score": 70, "hidden_terms_and_costs": {"detections": [
            {"type": "auto_renewal", "phrase": "renews automatically", "severity": "high", "explanation": "e"}]},
            "synthesis": "s"})
    transport.calls = calls
    return transport


def claude_reply(obj) -> bytes:
    return json.dumps({"content": [{"type": "text", "text": "```json\n" + json.dumps(obj) + "\n```"}],
                       "stop_reason": "end_turn"}).encode()


class AiScanTest(unittest.TestCase):
    TEXT = "Your membership renews automatically each year. Experts say you deserve a big boat."

    def test_no_key_is_unknown_with_a_clear_note(self):
        for sid in ("fineprint.ai", "jargonary.ai"):
            r = BY_ID[sid].run(item(self.TEXT), cfg())
            self.assertEqual((r.state, r.confidence), ("unknown", 0))
            self.assertIn("no AI key", r.note)
            self.assertTrue(r.transient)

    def test_fineprint_ai_entry_120_and_corroboration_to_200(self):
        t = fake([])
        c = cfg(ai=AiConfig(anthropic_key="sk-test-not-real", transport=t))
        r = BY_ID["fineprint.ai"].run(item(self.TEXT), c)
        self.assertEqual(r.state, "ok")
        self.assertEqual(r.value, 70)
        self.assertEqual(r.output["flags"][0]["confidence"], 200)   # the lexicon saw the same phrase
        self.assertEqual(r.confidence, 200)
        url, headers, body = t.calls[0]
        self.assertEqual(body["model"], "claude-sonnet-5-5")
        self.assertNotIn("temperature", body)
        self.assertEqual(headers["anthropic-version"], "2026-10-21")

    def test_uncorroborated_ai_flag_stays_at_120(self):
        def t(url, headers, body, timeout):
            return 200, claude_reply({"overall_risk_score": 80, "scare_tactics": {"detections": [
                {"type": "threat", "phrase": "a big boat", "severity": "high"}]}})
        r = BY_ID["fineprint.ai"].run(item(self.TEXT), cfg(ai=AiConfig(anthropic_key="k", transport=t)))
        self.assertEqual(r.confidence, 120)
        self.assertEqual(verdict([{"scan": "fineprint.ai", "state": r.state, "value": r.value,
                                   "confidence": r.confidence, "note": r.note}])["level"], "DANGER_UNCONFIRMED")

    def test_bad_json_and_http_errors_are_unknown(self):
        bad = lambda *a: (200, json.dumps({"content": [{"type": "text", "text": "no json here"}]}).encode())  # noqa: E731
        r = BY_ID["fineprint.ai"].run(item(self.TEXT), cfg(ai=AiConfig(anthropic_key="k", transport=bad)))
        self.assertEqual(r.state, "unknown")
        err = lambda *a: (401, b'{"error": {"message": "invalid x-api-key"}}')  # noqa: E731
        r = BY_ID["jargonary.ai"].run(item(self.TEXT), cfg(ai=AiConfig(anthropic_key="k", transport=err)))
        self.assertEqual(r.state, "unknown")
        self.assertIn("401", r.note)
        refusal = lambda *a: (200, json.dumps({"content": [], "stop_reason": "refusal"}).encode())  # noqa: E731
        r = BY_ID["fineprint.ai"].run(item(self.TEXT), cfg(ai=AiConfig(anthropic_key="k", transport=refusal)))
        self.assertIn("declined", r.note)

    def test_gemini_body_and_key_header(self):
        seen = {}

        def t(url, headers, body, timeout):
            seen.update(url=url, headers=headers, body=json.loads(body))
            return 200, json.dumps({"candidates": [{"content": {"parts": [{"text": json.dumps(
                {"simplified_text": "plain", "jargon_glossary": [{"term": "APR", "plain_meaning": "yearly cost"}],
                 "manipulation_risk": 10})}]}}]}).encode()
        r = BY_ID["jargonary.ai"].run(item(self.TEXT), cfg(ai=AiConfig(gemini_key="g-key", transport=t)))
        self.assertEqual((r.state, r.value, r.confidence), ("ok", 10, 120))
        self.assertTrue(seen["url"].endswith("/gemini-2.5-flash:generateContent"))
        self.assertNotIn("g-key", seen["url"])
        self.assertEqual(seen["headers"]["x-goog-api-key"], "g-key")
        self.assertEqual(seen["body"]["generationConfig"]["temperature"], 0)

    def test_redaction_before_sending(self):
        text = "Card 4111 1111 1111 1111, SSN 123-45-6789, mail me at a@b.com. It renews automatically."
        red, found = ai.redact(text)
        for secret in ("4111 1111 1111 1111", "123-45-6789", "a@b.com"):
            self.assertNotIn(secret, red)
        self.assertEqual({f["category"] for f in found} >= {"Credit/debit card number", "Email address"}, True)

    def test_key_never_in_repr(self):
        self.assertNotIn("sk-secret", repr(AiConfig(anthropic_key="sk-secret")))


class EverRunSandboxTest(unittest.TestCase):
    def setUp(self):
        self.c = cfg(enable_ever_run=True)

    def test_off_by_default(self):
        r = BY_ID["ever.run"].run(item("let x = 1\nshow x\n", "x.ever"), cfg())
        self.assertEqual(r.state, "n/a")
        self.assertIn("SUPREVOLT_ENABLE_EVER_RUN", r.note)

    def test_shell_text_is_only_text(self):
        marker = os.path.join(tempfile.mkdtemp(), "pwned")
        src = f'let a = 1; rm -rf /\nlet b = "$(touch {marker})"\nshow b\n'
        r = BY_ID["ever.run"].run(item(src, "x.ever"), self.c)
        self.assertEqual(r.state, "ok")
        self.assertFalse(os.path.exists(marker))

    def test_infinite_loop_dies_at_5_seconds_as_error(self):
        r = BY_ID["ever.run"].run(item("ever x = for i = 1 to 100000000000 do i * i\nshow x\n", "x.ever"), self.c)
        self.assertEqual(r.state, "error")
        self.assertTrue(r.transient)

    def test_extern_is_refused(self):
        r = BY_ID["ever.run"].run(item('extern cos(x) from "libm.so.6"\nlet y = cos(0)\nshow y\n', "x.ever"), self.c)
        self.assertIn("extern is disabled", json.dumps(r.output))

    def test_argv_is_fixed(self):
        self.assertTrue(os.path.isabs(ever_run.RUNNER))
        self.assertIn('argv = [sys.executable, "-I", RUNNER, path]', read(ever_run.__file__, "r"))


if __name__ == "__main__":
    unittest.main()
