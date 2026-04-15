from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import click
from dotenv import load_dotenv
from rich.console import Console

from .auth import store_token
from .client import AgentOpsClient, AgentOpsError
from .commands import config as config_commands
from .commands import diagnose as diagnose_commands
from .commands import knowledge as knowledge_commands
from .commands import session as session_commands
from .config import AppConfig, ConfigManager, DEFAULT_CONFIG_PATH
from .formatters.output import print_json


@dataclass(slots=True)
class AppContext:
    config_manager: ConfigManager
    settings: AppConfig
    client: AgentOpsClient
    console: Console
    verbose: bool
    json_output: bool


def build_app_context(
    config_path: str, api_url: str | None, verbose: bool, json_output: bool
) -> AppContext:
    load_dotenv()
    manager = ConfigManager.from_path(config_path)
    settings = manager.load()
    if api_url:
        settings.api_url = api_url
    console = Console(stderr=False)
    client = AgentOpsClient(api_url=settings.api_url, token=settings.auth.token)
    return AppContext(
        config_manager=manager,
        settings=settings,
        client=client,
        console=console,
        verbose=verbose,
        json_output=json_output,
    )


@click.group(context_settings={"help_option_names": ["-h", "--help"]})
@click.option(
    "--config",
    "config_path",
    default=str(DEFAULT_CONFIG_PATH),
    show_default=True,
    type=click.Path(dir_okay=False, path_type=Path),
    help="Config file path.",
)
@click.option("--api-url", help="API base URL.")
@click.option("--verbose", "verbose", is_flag=True, help="Verbose output.")
@click.option("--json", "json_output", is_flag=True, help="Output as JSON.")
@click.pass_context
def cli(
    ctx: click.Context,
    config_path: Path,
    api_url: str | None,
    verbose: bool,
    json_output: bool,
) -> None:
    """AgentOps CLI for diagnosis, knowledge, and session workflows."""
    ctx.obj = build_app_context(str(config_path), api_url, verbose, json_output)


@cli.command("login")
@click.option("--api-key", required=True, help="API key used for authentication.")
@click.option(
    "--save/--no-save", default=True, show_default=True, help="Persist token to config."
)
@click.pass_obj
def login_command(app: AppContext, api_key: str, save: bool) -> None:
    """Authenticate against the AgentOps API."""
    try:
        payload = app.client.login(api_key)
    except AgentOpsError as exc:
        raise click.ClickException(str(exc)) from exc

    token = payload["token"]
    expires_in = int(payload.get("expiresIn", 3600))
    if save:
        app.settings = store_token(app.config_manager, app.settings, token, expires_in)
        app.client.token = token

    response = {
        "saved": save,
        "expiresIn": expires_in,
        "expiresAt": app.settings.auth.expires_at.isoformat()
        if app.settings.auth.expires_at
        else None,
    }
    if app.json_output:
        print_json(app.console, response)
        return
    app.console.print("[green]Login successful.[/green]")
    app.console.print(f"Token expires in [bold]{expires_in}[/bold] seconds.")
    if response["expiresAt"]:
        app.console.print(f"Stored until [bold]{response['expiresAt']}[/bold].")


diagnose_commands.register(cli)
knowledge_commands.register(cli)
session_commands.register(cli)
config_commands.register(cli)


def main() -> None:
    cli()
