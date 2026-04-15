from __future__ import annotations

from typing import Any

import click

from agentops.formatters.output import print_json


def register(group: click.Group) -> None:
    @group.group("config")
    def config_group() -> None:
        """Manage local CLI configuration."""

    @config_group.command("init")
    @click.option("--api-url")
    @click.pass_obj
    def config_init(ctx: Any, api_url: str | None) -> None:
        config = ctx.config_manager.init(api_url=api_url)
        if ctx.json_output:
            print_json(ctx.console, config.model_dump(by_alias=True, exclude_none=True))
        else:
            ctx.console.print(
                f"[green]Initialized config[/green] at {ctx.config_manager.path}"
            )

    @config_group.command("show")
    @click.pass_obj
    def config_show(ctx: Any) -> None:
        payload = ctx.settings.model_dump(by_alias=True, exclude_none=True)
        print_json(ctx.console, payload)

    @config_group.command("set")
    @click.argument("key")
    @click.argument("value")
    @click.pass_obj
    def config_set(ctx: Any, key: str, value: str) -> None:
        updated = ctx.config_manager.set_value(key, value)
        ctx.settings = updated
        print_json(ctx.console, updated.model_dump(by_alias=True, exclude_none=True))
