from __future__ import annotations

from typing import Any

import click

from agentops.formatters.output import history_table, print_json, session_panel
from agentops.formatters.trace import timeline_group, trace_table


def register(group: click.Group) -> None:
    @group.group("session")
    def session_group() -> None:
        """Inspect diagnosis sessions and traces."""

    @session_group.command("show")
    @click.argument("session_id")
    @click.pass_obj
    def session_show(ctx: Any, session_id: str) -> None:
        payload = ctx.client.get_session(session_id)
        if ctx.json_output:
            print_json(ctx.console, payload)
        else:
            ctx.console.print(session_panel(payload))

    @session_group.command("trace")
    @click.argument("session_id")
    @click.option(
        "--format",
        "output_format",
        type=click.Choice(["table", "json"]),
        default="table",
    )
    @click.pass_obj
    def session_trace(ctx: Any, session_id: str, output_format: str) -> None:
        items = ctx.client.get_trace(session_id)
        if ctx.json_output or output_format == "json":
            print_json(ctx.console, items)
        else:
            ctx.console.print(trace_table(items))

    @session_group.command("timeline")
    @click.argument("session_id")
    @click.pass_obj
    def session_timeline(ctx: Any, session_id: str) -> None:
        items = ctx.client.get_timeline(session_id)
        if ctx.json_output:
            print_json(ctx.console, items)
        else:
            ctx.console.print(timeline_group(items))

    @session_group.command("history")
    @click.option("--page", type=int, default=0, show_default=True)
    @click.option("--size", type=int, default=20, show_default=True)
    @click.pass_obj
    def session_history(ctx: Any, page: int, size: int) -> None:
        payload = ctx.client.get_history(page=page, size=size)
        if ctx.json_output:
            print_json(ctx.console, payload)
        else:
            ctx.console.print(history_table(payload))
