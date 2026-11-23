"""Shared plumbing for the three AI-backed scans: prompt files, the 120 entry confidence, UNKNOWN on failure."""

from __future__ import annotations

import os

from . import PROMPTS, unknown
from .. import ai

AI_ENTRY = 120          # what Ever gives outside data: under the execute floor of 128
CORROBORATED = 200      # min(200, 120 + 80) when the offline lexicon saw the same phrase


def prompt_bytes(name: str) -> bytes:
    with open(os.path.join(PROMPTS, name), "rb") as f:
        return f.read()


def prompt(name: str) -> str:
    return prompt_bytes(name).decode("utf-8")


def cfg_key(name: str, cfg) -> dict:
    return ai.cfg_key(cfg.ai, prompt_bytes(name))


def no_key(cfg):
    return None if cfg.ai.chosen() else unknown(cfg.ai.missing_note())


def failed(exc: Exception):
    return unknown(f"AI call failed: {exc}")


def clamp_risk(v) -> int | None:
    try:
        return max(0, min(100, int(round(float(v)))))
    except (TypeError, ValueError):
        return None
