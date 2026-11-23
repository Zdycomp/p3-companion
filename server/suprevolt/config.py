"""Server settings. Everything comes from flags or environment variables; nothing secret is stored."""

from __future__ import annotations

import os
from dataclasses import dataclass, field

DEFAULT_CLAUDE_MODEL = "claude-sonnet-5-5"
DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"


def _env(*names: str) -> str:
    for n in names:
        v = os.environ.get(n, "").strip()
        if v:
            return v
    return ""


def _flag(name: str) -> bool:
    return os.environ.get(name, "").strip().lower() in ("1", "true", "yes", "on")


@dataclass
class AiConfig:
    """The owner's own AI keys, read from the environment only. Never sent to a client, never stored."""
    anthropic_key: str = ""
    gemini_key: str = ""
    provider: str = ""                     # "claude" | "gemini" | "" (pick whichever key is set)
    claude_model: str = DEFAULT_CLAUDE_MODEL
    gemini_model: str = DEFAULT_GEMINI_MODEL
    transport: object = None               # tests inject a fake transport; None = real HTTPS

    @classmethod
    def from_env(cls) -> "AiConfig":
        return cls(
            anthropic_key=_env("SUPREVOLT_ANTHROPIC_KEY", "ANTHROPIC_API_KEY"),
            gemini_key=_env("SUPREVOLT_GEMINI_KEY", "GEMINI_API_KEY"),
            provider=_env("SUPREVOLT_AI_PROVIDER").lower(),
            claude_model=_env("SUPREVOLT_CLAUDE_MODEL") or DEFAULT_CLAUDE_MODEL,
            gemini_model=_env("SUPREVOLT_GEMINI_MODEL") or DEFAULT_GEMINI_MODEL,
        )

    def chosen(self) -> str:
        """Which provider will answer, or '' when no key is set."""
        if self.provider == "claude":
            return "claude" if self.anthropic_key else ""
        if self.provider == "gemini":
            return "gemini" if self.gemini_key else ""
        if self.anthropic_key:
            return "claude"
        if self.gemini_key:
            return "gemini"
        return ""

    def model(self) -> str:
        p = self.chosen()
        return self.claude_model if p == "claude" else self.gemini_model if p == "gemini" else ""

    def missing_note(self) -> str:
        if self.provider == "claude":
            return "no SUPREVOLT_ANTHROPIC_KEY (or ANTHROPIC_API_KEY) on the server"
        if self.provider == "gemini":
            return "no SUPREVOLT_GEMINI_KEY (or GEMINI_API_KEY) on the server"
        return "no AI key on the server (set ANTHROPIC_API_KEY or GEMINI_API_KEY)"

    def __repr__(self) -> str:  # keys never appear in a repr, a log or a traceback
        return f"AiConfig(provider={self.chosen() or 'none'}, model={self.model() or '-'})"


@dataclass
class Config:
    host: str = "127.0.0.1"
    port: int = 8787
    data_dir: str = ""
    profile: str = "full"                  # "full" (Oracle, local) | "render" (512 MB free tier)
    max_body: int = 8 * 1024 * 1024
    max_text: int = 256 * 1024
    max_image: int = 8 * 1024 * 1024
    max_pixels: int = 20_000_000
    max_code: int = 64 * 1024
    max_queue: int = 200
    max_wait: int = 20
    rate_per_min: int = 30
    dna_binary_cap: int = 64 * 1024
    offline_timeout: float = 5.0
    ai_timeout: float = 60.0
    enable_ever_run: bool = False
    ever_run_timeout: float = 5.0
    sweep_utc: tuple = (3, 17)             # daily re-run and determinism audit, 03:17 UTC
    log_file: str = ""
    ai: AiConfig = field(default_factory=AiConfig)

    def __post_init__(self):
        if not self.data_dir:
            self.data_dir = os.path.expanduser("~/.suprevolt")
        if self.profile == "render":
            self.max_body = min(self.max_body, 2 * 1024 * 1024)
            self.dna_binary_cap = min(self.dna_binary_cap, 16 * 1024)
        if not self.log_file:
            self.log_file = os.path.join(self.data_dir, "suprevolt.log")

    @classmethod
    def from_env(cls, **over) -> "Config":
        kw = dict(
            data_dir=_env("SUPREVOLT_DATA"),
            profile=_env("SUPREVOLT_PROFILE").lower() or "full",
            enable_ever_run=_flag("SUPREVOLT_ENABLE_EVER_RUN"),
            ai=AiConfig.from_env(),
        )
        rate = _env("SUPREVOLT_RATE_PER_MIN")
        if rate.isdigit():
            kw["rate_per_min"] = int(rate)
        kw.update({k: v for k, v in over.items() if v is not None})
        return cls(**kw)
