from __future__ import annotations

from pathlib import Path

from click.testing import CliRunner

from agentops.cli import cli


def test_login_saves_token(monkeypatch, tmp_path: Path) -> None:
    class FakeClient:
        def __init__(self) -> None:
            self.token = None

        def login(self, api_key: str) -> dict[str, object]:
            assert api_key == "test-key"
            return {"token": "jwt-token", "expiresIn": 3600}

    from agentops.cli import AppContext
    from agentops.config import AppConfig, ConfigManager
    from rich.console import Console

    config_path = tmp_path / "agentops.yaml"

    def fake_build(
        config_path_str: str, api_url: str | None, verbose: bool, json_output: bool
    ) -> AppContext:
        manager = ConfigManager.from_path(config_path_str)
        return AppContext(
            config_manager=manager,
            settings=AppConfig(api_url=api_url or "http://localhost:8080"),
            client=FakeClient(),
            console=Console(record=True),
            verbose=verbose,
            json_output=json_output,
        )

    monkeypatch.setattr("agentops.cli.build_app_context", fake_build)
    runner = CliRunner()
    result = runner.invoke(
        cli, ["--config", str(config_path), "login", "--api-key", "test-key", "--save"]
    )
    assert result.exit_code == 0, result.output
    written = config_path.read_text(encoding="utf-8")
    assert "jwt-token" in written
    assert "expires-at" in written
