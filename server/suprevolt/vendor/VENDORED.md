# Vendored code (SupRevolt v1)

Frozen copies of the repository code the scans import, so "same input, same output" survives edits
to the live repositories. Every file below is a **byte-identical copy** (the sha256 is of the copied
file and of the source at the listed commit). Nothing in a vendored file was changed. The few
`__init__.py` files that only say "SupRevolt vendor package marker" were added by SupRevolt so the
copies can be imported as packages; they are not part of any source.

A scan's VERSION includes a hash of the vendored files it uses (see `scans/__init__.py`), so
re-vendoring a newer commit is automatically a new cache key.

Source repositories (read-only clones; nothing in them was modified):

| Repo | Commit |
|---|---|
| ClaudRick | `053250785c0a32712a8de2858650b3f132046c1d` |
| everlang-dpl | `1b794420b3ef54ecd9ed1c4e12e63dd74151f8d1` |
| Revision | `89f110b99cad9a892125487d5bee07e0ceede3a9` (surveyed only; nothing vendored: `Revision/app` is the stock Gemini sample) |

## Licenses

- everlang-dpl: MIT, `LICENSE` (Copyright (c) 2026 Zdycomp) and `ezr/LICENSE` (Copyright (c) 2026
  Codric Enterprise), both copied into `licenses/`.
- ClaudRick: MIT, declared in `pyproject.toml`, `everconf/pyproject.toml` and `refix2/package.json`
  (the repository has no LICENSE file to copy).
- None of the vendored source files carries a per-file license header; each keeps its original
  module docstring and attribution text unchanged.

## Files

| Vendored path | Source | sha256 |
|---|---|---|
| `everconf/__init__.py` | ClaudRick `everconf/everconf/__init__.py` @ `053250785c0a` | `eb092757123a3ab5276ac3f072ebc184d43a8b60bf2830cbf66ac05e4662dfa2` |
| `refix/gradle.json` | ClaudRick `refix2/signatures/gradle.json` @ `053250785c0a` | `5e5a4e63fecaa42e03fa271c82ce46cd72617f9d088f63872aed7808ff7bdee7` |
| `accord/accord.py` | ClaudRick `accord/accord.py` @ `053250785c0a` | `e4de9c00c4274f3ce350c4438faec29a54120f62d44da828a2403eb0ec35d1c6` |
| `accord/core.py` | ClaudRick `accord/core.py` @ `053250785c0a` | `9c03b3b63473a5f3757b274d5b2349ffcb7bf79b73c986911de6072be117ea97` |
| `accord/parse.py` | ClaudRick `accord/parse.py` @ `053250785c0a` | `affd94a1053f71fa9140462e3400903e53f58e03756af12a5a3c21c896c5499a` |
| `revision/ratelimit.py` | ClaudRick `src/revision/ratelimit.py` @ `053250785c0a` | `c2eb3b59c87a2aa929aab7032e67d406939f40e9ba51a60a3e1352f6e6160a31` |
| `licenses/everlang-dpl-LICENSE` | everlang-dpl `LICENSE` @ `1b794420b3ef` | `e168c33886b9f639bfb47a0b01423ab6e1a23ae26c7acfbd1137d0b2830a8113` |
| `licenses/ezr-LICENSE` | everlang-dpl `ezr/LICENSE` @ `1b794420b3ef` | `7e4c31956423068def1c99754d5fb4f5a876dc8b074df4d9a2cb0c76380f292c` |
| `everlang/biocomputing/sequencer.py` | everlang-dpl `everlang_standalone/everlang/biocomputing/sequencer.py` @ `1b794420b3ef` | `8b7f2764bbfb8e5452bdcda1b343f0e44076724985a4eab6d271587b4d39c0bd` |
| `everlang/biocomputing/quaternary.py` | everlang-dpl `everlang_standalone/everlang/biocomputing/quaternary.py` @ `1b794420b3ef` | `a69650dae15abf7d72835b681a81f758f47a49ea8ec30a3d9e4dcda2263a13c9` |
| `everlang/biocomputing/binary_sequencer.py` | everlang-dpl `everlang_standalone/everlang/biocomputing/binary_sequencer.py` @ `1b794420b3ef` | `a707ee88b8a40d4d6e8ab5e2b43f623714fdee4b9c286514ba8adff3948dbff2` |
| `everlang/biocomputing/dna_engine.py` | everlang-dpl `everlang_standalone/everlang/biocomputing/dna_engine.py` @ `1b794420b3ef` | `1a21509607cea51478167f4c15e189922b2f46f976e122dbf4d28ba0b051142b` |
| `everlang/core/particle.py` | everlang-dpl `everlang_standalone/everlang/core/particle.py` @ `1b794420b3ef` | `563e1f02346424137f5629105d12c877429eaa980e883e072e6e7d9e0ffffd9a` |
| `ezr/edapt/__init__.py` | everlang-dpl `ezr/edapt/__init__.py` @ `1b794420b3ef` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `ezr/edapt/fixers.py` | everlang-dpl `ezr/edapt/fixers.py` @ `1b794420b3ef` | `89109cd31e69663372932760b243b4017bdfe1bb9c5517db7750f2840dec8453` |
| `ezr/interp/syntax.py` | everlang-dpl `ezr/2-interpreter-python/syntax.py` @ `1b794420b3ef` | `78fe3ae366f5080a50e97e10bb6b68e89d72d01cc4923503badbe0823b40fb49` |
| `ezr/interp/forgive.py` | everlang-dpl `ezr/2-interpreter-python/forgive.py` @ `1b794420b3ef` | `41a6b5f74172f2db8b41fd95dbc543e23e1b20dce26d90621a67b1ded8017e17` |
| `ezr/interp/ever.py` | everlang-dpl `ezr/2-interpreter-python/ever.py` @ `1b794420b3ef` | `49b611e12d7bf9bee2a3fd06efde7f6e139aea5d06c196193ccc26933ecddb32` |
| `ezr/interp/runtime.py` | everlang-dpl `ezr/2-interpreter-python/runtime.py` @ `1b794420b3ef` | `a0f702cb33e48b08e361bf1951e24611cb0aaa91700f05ee1e6ff923df690a8c` |
| `ezr/interp/repair_archive.py` | everlang-dpl `ezr/2-interpreter-python/repair_archive.py` @ `1b794420b3ef` | `c55d6c7455ccc3b46a6dbf85a2ea1b5e523977333ef433d26c4ec6de2eebffb7` |
| `ezr/interp/ir.py` | everlang-dpl `ezr/2-interpreter-python/ir.py` @ `1b794420b3ef` | `5daad2c64de1748e6ace9449d9912696a2b2a8c388e2078e141a6f2b763b9906` |
| `ezr/interp/scope.py` | everlang-dpl `ezr/2-interpreter-python/scope.py` @ `1b794420b3ef` | `e04ae48f302a148150e65857eda386b4071f2528baf0e0b7d2943c16239d5c46` |
| `ezr/interp/evalue.py` | everlang-dpl `ezr/2-interpreter-python/evalue.py` @ `1b794420b3ef` | `43816784dc7d91674bc7f01fb8939e53449fe774b460a62819a46672c5976bbc` |
| `ezr/interp/ffi.py` | everlang-dpl `ezr/2-interpreter-python/ffi.py` @ `1b794420b3ef` | `c5e1559b33c2a59b36142b4d6fa6239bcfe35bbcdfb657e100f091bb34cdf778` |
| `ezr/interp/builtins_ml.py` | everlang-dpl `ezr/2-interpreter-python/builtins_ml.py` @ `1b794420b3ef` | `92d3f727c20d3cc72872dab007279198f073962e93aa0cc7a8e9229be8772a00` |
| `ezr/interp/profiler.py` | everlang-dpl `ezr/2-interpreter-python/profiler.py` @ `1b794420b3ef` | `a7128a3a8127f69d857f36dbe1c4eecfc0992f5bd40bfd2b65d2b6b69983ec9b` |

## Ported, not copied

| SupRevolt file | Ported from | Notes |
|---|---|---|
| `scans/refix_gradle.py` | ClaudRick `refix2/src/match.js` `scan()` + `_interp()` | Python port with JS regex semantics (`m` flag, `\d` = [0-9], unmatched group = "undefined"). Reads the vendored `refix/gradle.json`. `apply`/`revert` are never ported. Parity test against `node` + the clone (dev only, skipped when absent). |
| `ai.py` | ClaudRick `src/revision/anthropic_client.py` `AnthropicClient` | stdlib urllib client rewritten with a Gemini twin and an injectable transport; redaction gate ported from `src/revision/static/index.html` `runRedactionGate()`. |
| `prompts/fineprint.v1.txt` | ClaudRick `src/revision/static/index.html`, Fine Print Analyzer prompt | verbatim (`${text}` is the placeholder). |
| `prompts/jargonary.v1.txt` | same file, Jargonary prompt | "plain" level text filled in; four Revisor fields added (tone, sarcasm, intent, manipulation_risk). |
| `scans/ever_runner.py` | everlang-dpl `ezr/2-interpreter-python/ever_cli.py` `cmd_run` | runs the vendored `runtime.run_source()` without the ~/.ever profile, without the repair-archive database, with `extern` refused. |
| `scans/code_edapt.py` `brackets()` | everlang-dpl `ezr/edapt/fixers.py` `check_brackets()` | same rule, built on edapt's own `_mask`, `_SYNTAX`, `_PAIRS` (see findings). |

## Test data copied from the repositories

| SupRevolt path | Source | Change | sha256 |
|---|---|---|---|
| `suprevolt/golden/ever.run/weekly_sales.in` | everlang-dpl `ezr/examples/weekly_sales.ever` | copied | `4eb39da5cfcc46bd4d5b1a50975500afef2862b1c06186a9a6edfbff0a7f8b0a` |
| `suprevolt/golden/ever.run/weekly_sales_thursday.in` | everlang-dpl `ezr/examples/weekly_sales.ever` | Thursday set to 500 (README: total 2615) | `b9c15bf1e969cac0679e11a808d6f114097e3c4bb68371a04d711dfd8741e093` |
| `suprevolt/golden/ever.heal/stray_semicolon.in` | everlang-dpl `ezr/examples/compound_interest.ever` | one `;` added on line 12 | `f51fe2d5b94017e8a6d31bc71f30c57fc99202619f420b4cb67c79e8d5618232` |
| `suprevolt/golden/accord.check/classify.in` | ClaudRick `accord/examples/classify.accord` | copied | `49f03d7d3bdf1ed5fe303da3955a81a138d609b44568e04bb8c527881fbcb4df` |
| `suprevolt/golden/fineprint.lexicon/D01-gym-membership.in` | ClaudRick `tests/corpora/analyzer/D01-gym-membership.docx` | copied | `7bfa0aa942acedff419c35a2d3a774aec94db953bdbef68c8ed45ffdfc8d6312` |
| `suprevolt/golden/fineprint.lexicon/C01-library-newsletter.in` | ClaudRick `tests/corpora/analyzer/C01-library-newsletter.docx` | copied | `ec74a12250ce7f5cc1c1ddd51e10ae21a67cd7a20784da0d5c2a7014a483cd6e` |
| `suprevolt/golden/fineprint.lexicon/C02-fair-refund-policy.in` | ClaudRick `tests/corpora/analyzer/C02-fair-refund-policy.docx` | copied | `0bef58f70be2fdb7c7ceebf910306033c76e11a853f514c5fe6a0d53080b6c49` |
| `tests/data/analyzer-ground-truth.json` | ClaudRick `tests/corpora/analyzer/ground-truth.json` | copied | `b183f6edefaf732ccf0d607c509be09daecc2d6a4a7564847fb76db51e89c93c` |
| `tests/data/C01-library-newsletter.docx` | ClaudRick `tests/corpora/analyzer/C01-library-newsletter.docx` | copied | `ec74a12250ce7f5cc1c1ddd51e10ae21a67cd7a20784da0d5c2a7014a483cd6e` |
| `tests/data/C02-fair-refund-policy.docx` | ClaudRick `tests/corpora/analyzer/C02-fair-refund-policy.docx` | copied | `0bef58f70be2fdb7c7ceebf910306033c76e11a853f514c5fe6a0d53080b6c49` |
| `tests/data/D01-gym-membership.docx` | ClaudRick `tests/corpora/analyzer/D01-gym-membership.docx` | copied | `7bfa0aa942acedff419c35a2d3a774aec94db953bdbef68c8ed45ffdfc8d6312` |
| `tests/data/D02-free-trial-saas.docx` | ClaudRick `tests/corpora/analyzer/D02-free-trial-saas.docx` | copied | `166d19013027e0cf5068198f61a5505948a5ae221e0aff86d8618169e0bb4b9c` |
| `tests/data/D03-payday-loan.docx` | ClaudRick `tests/corpora/analyzer/D03-payday-loan.docx` | copied | `1b950f942912dc1fab5d816a6e41aca60d88177dda365368882a9a4cf47f706d` |
| `tests/data/D04-timeshare.docx` | ClaudRick `tests/corpora/analyzer/D04-timeshare.docx` | copied | `72de918abfbeda01d1dad5403c2b28bbe4a5a4855ba1cdcb8c8f2a969bd1db7c` |
| `tests/data/D05-subscription-box.docx` | ClaudRick `tests/corpora/analyzer/D05-subscription-box.docx` | copied | `9db02840ad5b58b12f20e77e3640ac2ee8966052e6f6160f643691e29d6067ee` |
| `tests/data/D06-course-upsell.docx` | ClaudRick `tests/corpora/analyzer/D06-course-upsell.docx` | copied | `12c172fe606c2cac23405c1344a95b548fb9e8ddc606da8e1e61ed84be81e5de` |
| `tests/data/D07-apartment-lease.docx` | ClaudRick `tests/corpora/analyzer/D07-apartment-lease.docx` | copied | `ce4daca17c364cbd8636ae9fa8ea10d4ba77031e620a9cd7a34beb7966871952` |
| `tests/data/D08-crypto-pitch.docx` | ClaudRick `tests/corpora/analyzer/D08-crypto-pitch.docx` | copied | `581744bdb1d7fd335595ce24f958e97e1811859d9264f3294445c611be5ee1d1` |

## Findings in the source repositories (not fixed there: the clones are read-only)

1. **ReFix regex swallows a trailing period.** `gradle.unresolved_dependency` matches the version
   with `([a-zA-Z0-9._+-]+)`, so `Could not resolve com.squareup.okhttp3:okhttp:9.9.9.` captures
   version `9.9.9.` and the fixture text repeats it. The port keeps the bug on purpose (parity with
   `match.js`), flags the capture in `suspect`, and drops that hit to 192/256. The golden
   `golden/refix.gradle/unresolved_dependency` pins it. Not fixed in the vendored copy (the
   architecture doc does not ask for that); when the signature is fixed upstream and re-vendored,
   update that golden to 256.
2. **edapt is mid-refactor at this commit.** `diagnose_brackets` does not exist, although
   `ezr/edapt/cli.py` and `everlang/core/heal.py` import it (both fail); `check_brackets` calls a
   missing `_mask_strings_and_comments`; the JavaScript, Rust and Java fixers raise
   `NameError: _fix_semicolons`. `code.edapt` therefore accepts only languages whose fixer runs
   (probed at start: Python, Go, Kotlin) and checks bracket balance itself with edapt's `_mask`.
   Re-vendoring a fixed edapt turns the other languages back on with no code change.
3. **Ever's runtime writes outside the program.** `ever_cli.py run` writes `~/.ever/profile.json`
   and `runtime.run_source()` opens `ezr/4-archive-sql/tapestry.db` next to the code to archive
   repairs. SupRevolt's runner turns both off (and refuses `extern`, which loads native libraries).
