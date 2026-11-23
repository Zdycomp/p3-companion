"""python -m suprevolt [--host 127.0.0.1 --port 8787 --data ~/.suprevolt --reset-token --selftest]"""

from __future__ import annotations

import argparse
import os
import signal
import sys

from . import VERSION
from .config import Config


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="suprevolt", description="SupRevolt scan server")
    p.add_argument("--host", default=os.environ.get("SUPREVOLT_HOST", "127.0.0.1"))
    p.add_argument("--port", type=int, default=int(os.environ.get("PORT") or os.environ.get("SUPREVOLT_PORT") or 8787))
    p.add_argument("--data", default=None, help="data folder (default ~/.suprevolt, or SUPREVOLT_DATA)")
    p.add_argument("--profile", choices=("full", "render"), default=None,
                   help="render = 2 MB bodies, 16 KB dna.binary (free 512 MB host)")
    p.add_argument("--enable-ever-run", action="store_true", default=None,
                   help="allow ever.run to execute .ever programs (sandboxed child process)")
    p.add_argument("--reset-token", action="store_true", help="make a new access token and print it once")
    p.add_argument("--selftest", action="store_true", help="run the golden and twice-run gates, then exit")
    p.add_argument("--version", action="version", version=f"suprevolt {VERSION}")
    a = p.parse_args(argv)

    cfg = Config.from_env(host=a.host, port=a.port, data_dir=a.data, profile=a.profile,
                          enable_ever_run=a.enable_ever_run)
    if a.selftest:
        from .selftest import run_selftest
        return run_selftest(cfg)

    os.makedirs(cfg.data_dir, exist_ok=True)
    fd = os.open(cfg.log_file, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    logf = os.fdopen(fd, "a", encoding="utf-8")

    from .api import Server, banner, ensure_token
    from .loop import Engine

    def log(msg: str) -> None:
        import time
        logf.write(f"{time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())} {msg}\n")
        logf.flush()

    engine = Engine(cfg, log=log)
    fixed = os.environ.get("SUPREVOLT_TOKEN_HASH", "").strip().lower()
    if a.reset_token and fixed:
        print("note: SUPREVOLT_TOKEN_HASH is set, so --reset-token does nothing; change that variable instead",
              flush=True)
    token = ensure_token(engine.store, reset=a.reset_token, fixed_hash=fixed)
    if token:
        # Console only (stdout), never the log file. Under systemd this reaches the journal once.
        sys.stdout.write(banner(token))
        sys.stdout.flush()
        del token
    server = Server((cfg.host, cfg.port), engine, logf=logf)
    engine.start()
    host, port = server.server_address[:2]
    ai = cfg.ai.chosen() or "none (AI scans report UNKNOWN)"
    print(f"SupRevolt {VERSION} listening on http://{host}:{port}  data={cfg.data_dir}  ai={ai}  "
          f"ever.run={'on' if cfg.enable_ever_run else 'off'}", flush=True)
    log(f"start version={VERSION} port={port} ai={cfg.ai.chosen() or 'none'}")

    def stop(*_):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, stop)
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        engine.shutdown()
        log("stop")
        logf.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
