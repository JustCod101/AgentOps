from __future__ import annotations

from datetime import datetime, timedelta, timezone

from .config import AppConfig, ConfigManager


def calculate_expiry(expires_in: int) -> datetime:
    return datetime.now(timezone.utc) + timedelta(seconds=expires_in)


def store_token(
    manager: ConfigManager,
    config: AppConfig,
    token: str,
    expires_in: int,
) -> AppConfig:
    config.auth.token = token
    config.auth.expires_at = calculate_expiry(expires_in)
    return manager.save(config)


def clear_token(manager: ConfigManager, config: AppConfig) -> AppConfig:
    config.auth.token = None
    config.auth.expires_at = None
    return manager.save(config)
