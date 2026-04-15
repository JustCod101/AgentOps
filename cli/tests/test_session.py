from __future__ import annotations

from pathlib import Path

from click.testing import CliRunner

from agentops.cli import cli


def _build_context(monkeypatch, tmp_path: Path, fake_client) -> None:
    from agentops.cli import AppContext
    from agentops.config import AppConfig, AuthConfig, ConfigManager
    from rich.console import Console

    def fake_build(
        config_path_str: str, api_url: str | None, verbose: bool, json_output: bool
    ) -> AppContext:
        manager = ConfigManager.from_path(config_path_str)
        return AppContext(
            config_manager=manager,
            settings=AppConfig(
                api_url=api_url or "http://localhost:8080", auth=AuthConfig(token="jwt")
            ),
            client=fake_client,
            console=Console(record=True, width=120),
            verbose=verbose,
            json_output=json_output,
        )

    monkeypatch.setattr("agentops.cli.build_app_context", fake_build)


def test_session_show(monkeypatch, tmp_path: Path) -> None:
    class FakeClient:
        def get_session(self, session_id: str):
            assert session_id == "sess-1"
            return {
                "session": {
                    "sessionId": "sess-1",
                    "status": "COMPLETED",
                    "intentType": "DB_SLOW_QUERY",
                    "totalLatencyMs": 321,
                    "rootCause": "缺少索引",
                },
                "traceStats": {"totalSteps": 8},
            }

    _build_context(monkeypatch, tmp_path, FakeClient())
    runner = CliRunner()
    result = runner.invoke(
        cli, ["--config", str(tmp_path / "cfg.yaml"), "session", "show", "sess-1"]
    )
    assert result.exit_code == 0, result.output
    assert "Diagnosis Session" in result.output
    assert "缺少索引" in result.output


def test_session_trace_json(monkeypatch, tmp_path: Path) -> None:
    class FakeClient:
        def get_trace(self, session_id: str):
            assert session_id == "sess-1"
            return [
                {
                    "stepIndex": 1,
                    "agentName": "ROUTER",
                    "stepType": "THOUGHT",
                    "content": "分析意图",
                    "timestamp": "2026-01-01T00:00:00Z",
                }
            ]

    _build_context(monkeypatch, tmp_path, FakeClient())
    runner = CliRunner()
    result = runner.invoke(
        cli,
        [
            "--config",
            str(tmp_path / "cfg.yaml"),
            "session",
            "trace",
            "sess-1",
            "--format",
            "json",
        ],
    )
    assert result.exit_code == 0, result.output
    assert '"agentName": "ROUTER"' in result.output
