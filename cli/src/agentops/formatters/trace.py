from __future__ import annotations

from typing import Any

from rich.console import Group
from rich.table import Table
from rich.text import Text


STEP_STYLES = {
    "THOUGHT": "cyan",
    "ACTION": "yellow",
    "OBSERVATION": "green",
    "REFLECTION": "magenta",
    "DECISION": "bold blue",
}


def trace_table(items: list[dict[str, Any]]) -> Table:
    table = Table(title="Trace")
    table.add_column("#", justify="right")
    table.add_column("Time")
    table.add_column("Agent")
    table.add_column("Type")
    table.add_column("Content")
    for item in items:
        step_type = str(item.get("stepType", "-"))
        table.add_row(
            str(item.get("stepIndex", "-")),
            str(item.get("timestamp", item.get("createdAt", "-"))),
            str(item.get("agentName", "-")),
            f"[{STEP_STYLES.get(step_type, 'white')}]{step_type}[/]",
            str(item.get("content", "-")),
        )
    return table


def render_trace_event(event: dict[str, Any]) -> Text:
    step_type = str(event.get("stepType", "TRACE"))
    style = STEP_STYLES.get(step_type, "white")
    prefix = (
        f"[{event.get('timestamp', '-')}] {event.get('agentName', '?')}/{step_type}: "
    )
    text = Text(prefix, style=style)
    text.append(str(event.get("content", "")))
    return text


def timeline_group(items: list[dict[str, Any]]) -> Group:
    return Group(*(render_trace_event(item) for item in items))
