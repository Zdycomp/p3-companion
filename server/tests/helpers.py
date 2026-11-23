"""Shared test helpers (unittest only, no third-party packages)."""

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER = os.path.dirname(HERE)
DATA = os.path.join(HERE, "data")
if SERVER not in sys.path:
    sys.path.insert(0, SERVER)

from suprevolt.config import AiConfig, Config  # noqa: E402
from suprevolt.loop import prepare  # noqa: E402
from suprevolt.scans import Item  # noqa: E402
from suprevolt.util import sha256_hex  # noqa: E402

CLAUDRICK = "/home/user/src/ClaudRick"   # optional read-only clone, used only for dev parity checks


def cfg(**kw) -> Config:
    kw.setdefault("ai", AiConfig())
    kw.setdefault("data_dir", os.devnull)
    return Config(**kw)


def item(data, filename="", lang="") -> Item:
    if isinstance(data, str):
        data = data.encode("utf-8")
    data, kind = prepare(data)
    return Item(data, sha256_hex(data), kind, filename, lang)


def read(path, mode="rb"):
    with open(path, mode) as f:
        return f.read()


def load_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def clean_env(**extra) -> dict:
    """The environment for a child server: no AI keys and nothing else from the caller's secrets."""
    env = {k: v for k, v in os.environ.items()
           if not any(s in k.upper() for s in ("KEY", "TOKEN", "SECRET", "SUPREVOLT"))}
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    env.update(extra)
    return env
