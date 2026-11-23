# SupRevolt server

One server that runs every scan in a fixed order, gives the same answer for the same input, and puts
a confidence from 0 to 256 on every result. P3 Companion is its front door (Settings › SupRevolt).
Python 3.11 or newer, standard library only: there is nothing to `pip install`.

Design: [`docs/SUPREVOLT-ARCHITECTURE.md`](../docs/SUPREVOLT-ARCHITECTURE.md). Vendored code and its
sources: [`suprevolt/vendor/VENDORED.md`](suprevolt/vendor/VENDORED.md).

## The scans

| # | Scan | Input | Offline | Same answer every time |
|---|---|---|---|---|
| 1 | `blob.fingerprint` | anything | yes | yes |
| 2 | `refix.gradle` | Gradle build log | yes | yes |
| 3 | `dna.sequence` | DNA text (A C G T, 12+ bases) | yes | yes |
| 4 | `dna.binary` | any bytes up to 64 KB (16 KB on Render) | yes | yes |
| 5 | `code.edapt` | Python, Go, Kotlin source (others return when edapt is fixed upstream) | yes | yes |
| 6 | `ever.heal` | `.ever` source | yes | yes |
| 7 | `ever.run` | `.ever` program, **off unless `SUPREVOLT_ENABLE_EVER_RUN=1`** | yes | yes |
| 8 | `accord.check` | `.accord` program | yes | yes |
| 9 | `fineprint.lexicon` | text, `.docx` | yes | yes |
| 10 | `fineprint.ai` | text, `.docx` | needs your AI key | no (cached) |
| 11 | `jargonary.ai` | text, `.docx` | needs your AI key | no (cached) |
| 12 | `image.read` | JPEG, PNG, GIF, WebP | needs your AI key (or `tesseract`) | no (cached) |

A scan that doesn't fit the input says `n/a` and is left out of the verdict. A scan that should have
answered but couldn't (no AI key, crash, timeout) says `unknown`, and then the whole verdict is
`UNKNOWN` with the reason, instead of a guess. AI results start at 120/256 (under the 128 floor), and
only rise to 200 when the offline lexicon saw the same phrase.

Verdict: `CLEAR` (risk under 25), `CAUTION` (25 to 59), `DANGER` (60+), plus `_UNCONFIRMED` when the
confidence is under 128.

## Run it on your computer

```
cd server
python3 -m suprevolt
```

The first start prints the **access token once**. Copy it then; only a hash is kept. Lost it? Run
`python3 -m suprevolt --reset-token` for a new one. Data lives in `~/.suprevolt` (`--data` to change).
The server listens on `127.0.0.1:8787`.

Tests (no extra packages): `python3 -m unittest discover -s tests -v`. Determinism check:
`python3 -m suprevolt --selftest`.

### AI keys (optional)

Set one in the environment before starting. They are read from the environment only, never stored,
never logged, never sent to the app.

| Variable | Meaning |
|---|---|
| `SUPREVOLT_GEMINI_KEY` or `GEMINI_API_KEY` | Google Gemini key (free at aistudio.google.com/apikey) |
| `SUPREVOLT_ANTHROPIC_KEY` or `ANTHROPIC_API_KEY` | Anthropic Claude key |
| `SUPREVOLT_AI_PROVIDER` | `gemini` or `claude` (default: Claude if its key is set, else Gemini) |
| `SUPREVOLT_GEMINI_MODEL` / `SUPREVOLT_CLAUDE_MODEL` | default `gemini-2.5-flash` / `claude-sonnet-5-5` |
| `SUPREVOLT_ENABLE_EVER_RUN=1` | let `ever.run` execute `.ever` programs (sandboxed, 5 s) |
| `SUPREVOLT_PROFILE=render` | smaller limits for a 512 MB host |
| `SUPREVOLT_TOKEN_HASH` | keep one token on a host that wipes its disk (see Render) |

Text sent to an AI first goes through a redaction gate (card numbers, SSNs, passwords, API keys,
emails, phone numbers are replaced).

## API

All routes but health need `Authorization: Bearer <token>`.

```
GET  /v1/health
GET  /v1/catalog
POST /v1/scans        {"text": "...", "scans": "all" | ["fineprint.lexicon", ...], "wait": 0-20,
                       "filename": "lease.txt", "lang": "kotlin"}
POST /v1/scans?scans=all&wait=10   body = the file's bytes, header X-Filename: lease.docx
GET  /v1/scans/{id}
```

Answers `202 {"job", "state", "poll"}`, or `200` with the verdict and every result when it finished
within `wait` seconds. Posting the same thing twice gives the same job back.

Limits: body 8 MB (Render 2 MB), text 256 KB, code and `.ever`/`.accord` 64 KB, images 8 MB and
20 megapixels, 200 queued jobs, 30 requests a minute per token.

## Put it on Oracle Cloud (Always Free), all from a phone

Everything below happens in your phone's browser. Oracle's **Cloud Shell** is a terminal inside the
Oracle console, so you never need a computer or an SSH app.

**What you get:** an Arm (Ampere A1) virtual machine that stays on. Always Free covers up to 4 cores
and 24 GB of memory in total; this uses 2 cores and 12 GB. Free accounts need a card for identity
checks; Always Free resources are not charged. Oracle can reclaim Always Free machines that sit idle
for a long time, and new Ampere machines are sometimes "out of capacity": try another availability
domain, or again later.

### 1. Make an SSH key in Cloud Shell

1. Sign in at cloud.oracle.com. Tap the **Cloud Shell** icon (`>_`, top bar) and wait for the prompt.
2. Paste:
   ```
   ssh-keygen -t ed25519 -f ~/.ssh/suprevolt -N "" && cat ~/.ssh/suprevolt.pub
   ```
3. Long-press the line that starts with `ssh-ed25519` and copy it. Cloud Shell keeps this key.

### 2. Create the machine

1. Menu › **Compute** › **Instances** › **Create instance**.
2. Name: `suprevolt`.
3. **Image and shape** › Edit › Image: **Canonical Ubuntu 24.04**. Shape: **Ampere** ›
   `VM.Standard.A1.Flex`, 2 OCPUs, 12 GB memory.
4. **Add SSH keys** › **Paste public keys** › paste the `ssh-ed25519 ...` line.
5. **Create**. When it says Running, copy the **Public IP address**.

### 3. Install SupRevolt

In Cloud Shell (replace `YOUR_IP`):

```
ssh -i ~/.ssh/suprevolt ubuntu@YOUR_IP
```

Answer `yes` once. Now you are on the new machine. Paste these one block at a time:

```
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 git
git clone https://github.com/Zdycomp/p3-companion.git
cd p3-companion/server
cp deploy/suprevolt.env.example suprevolt.env && chmod 600 suprevolt.env
nano suprevolt.env
```

In nano, put your Gemini or Claude key after the `=` (or leave both empty for offline scans only).
Save with Ctrl+O, Enter, then Ctrl+X (on a phone keyboard: the Cloud Shell keyboard bar has Ctrl).

```
sudo docker compose up -d --build
sudo docker compose logs suprevolt | grep -A3 "shown once"
```

Copy the token it shows. That is the only time it is shown.

Check it runs: `curl -s 127.0.0.1:8787/v1/health` should print `"ok": true`.

### 4. Give it an HTTPS address

The server only listens on the machine itself. The app needs `https://`, so put one of these in
front. **Never** open port 8787 to the internet: the token must not travel over plain HTTP.

**Option A, quickest: a Cloudflare quick tunnel (no domain, no account).**

```
sudo docker run -d --name tunnel --restart unless-stopped --network host \
  cloudflare/cloudflared:latest tunnel --no-autoupdate --url http://127.0.0.1:8787
sleep 8; sudo docker logs tunnel 2>&1 | grep -o 'https://[a-z0-9-]*\.trycloudflare\.com'
```

That `https://....trycloudflare.com` address is your server URL. It changes if the tunnel restarts
(after a reboot run the `grep` line again and update the app). For an address that never changes,
use a named Cloudflare Tunnel on your own domain, or Option B.

**Option B, a fixed address: Caddy with a free DuckDNS name.**

1. At duckdns.org sign in, create a name (for example `rick-suprevolt`) and set its IP to `YOUR_IP`.
2. In Oracle: **Networking** › **Virtual cloud networks** › your network › **Security Lists** ›
   Default › **Add Ingress Rules**: source `0.0.0.0/0`, TCP, destination ports `80,443`.
3. On the machine (Oracle's Ubuntu image also blocks ports in its own firewall):
   ```
   sudo iptables -I INPUT 6 -p tcp -m state --state NEW -m multiport --dports 80,443 -j ACCEPT
   sudo netfilter-persistent save
   sudo apt-get install -y caddy
   sudo cp deploy/Caddyfile /etc/caddy/Caddyfile
   sudo nano /etc/caddy/Caddyfile
   ```
   Change `suprevolt.example.duckdns.org` to your name, save, then `sudo systemctl reload caddy`.
4. Your server URL is `https://rick-suprevolt.duckdns.org`. Caddy renews the certificate by itself.

### 5. Connect the app

P3 Companion › Settings › **SupRevolt** › server URL and token › **Test**. It checks health, then the
catalog (which proves the token).

### Updating

```
cd ~/p3-companion && git pull && cd server && sudo docker compose up -d --build
```

The token and the cache survive (they live in the `suprevolt-data` volume).

### Without Docker (systemd)

Needs Python 3.11+ (Ubuntu 24.04 has 3.12):

```
sudo useradd -r -s /usr/sbin/nologin suprevolt
sudo mkdir -p /opt/suprevolt /var/lib/suprevolt && sudo cp -r suprevolt /opt/suprevolt/
sudo chown -R suprevolt /var/lib/suprevolt
sudo cp deploy/suprevolt.env.example /etc/suprevolt.env && sudo chmod 600 /etc/suprevolt.env
sudo cp deploy/suprevolt.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now suprevolt
sudo journalctl -u suprevolt | grep -A3 "shown once"
```

The banner reaches the journal once. After you copy the token you may clear old journal entries
with `sudo journalctl --vacuum-time=1s` (optional).

## Fallback: Render (free web service)

Use this if Oracle has no capacity. It works, with real limits:

- The free service **sleeps after 15 minutes** without requests; the next request waits about a
  minute while it wakes. The app shows "server asleep" plainly.
- **512 MB of memory**: set `SUPREVOLT_PROFILE=render` (2 MB uploads, 16 KB `dna.binary`).
- **The disk is wiped** on every restart and sleep: the cache starts cold, and without
  `SUPREVOLT_TOKEN_HASH` a new token would be made each time.
- Free instance hours are limited per month. Render provides HTTPS itself.

Steps (phone browser, dashboard.render.com):

1. **New** › **Web Service** › **Public Git repository**: `https://github.com/Zdycomp/p3-companion`.
2. **Root Directory**: `server`. **Language/Runtime**: Docker. **Instance type**: Free.
3. **Environment variables**: `SUPREVOLT_PROFILE` = `render`, plus `SUPREVOLT_GEMINI_KEY` or
   `SUPREVOLT_ANTHROPIC_KEY` if you want the AI scans.
4. **Deploy**. Open **Logs** and copy the token from the banner, and the `SUPREVOLT_TOKEN_HASH=...`
   line under it.
5. **Environment** › add `SUPREVOLT_TOKEN_HASH` with that value › save (Render redeploys). From now on
   the same token works after every sleep.
6. In the app use `https://<your-service>.onrender.com` and the token.

## Security notes

- Token: `secrets.token_urlsafe(32)`, shown once, stored as sha256, compared in constant time. The
  request log prints only method, path and status (never headers or query strings).
- In-process scans get bytes, never a path from a client. Nothing runs ReFix `apply`, accord `fill`,
  edapt `--write`, or any network call except `ai.py` to the provider you configured.
- `ever.run` (off by default) runs a fixed argv in a child process: user content only in a temp file,
  no shell, empty environment, 5 s CPU and wall clock, 512 MB, 32 files, 1 MB writes, `extern` refused.
- Put HTTPS in front (Caddy, Cloudflare Tunnel, or Render's own).
