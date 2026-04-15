from __future__ import annotations

import json
from typing import Any

from rich.console import Console
from rich.panel import Panel
from rich.table import Table


def print_json(console: Console, data: Any) -> None:
    console.print_json(json.dumps(data, ensure_ascii=False, default=str))


def knowledge_table(entries: list[dict[str, Any]]) -> Table:
    table = Table(title="Knowledge Entries")
    table.add_column("ID", style="cyan", no_wrap=True)
    table.add_column("Category", style="magenta")
    table.add_column("Title", style="bold")
    table.add_column("Priority", justify="right")
    table.add_column("Tags", style="green")
    for entry in entries:
        tags = ", ".join(entry.get("tags") or [])
        table.add_row(
            str(entry.get("id", "-")),
            str(entry.get("category", "-")),
            str(entry.get("title", "-")),
            str(entry.get("priority", "-")),
            tags,
        )
    return table


def history_table(page_data: dict[str, Any]) -> Table:
    table = Table(title="Diagnosis History")
    table.add_column("Session ID", style="cyan")
    table.add_column("Status")
    table.add_column("Intent")
    table.add_column("Root Cause")
    table.add_column("Created At")
    for item in page_data.get("content", []):
        table.add_row(
            str(item.get("sessionId", "-")),
            str(item.get("status", "-")),
            str(item.get("intentType", "-")),
            str(item.get("rootCause", "-")),
            str(item.get("createdAt", "-")),
        )
    return table


def session_panel(payload: dict[str, Any]) -> Panel:
    session = payload.get("session", payload)
    stats = payload.get("traceStats", {})
    lines = [
        f"[bold]Session:[/bold] {session.get('sessionId', '-')}",
        f"[bold]Status:[/bold] {session.get('status', '-')}",
        f"[bold]Intent:[/bold] {session.get('intentType', '-')}",
        f"[bold]Latency:[/bold] {session.get('totalLatencyMs', '-')}",
        f"[bold]Root Cause:[/bold] {session.get('rootCause', '-')}",
        f"[bold]Steps:[/bold] {stats.get('totalSteps', '-')}",
    ]
    return Panel("\n".join(lines), title="Diagnosis Session", expand=False)
