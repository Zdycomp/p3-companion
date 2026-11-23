# SupRevolt: architecture (v1)

Status: design only, no code yet. Owner: Theoretical Physicist. Implementer: polyglot code specialist.
Goal: P3 Companion becomes the front door to one server that runs every scan Rick has built, in a fixed
order, with the same answer for the same input, and a 0 to 256 confidence on every result.

```
glasses photo / picked doc / typed text
   └─ P3 Companion "SupRevolt" screen ──HTTPS + Bearer──► suprevolt server (Python 3.11 stdlib)
                                                         ├─ blob store (sha256-addressed)
                                                         ├─ sqlite: jobs, results, cache
                                                         ├─ job loop: fixed scan order, one tick at a time
                                                         └─ scan modules (in-process / fixed-argv subprocess / AI)
```

---

## 1. Scan catalog

Survey of `/home/user/src/ClaudRick`, `/home/user/src/everlang-dpl`, `/home/user/src/Revision`, plus Rick's
listed ideas. "Det." = same input gives byte-identical output. "Wrap": **IP** = import in-process,
**SP** = subprocess with a fixed argv, **RI** = reimplement in Python, **AI** = owner's key.

| # | Scan id | Source (seen) | Lang | Entry point | In → Out | Offline / Det. | Key | Wrap | v1 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `blob.fingerprint` | new | Py | `scan(blob)` | any → sha256, size, sniffed type, UTF-8 validity, line/word count | yes / yes | no | new | **yes** |
| 2 | `refix.gradle` | `ClaudRick/refix2/src/match.js` `loadSignatures()`, `scan(stderr, signatures)`; corpus `refix2/signatures/gradle.json` (10 signatures) | Node | `refix scan <log>` | build log text → list of `{signature_id, kind, failure_text, fixture_form, fixture_text, captures}` | yes / yes | no | **RI** (port the 20-line `scan()`; read the same `gradle.json`). Never port `apply`/`revert` (they write files). Parity test vs `node bin/refix scan`. | **yes** |
| 3 | `dna.sequence` | `everlang-dpl/everlang_standalone/everlang/biocomputing/sequencer.py` `DnaSequencer.run()` | Py | `run(raw)` | DNA text → codons, pairs, `gc_content`, `lexer_errors`, `parser_errors`, `valid` | yes / yes | no | **IP** (serialize dataclasses to plain JSON) | **yes** |
| 4 | `dna.binary` | `.../biocomputing/binary_sequencer.py` `BinarySequencer.sequence(bytes)` | Py | `sequence()` | any bytes → DNA strand, codons, `gc_content`, `round_trip` | yes / yes | no | **IP**, cap input at 64 KB (strand is 4x the bytes) | **yes** |
| 5 | `code.edapt` | `everlang-dpl/ezr/edapt/fixers.py` `fix(src, language)` → `FixResult(code, fixes)`, `diagnose_brackets()`, `detect_language()` | Py | `edapt <file> --lang` | Python/JS/Go/Rust/Java/Kotlin source → list of fixes with line + reason, repaired code, bracket problems | yes / yes | no | **IP** (load by path, the way `everlang/core/heal.py` `_load()` does) | **yes** |
| 6 | `ever.heal` | `everlang-dpl/ezr/2-interpreter-python/forgive.py` (and `everlang/core/heal.py` `run(source, language)` → `EngineResult`) | Py | `heal.run()` | `.ever` source → healed source + every correction, or refusal | yes / yes | no | **IP** | **yes** |
| 7 | `ever.run` | `everlang-dpl/ezr/2-interpreter-python/ever_cli.py` sub-command `run` (also `check`) | Py | `ever_cli.py run <file>` | `.ever` program → printed values with `[conf/256]` (native confidence) | yes / yes | no | **SP** fixed argv `[python3, ever_cli.py, run, <tmp>.ever]`, 5 s timeout, rlimits | **yes** |
| 8 | `accord.check` | `ClaudRick/accord/accord.py` `load()` → `verify_program()` → `explain_program()` | Py | `accord.py check <file>` | `.accord` program → accepted/refused + explanation, TAC sha256 | yes / yes | no | **IP** (stdlib only; never `fill`, which calls the API) | **yes** |
| 9 | `fineprint.lexicon` | **new**, seeded from `ClaudRick/tests/corpora/analyzer/ground-truth.json` schema (5 categories, 18 subtypes: `auto_renewal`, `cancellation_obstruction`, `time_pressure`, `fear_appeal`, ...) | Py | `scan(text)` | text → hits `{category, subtype, phrase, offset, severity}`, risk 0 to 100 | yes / yes | no | **RI**: versioned phrase/regex table; this is the offline half of the Revisor/ClauseShield "contract trap" scan | **yes** |
| 10 | `fineprint.ai` | `ClaudRick/src/revision/static/index.html` line ~938, the Fine Print Analyzer prompt (`overall_risk_score` 0 to 100, JSON-only); client `src/revision/anthropic_client.py` `AnthropicClient.create_message()` | JS prompt + Py client | `create_message(prompt)` | text → flagged phrases by category, `overall_risk_score` | no / no | Claude | **AI**: lift the prompt string verbatim into `prompts/fineprint.v1.txt`, reuse the stdlib `AnthropicClient` | **yes** |
| 11 | `jargonary.ai` | same `index.html` Jargonary tool (prompt at ~1192 / ~1261, JSON with glossary); Revisor idea adds tone, sarcasm, intent | prompt | Claude/Gemini | text → plain English, glossary, tone/intent read | no / no | yes | **AI** | **yes** |
| 12 | `image.read` | P3's own `AiWire.photoPreset()` "read and translate text" + `ImagePrep.jpegForAi()` | Kotlin prompt | AI vision | JPEG → extracted text (then feeds 9, 10, 11) | no / no | yes | **AI** (Gemini or Claude body, same as `AiWire.geminiBody()`/`claudeBody()`); optional offline `tesseract` via fixed argv if installed | **yes** |
| 13 | `enhance.ai`, `translate.ai` | `index.html` Enhance / Translate | prompt | | text → rewritten text | no / no | yes | AI; transformations, not scans | v2 |
| 14 | `helpdoc.form` | idea (Revisor "Help-Doc" form filler, step-by-step walkthroughs) | none | | form image/text → field list + guidance | no / no | yes | AI | v2 |
| 15 | `genomics.kmer` | `everlang/genomics/kmer_index.py` `KmerIndex.query_all_kmers()`, `query_engine.py` `SequenceQueryEngine.query(seq, top_k, min_coverage)` | Py | | DNA → matches against a reference | yes / yes | no | IP, needs a loaded reference set (`reference_loader.py`), RAM heavy | v2 |
| 16 | `ezpipeline` | `everlang/pipeline.py` `EZPipeline.run(snippet_id, code, lang, rule_particle)` (EXAMINE → EVALUATE → EXECUTE → ARCHIVE) | Py | | code snippet → classification (repair / quarantine / normalized) | yes / check | no | IP; mutates its own `EArchive`, so needs a fresh instance per call | v2 |
| 17 | `ever.audit` | `ezr/2-interpreter-python/audit.py` | Py | | repo docs vs code | yes | no | dev tool, not a user scan | no |
| 18 | `rime.check` | `ClaudRick/realm/realm.py` `converge()`, `rhyme.py` | Py | | Rime couplets → laws pass/fail | yes / yes | no | IP | v2 (fun, low value) |

Not found in the clones: **ClaudRick algorithm discovery** (`ClaudRick/src/` holds only the ReVision web
server `src/revision/`), and the **ClauseShield/Revisor** code (memory says it was a Google AI Studio
prototype; `Revision/app` is the stock Gemini "Baking" sample, `BakingViewModel.kt`). Both are covered
in v1 by #9 + #10 + #11. `quantum/`, `vibe_compiler.py`, `power-pack`, `mastery-system`,
`claude-md-generator` are not scans.

**v1 = 12 scans**: 9 offline deterministic (#1 to #9) and 3 AI-backed (#10, #11, #12).

### Applicability (so "not my kind of input" never poisons the verdict)
Each module declares `accepts(blob) -> bool` from the fingerprint: text, image, code (by extension or
`detect_language()`), `.ever`, `.accord`, DNA (only `ACGT` + whitespace, ≥ 12 bases), build log (contains
`FAILURE:` or `BUILD FAILED`). A scan that does not accept the input reports `n/a` and is left out of the
aggregate. A scan that **should** have run but could not (crash, timeout, no AI key) reports `UNKNOWN`.

---

## 2. Server v1

### 2.1 Layout (stdlib only; nothing to `pip install` for offline scans)
```
suprevolt/
  __main__.py      python -m suprevolt [--host 0.0.0.0 --port 8787 --data ~/.suprevolt --reset-token]
  api.py           ThreadingHTTPServer + BaseHTTPRequestHandler (pattern from ClaudRick/src/revision/server.py)
  loop.py          the job loop (one thread; 2.3)
  store.py         sqlite3 + blob dir
  conf.py          vendored everconf (single file, ClaudRick/everconf/everconf/__init__.py, 311 lines)
  verdict.py       aggregate (2.4)
  ai.py            Claude + Gemini over urllib (lift AnthropicClient; add a Gemini twin)
  scans/           one module per catalog row: ID, VERSION, DETERMINISTIC, NEEDS_KEY, accepts(), run()
  vendor/          frozen copies of the repo code the scans import + VENDOR.lock (path, git commit, sha256)
  golden/          test inputs and expected outputs (section 4)
```
Vendoring (not importing from the live clones) is what makes "same input, same output" survive Rick
editing his repos. A scan's `VERSION` changes whenever its vendored files' hashes change.

### 2.2 Storage
- Blob store: `data/blobs/ab/cdef...` named by sha256 of the raw bytes. Text is normalized before hashing:
  UTF-8, `\r\n` → `\n`, no other change. Images are hashed as received (no re-encode on the server).
- sqlite (`data/suprevolt.db`, WAL mode):
  - `jobs(id TEXT PK, blob TEXT, scans TEXT, state TEXT, seq INTEGER, created INTEGER, done INTEGER)`
  - `results(blob, scan_id, scan_version, cfg_hash, json, value, confidence, state, ms, PRIMARY KEY(blob, scan_id, scan_version, cfg_hash))`
  - `meta(key PK, value)`: catalog version, token hash, last sweep.
- Job id = `sha256(blob + "\0" + sorted(scan ids) + "\0" + catalog_version)[:24]`. Posting the same thing
  twice returns the same job, already done.
- `cfg_hash` for AI scans = sha256(provider, model, prompt file bytes, temperature=0). Changing the model
  is a new cache key, never a silent overwrite.

### 2.3 The deterministic loop
One worker thread. Each tick:
1. Take the oldest queued job (`ORDER BY seq`), mark it `running`.
2. Expand `"all"` to the catalog in **fixed catalog order** (table order above). Requested ids are also
   run in catalog order, never request order.
3. For each scan: if `accepts()` is false → `n/a`. Else look up `results` by the full key; on hit, reuse.
   On miss, run it with a wall-clock limit (offline 5 s, AI 60 s) and store the result.
4. Derived inputs are explicit: `image.read` output text is stored as a **new blob**, and text scans
   (#9, #10, #11) run on that blob as child results of the same job. The order stays fixed.
5. Compute the verdict (2.4), mark `done`, then sleep 0.25 s or wake on a new POST.
- **Re-run schedule** ("sweep"), every 24 h at a fixed UTC time: (a) any cached result whose
  `scan_version` is stale is re-run; (b) a determinism audit re-runs 20 cached deterministic results
  (chosen by lowest sha256, not random) and compares output hashes. Any mismatch sets
  `determinism: "broken"` in `/v1/health` and refuses to serve that scan's cache until fixed.
- AI scans are cached but never claimed deterministic. They run at temperature 0, and their results are
  marked `deterministic: false`.
- Oracle (2 OCPU, 12 GB): one loop thread plus a pool of 2 for AI calls (they wait on the network). Render
  (512 MB): one thread total, `dna.binary` capped at 16 KB, and the sqlite file lives on an ephemeral disk
  (the cache is lost on each restart and spin-down; accepted for a fallback).

### 2.4 Confidence and the SupRevolt verdict (everconf semantics)
- Every result is `C(value, confidence)`, value = risk 0 to 100 (0 = nothing found), confidence 0 to 256.
  Arithmetic follows everconf: confidence of a combination = `min()` of the inputs; one `UNKNOWN` input
  makes the aggregate `UNKNOWN` (`csum`/`cmean`/`cmin_conf` from the vendored `conf.py`).
- Confidence rules per kind (fixed, documented in each module):
  - Exact structural scans (#1, #3, #4, #8): 256 when the input parses, else 256 for "invalid" as a finding.
  - Rule matchers (#2, #5, #6): 256 for a hit on a full signature; 192 when the match relies on a capture
    that looks wrong (see the ReFix trailing-period case, 4.2); 224 for "no match" (absence is weaker evidence).
  - `ever.run`: the program's own `[n/256]` values; the result's confidence = their min.
  - `fineprint.lexicon`: 200 per hit, 160 for "clean" (a lexicon misses paraphrases).
  - AI scans enter at **120**, the same number Ever gives outside data, under Ever's execute floor of 128.
    They rise only when corroborated: an AI flag whose phrase is also a lexicon hit takes
    `min(200, 120 + 80)`. So an AI-only alarm is shown, but never above "unconfirmed".
- Verdict = `{level, risk, confidence, reasons[]}`:
  - `risk` = max risk over applicable results; `confidence` = `cmin_conf` over applicable results.
  - Any applicable `UNKNOWN` → `level: "UNKNOWN"` and the reason names the scan (for example
    `fineprint.ai: no SUPREVOLT_ANTHROPIC_KEY`). This is the honest answer, not a fallback guess.
  - Else `CLEAR` (risk < 25), `CAUTION` (25 to 59), `DANGER` (≥ 60); if confidence < 128 the level gets
    the suffix `_UNCONFIRMED`.
  - Shown to people as `218/256` and as a percent (`85%`), both from the same integer.

### 2.5 REST API
All routes except `/v1/health` need `Authorization: Bearer <token>`.
- `POST /v1/scans`
  - JSON body: `{"text": "...", "scans": ["fineprint.lexicon", ...] | "all", "wait": 0..20}`, or
  - raw upload: body = file/image bytes, `Content-Type` = its type, `X-Filename: lease.txt`,
    query `?scans=all&wait=10` (no multipart: the stdlib `cgi` module is gone in 3.13).
  - Response `202 {"job": "<id>", "state": "queued", "poll": "/v1/scans/<id>"}`, or `200` with the full
    result if it finished within `wait` seconds. Unknown scan id → `400` listing valid ids.
- `GET /v1/scans/{id}` → `{job, state, blob, verdict, results: [{scan, version, state: ok|n/a|unknown|error,
  value, confidence, deterministic, cached, ms, output}]}`.
- `GET /v1/catalog` → each scan's id, version, accepts, deterministic, needs_key, key_present.
- `GET /v1/health` → `{ok, version, catalog_version, queue_depth, determinism}` (no secrets, no counts of users).
- Limits: body ≤ 8 MB (Render: 2 MB), text ≤ 256 KB, image ≤ 8 MB and ≤ 20 megapixels, `.ever`/`.accord`/
  code ≤ 64 KB, queue ≤ 200 jobs (`429` beyond), 30 requests/min per token (reuse `ratelimit.py`).

### 2.6 Security
- **Token**: on first run, `secrets.token_urlsafe(32)` is printed once in a console banner. Only
  `sha256(token)` is stored (`meta.token_hash`); checks use `hmac.compare_digest`. The request logger
  never prints headers. `--reset-token` makes a new one and prints it once. Under systemd the banner
  goes to the journal once; tell Rick to copy it, then `journalctl --vacuum-time=1s` is optional.
- **AI keys** come only from env vars `SUPREVOLT_ANTHROPIC_KEY`, `SUPREVOLT_GEMINI_KEY` (the owner's own
  keys), optional `SUPREVOLT_AI_PROVIDER=claude|gemini`, `SUPREVOLT_CLAUDE_MODEL`, `SUPREVOLT_GEMINI_MODEL`.
  Never accepted over the API, never stored in sqlite, never echoed. No key → those scans report `UNKNOWN`.
- **Subprocess**: only `ever.run` (and optional `tesseract`). argv is a fixed list; user content goes
  only into a temp file the server named (`<sha256>.ever` in a fresh temp dir), never into argv or a
  shell. `shell=False`, `env={}` plus `PATH`, `cwd`=temp dir, `preexec_fn` sets `RLIMIT_CPU 5`,
  `RLIMIT_AS 512 MB`, `RLIMIT_NOFILE 32`, `RLIMIT_FSIZE 1 MB`; output capped at 256 KB.
- In-process scans get only bytes/strings, never paths from the client. `accord.check` loads from a
  server-made temp file. Nothing runs `fill`, `apply`, `--write` or any network call except `ai.py`.
- TLS: the server speaks plain HTTP on `127.0.0.1`; put Caddy (automatic HTTPS) or a Cloudflare Tunnel in
  front on Oracle. Render terminates TLS itself. The token must never cross plain HTTP on the internet.

### 2.7 Deploy
Dockerfile (multi-arch; build with `docker buildx build --platform linux/arm64,linux/amd64`):
```dockerfile
FROM python:3.11-slim
RUN useradd -r -u 10001 sr && mkdir /data && chown sr /data
COPY suprevolt /app/suprevolt
WORKDIR /app
USER sr
ENV SUPREVOLT_DATA=/data PYTHONDONTWRITEBYTECODE=1
EXPOSE 8787
HEALTHCHECK CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8787/v1/health')"
CMD ["python", "-m", "suprevolt", "--host", "0.0.0.0", "--port", "8787"]
```
No Node in the image (ReFix is ported). systemd unit `/etc/systemd/system/suprevolt.service`:
```ini
[Unit]
Description=SupRevolt scan server
After=network-online.target
[Service]
User=suprevolt
WorkingDirectory=/opt/suprevolt
EnvironmentFile=/etc/suprevolt.env          # SUPREVOLT_ANTHROPIC_KEY=..., chmod 600
ExecStart=/usr/bin/python3 -m suprevolt --host 127.0.0.1 --port 8787 --data /var/lib/suprevolt
Restart=on-failure
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/lib/suprevolt
PrivateTmp=true
MemoryMax=2G
[Install]
WantedBy=multi-user.target
```
Local: `python -m suprevolt` (binds 127.0.0.1:8787, data in `~/.suprevolt`).

---

## 3. App side (P3 Companion, framework only)

New files, same split as `AiWire.kt` / `AiClient.kt` and `VoiceCommands.kt` / `CommandRunner.kt`:

| File | Android deps | Purpose |
|---|---|---|
| `SupRevoltWire.kt` | none (pure Kotlin, JVM-tested) | builds requests, parses responses into `ScanResult(scan, state, risk, conf, text)` and `Verdict`; formats `218/256` and `85%` |
| `SupRevoltClient.kt` | `HttpURLConnection`, `org.json` (both framework) | POST/GET, poll every 1 s up to 30 s, runs on a background thread like `AiClient` |
| `SupRevoltScreen.kt` | views | the screen below; opened from Settings › SupRevolt and from Assistant, Gallery and Glasses |
| `ConfidenceBar.kt` | `View` | draws a 0 to 256 bar with the number; `UNKNOWN` = hatched grey bar labelled "unknown"; `n/a` rows hidden behind "Show skipped" |

Screen:
1. **Setup card**: server URL + token fields, **Test** button (calls `/v1/health`, then `/v1/catalog` to
   prove the token). Token is stored in app-private prefs like the AI key, shown masked, and is **excluded**
   from `SettingsBackup` (same rule as keys). `https://` required; `http://` allowed only for
   `127.0.0.1`/`10.*`/`192.168.*` after a warning (needs a `network_security_config` entry for those).
2. **Input**: Glasses' last photo (reuse the Glasses tab path), take a new photo with the glasses, pick a
   document (`ACTION_OPEN_DOCUMENT`, any type), or type/paste text. Photos go through
   `ImagePrep.jpegForAi(maxSide = 2048)` before upload.
3. **Scans**: chips from `/v1/catalog` (greyed when the server says `key_present: false`), plus **All**.
4. **Results**: verdict header (level, risk, `confidence/256`), then one row per scan with its bar,
   cached/live tag and a tap-to-expand output. "Share report" makes a text summary (no token, no URL).
5. Gallery long-press adds **Send to SupRevolt**.

Voice: add `Rule("suprevolt.scan", P4, r("scan (?:this|that|it)", "run a scan", "suprevolt scan"))` to the
ordered table in `VoiceCommands.kt` (pure, JVM-testable). `CommandRunner` → `MainActivity.whenConnected`
→ take a glasses photo → upload with `scans=all&wait=20` → speak `"Caution. Three flags. Confidence 182 of
256."` or `"Server not set up"` / `"Unknown: no AI key on the server"`. Add it to `VOICE-COMMANDS.md`'s
test table, including that "scan this" in `Mode.TRANSLATE` is translated, not run.

---

## 4. Determinism and test plan

### 4.1 Gates (run in CI and as `python -m suprevolt --selftest`)
1. **Golden**: every offline scan runs every file in `golden/<scan>/` and the canonical JSON
   (`json.dumps(sort_keys=True, separators=(",",":"))`) must match `expected.json` byte for byte.
2. **Twice-run**: each golden input runs twice in one process and once in a fresh process; three output
   hashes equal. Also run with `PYTHONHASHSEED=0` and `=1`: equal (catches set/dict-order leaks).
3. **Parity**: `refix.gradle` port vs `node refix2/bin/refix scan` on all ReFix test logs (dev only).
4. **Poison**: a job with one applicable `UNKNOWN` must have verdict `UNKNOWN`; a job whose only skipped
   scans are `n/a` must not.
5. **Cache key**: same text with `\r\n` vs `\n` → same blob and job id; one extra byte → different.
6. **Security**: a `.ever` file containing `; rm -rf /` and `$(...)` runs as text only; an infinite loop
   dies at 5 s with state `error`; a 9 MB body → `413`; no token / wrong token → `401`; the token string
   never appears in the log file after first-run.

### 4.2 Golden inputs (values below were produced by running the current code)
| Scan | Input | Expected |
|---|---|---|
| `dna.sequence` | `ATCGATCGATCG` | 4 codons `ATC GAT CGA TCG` at starts 0, 3, 6, 9; `gc_content 0.5`; `valid true`; conf 256 |
| `dna.sequence` | `ATCGX` | `lexer_errors [{base X, index 4}]`; `gc_content 0.5` (over 4 valid bases); `valid false` |
| `dna.binary` | bytes `Hi` | `dna "TACATCCT"`; `round_trip true`; `gc_content 0.375` |
| `refix.gradle` | `FAILURE: Build failed\n> Could not resolve com.squareup.okhttp3:okhttp:9.9.9.\n` | one hit `gradle.unresolved_dependency`, `fixture_form "SEARCH_MAVEN_CENTRAL:com.squareup.okhttp3:okhttp"`, `captures.version "9.9.9."` **(trailing period is a real bug in the signature regex; keep this golden at conf 192 until the regex is fixed, then update)** |
| `refix.gradle` | `BUILD SUCCESSFUL` | `[]`, risk 0, conf 224 |
| `code.edapt` | `def f(x)\n    return x\n` (python) | one fix at line 1, reason "if/elif/else/for/while/def/class/try/except/finally headers must end in a colon"; `code` gains the `:` |
| `code.edapt` | a clean 3-line Kotlin function | `fixes []`, bracket problems `[]` |
| `ever.run` | `ezr/examples/weekly_sales.ever` | `total = z [0/256]`, `best = 610 [256/256]`, `third_day = 380 [256/256]` → result `UNKNOWN` (as documented in `ezr/README.md`) |
| `ever.run` | same file with Thursday filled | `total` at 256 (README: 2615) |
| `ever.heal` | `ezr/examples` file with a stray `;` | one correction from `_strip_semicolons`, healed source runs |
| `accord.check` | `ClaudRick/accord/examples/classify.accord` | `accepted true`; TAC sha256 pinned on first run |
| `fineprint.lexicon` | `ClaudRick/tests/corpora/analyzer/D01-gym-membership.docx` text | includes `auto_renewal` on "renews automatically each year"; recall vs `ground-truth.json` ≥ the pinned v1 number |
| `fineprint.lexicon` | `C01-library-newsletter.docx`, `C02-fair-refund-policy.docx` | `CLEAR` (controls; zero `high` hits) |
| `blob.fingerprint` | the 12-byte string `ATCGATCGATCG` | sha256 pinned; `type text/plain`; `lines 1` |

`.docx` inputs need text extraction: v1 reads `word/document.xml` from the zip with `zipfile` + `xml.etree`
(stdlib, deterministic). PDFs are v2 (or AI `image.read` per page).

### 4.3 AI scans (not deterministic, still tested)
- Recorded-response tests: `ai.py` takes a `transport` callable; tests feed stored JSON replies and check
  parsing, the 120 entry confidence, corroboration up to 200, and `UNKNOWN` on missing key / bad JSON.
- Live quality: reuse `ClaudRick/tests/corpora/analyzer/grade.js` and `tools/grade-tools.js` thresholds
  (recall per category, control false positives) on demand, never in CI (it spends the owner's key).

---

## 5. Open risks
1. **Determinism leaks**: vendored code drifting, float formatting, dict order, the clock. Mitigated by
   vendoring with hashes, canonical JSON, the twice-run gate and the daily audit.
2. **Free-host limits**: Oracle Always Free ARM capacity is often "out of stock" and idle VMs can be
   reclaimed; Render sleeps and wipes disk. The app must show "server asleep / cache cold" plainly.
3. **Running user-supplied programs** (`ever.run`): the only real code execution surface. Fixed argv,
   rlimits, no network, temp dir, 5 s; if any doubt remains, ship v1 with `ever.run` disabled by default
   (`SUPREVOLT_ENABLE_EVER_RUN=1`).
