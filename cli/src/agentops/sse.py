from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, AsyncIterator


@dataclass(slots=True)
class SSEEvent:
    event: str
    data: Any
    raw_data: str


async def iter_sse(lines: AsyncIterator[str]) -> AsyncIterator[SSEEvent]:
    event_name = "message"
    data_lines: list[str] = []

    async for raw_line in lines:
        line = raw_line.strip("\n")
        if not line.strip():
            if data_lines:
                raw_data = "\n".join(data_lines)
                try:
                    payload = json.loads(raw_data)
                except json.JSONDecodeError:
                    payload = raw_data
                yield SSEEvent(event=event_name, data=payload, raw_data=raw_data)
            event_name = "message"
            data_lines = []
            continue

        if line.startswith(":"):
            continue
        if line.startswith("event:"):
            event_name = line.split(":", 1)[1].strip() or "message"
        elif line.startswith("data:"):
            data_lines.append(line.split(":", 1)[1].strip())

    if data_lines:
        raw_data = "\n".join(data_lines)
        try:
            payload = json.loads(raw_data)
        except json.JSONDecodeError:
            payload = raw_data
        yield SSEEvent(event=event_name, data=payload, raw_data=raw_data)
