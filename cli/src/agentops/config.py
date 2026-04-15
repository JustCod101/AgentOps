from __future__ import annotations

import os
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel, ConfigDict, Field

DEFAULT_API_URL = "http://localhost:8080"
DEFAULT_CONFIG_PATH = Path("~/.agentops.yaml").expanduser()


class AuthConfig(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    token: str | None = None
    expires_at: datetime | None = Field(default=None, alias="expires-at")

    def is_expired(self, skew_seconds: int = 60) -> bool:
        if self.expires_at is None:
            return True
        return self.expires_at <= datetime.now(timezone.utc) + timedelta(
            seconds=skew_seconds
        )


class AppConfig(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    api_url: str = Field(default=DEFAULT_API_URL, alias="api-url")
    auth: AuthConfig = Field(default_factory=AuthConfig)


@dataclass(slots=True)
class ConfigManager:
    path: Path

    @classmethod
    def from_path(cls, path: str | Path | None = None) -> "ConfigManager":
        resolved = Path(path or DEFAULT_CONFIG_PATH).expanduser()
        return cls(path=resolved)

    def exists(self) -> bool:
        return self.path.exists()

    def load(self) -> AppConfig:
        if not self.path.exists():
            return AppConfig()
        raw = yaml.safe_load(self.path.read_text(encoding="utf-8")) or {}
        return AppConfig.model_validate(raw)

    def save(self, config: AppConfig) -> AppConfig:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = config.model_dump(by_alias=True, exclude_none=True)
        self.path.write_text(
            yaml.safe_dump(payload, allow_unicode=True, sort_keys=False),
            encoding="utf-8",
        )
        os.chmod(self.path, 0o600)
        return config

    def init(self, api_url: str | None = None) -> AppConfig:
        config = self.load()
        if api_url:
            config.api_url = api_url
        return self.save(config)

    def set_value(self, key: str, value: Any) -> AppConfig:
        config = self.load()
        normalized = key.replace("-", "_")
        if normalized == "api_url":
            config.api_url = str(value)
        else:
            raise KeyError(f"Unsupported config key: {key}")
        return self.save(config)
