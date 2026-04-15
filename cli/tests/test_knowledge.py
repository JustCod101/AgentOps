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


def test_knowledge_list_renders_table(monkeypatch, tmp_path: Path) -> None:
    class FakeClient:
        def list_knowledge(self, category: str | None = None):
            assert category is None
            return [
                {
                    "id": 1,
                    "category": "SLOW_QUERY_PATTERN",
                    "title": "慢查询",
                    "priority": 8,
                    "tags": ["db"],
                }
            ]

    _build_context(monkeypatch, tmp_path, FakeClient())
    runner = CliRunner()
    result = runner.invoke(
        cli, ["--config", str(tmp_path / "cfg.yaml"), "knowledge", "list"]
    )
    assert result.exit_code == 0, result.output
    assert "Knowledge Entries" in result.output
    assert "慢查询" in result.output


def test_knowledge_add_posts_payload(monkeypatch, tmp_path: Path) -> None:
    seen = {}

    class FakeClient:
        def create_knowledge(self, payload):
            seen.update(payload)
            return {"id": 2, **payload}

    _build_context(monkeypatch, tmp_path, FakeClient())
    runner = CliRunner()
    result = runner.invoke(
        cli,
        [
            "--config",
            str(tmp_path / "cfg.yaml"),
            "knowledge",
            "add",
            "--title",
            "连接池耗尽",
            "--category",
            "RUNBOOK",
            "--content",
            "检查慢查询",
            "--tags",
            "db,pool",
            "--priority",
            "9",
        ],
    )
    assert result.exit_code == 0, result.output
    assert seen["tags"] == ["db", "pool"]
    assert seen["priority"] == 9
