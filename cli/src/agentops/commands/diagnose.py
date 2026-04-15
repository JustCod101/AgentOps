from __future__ import annotations

import asyncio
from typing import Any

import click
from rich.console import Console

from agentops.client import AgentOpsError
from agentops.formatters.output import print_json, session_panel
from agentops.formatters.trace import render_trace_event


def register(group: click.Group) -> None:
    @group.command("diagnose")
    @click.argument("query", required=False)
    @click.option("--session-id", help="Reuse an existing diagnosis session ID.")
    @click.option("--stream/--no-stream", default=True, show_default=True)
    @click.pass_obj
    def diagnose_command(
        ctx: Any, query: str | None, session_id: str | None, stream: bool
    ) -> None:
        """Run a diagnosis or follow an existing diagnosis session."""
        console: Console = ctx.console
        client = ctx.client

        if not session_id and not query:
            raise click.UsageError("Provide QUERY or --session-id.")

        if stream:
            if session_id:
                asyncio.run(_stream_existing_session(ctx, session_id))
                return
            asyncio.run(_stream_new_diagnosis(ctx, query or ""))
            return

        if session_id:
            payload = client.get_session(session_id)
            _render_session(ctx, payload)
            return

        session_payload = asyncio.run(_run_non_streaming_diagnosis(ctx, query or ""))
        _render_session(ctx, session_payload)
        if not ctx.json_output:
            console.print("[green]Diagnosis completed.[/green]")

    @group.command("stream")
    @click.option("--session-id", required=True, help="Existing diagnosis session ID.")
    @click.pass_obj
    def stream_command(ctx: Any, session_id: str) -> None:
        """Stream events for an existing diagnosis session."""
        asyncio.run(_stream_existing_session(ctx, session_id))


async def _stream_new_diagnosis(ctx: Any, query: str) -> None:
    console: Console = ctx.console
    session_id: str | None = None
    result_payload: dict[str, Any] | None = None
    try:
        async for event in ctx.client.stream_diagnosis(query):
            if event.event == "session":
                session_id = event.data.get("sessionId")
                console.print(f"[bold green]Session:[/bold green] {session_id}")
            elif event.event == "trace":
                console.print(render_trace_event(event.data))
            elif event.event == "result":
                result_payload = event.data
                if ctx.json_output:
                    print_json(console, event.data)
                else:
                    console.print("[bold blue]Diagnosis result received.[/bold blue]")
                    if event.data.get("rootCause"):
                        console.print(
                            f"[bold]Root cause:[/bold] {event.data['rootCause']}"
                        )
            elif event.event == "error":
                raise AgentOpsError(
                    str(event.data.get("error", "Unknown diagnosis error"))
                )
            elif event.event == "done":
                console.print(
                    f"[green]Session complete:[/green] {event.data.get('status', '-')}"
                )

        if result_payload and not ctx.json_output:
            final_payload = ctx.client.get_session(
                result_payload.get("sessionId", session_id or "")
            )
            console.print(session_panel(final_payload))
    except AgentOpsError as exc:
        raise click.ClickException(str(exc)) from exc


async def _run_non_streaming_diagnosis(ctx: Any, query: str) -> dict[str, Any]:
    session_id: str | None = None
    result_payload: dict[str, Any] | None = None
    async for event in ctx.client.stream_diagnosis(query):
        if event.event == "session":
            session_id = event.data.get("sessionId")
        elif event.event == "result":
            result_payload = event.data
        elif event.event == "error":
            raise click.ClickException(
                str(event.data.get("error", "Unknown diagnosis error"))
            )
        elif event.event == "done":
            break

    target_session_id = (result_payload or {}).get("sessionId") or session_id
    if not target_session_id:
        raise click.ClickException(
            "Diagnosis completed without returning a session ID."
        )
    return ctx.client.get_session(target_session_id)


async def _stream_existing_session(ctx: Any, session_id: str) -> None:
    console: Console = ctx.console
    try:
        async for event in ctx.client.replay_session_events(session_id):
            if event.event == "trace":
                console.print(render_trace_event(event.data))
            elif event.event == "done":
                console.print(
                    f"[green]Session complete:[/green] {event.data.get('status', '-')}"
                )
        if not ctx.json_output:
            console.print(session_panel(ctx.client.get_session(session_id)))
    except AgentOpsError as exc:
        raise click.ClickException(str(exc)) from exc


def _render_session(ctx: Any, payload: dict[str, Any]) -> None:
    if ctx.json_output:
        print_json(ctx.console, payload)
    else:
        ctx.console.print(session_panel(payload))
