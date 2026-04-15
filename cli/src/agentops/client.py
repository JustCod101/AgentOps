from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Any, AsyncIterator

import httpx

from .sse import SSEEvent, iter_sse


class AgentOpsError(RuntimeError):
    pass


@dataclass(slots=True)
class AgentOpsClient:
    api_url: str
    token: str | None = None
    timeout: float = 30.0
    verify: bool = True

    def __post_init__(self) -> None:
        self.api_url = self.api_url.rstrip("/")

    def _headers(self, auth_required: bool = False) -> dict[str, str]:
        headers = {"Accept": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        elif auth_required:
            raise AgentOpsError("Authentication required. Run `agentops login` first.")
        return headers

    def _url(self, path: str) -> str:
        return f"{self.api_url}{path}"

    def _unwrap(self, payload: Any) -> Any:
        if isinstance(payload, dict) and "code" in payload and "message" in payload:
            code = payload.get("code", 200)
            if code != 200:
                raise AgentOpsError(
                    payload.get("message") or f"Request failed with code {code}"
                )
            return payload.get("data")
        return payload

    def _request(
        self, method: str, path: str, *, auth_required: bool = False, **kwargs: Any
    ) -> Any:
        headers = kwargs.pop("headers", {})
        merged_headers = {**self._headers(auth_required=auth_required), **headers}
        with httpx.Client(timeout=self.timeout, verify=self.verify) as client:
            response = client.request(
                method, self._url(path), headers=merged_headers, **kwargs
            )
        return self._handle_response(response)

    def _handle_response(self, response: httpx.Response) -> Any:
        try:
            payload = response.json()
        except ValueError:
            response.raise_for_status()
            return response.text

        if response.status_code >= 400:
            if isinstance(payload, dict):
                message = (
                    payload.get("message") or payload.get("error") or response.text
                )
            else:
                message = response.text
            raise AgentOpsError(message)
        return self._unwrap(payload)

    def login(self, api_key: str) -> dict[str, Any]:
        payload = self._request("POST", "/api/v1/auth/login", json={"apiKey": api_key})
        if not isinstance(payload, dict) or "token" not in payload:
            raise AgentOpsError("Login response did not include a token.")
        return payload

    def refresh(self) -> dict[str, Any]:
        payload = self._request("POST", "/api/v1/auth/refresh", auth_required=True)
        if not isinstance(payload, dict) or "token" not in payload:
            raise AgentOpsError("Refresh response did not include a token.")
        return payload

    def get_session(self, session_id: str) -> dict[str, Any]:
        return self._request(
            "GET", f"/api/v1/diagnosis/{session_id}", auth_required=True
        )

    def get_trace(self, session_id: str) -> list[dict[str, Any]]:
        return self._request(
            "GET", f"/api/v1/diagnosis/{session_id}/trace", auth_required=True
        )

    def get_timeline(self, session_id: str) -> list[dict[str, Any]]:
        return self._request(
            "GET", f"/api/v1/diagnosis/{session_id}/timeline", auth_required=True
        )

    def get_history(self, page: int = 0, size: int = 20) -> dict[str, Any]:
        return self._request(
            "GET",
            "/api/v1/diagnosis/history",
            auth_required=True,
            params={"page": page, "size": size},
        )

    def list_knowledge(self, category: str | None = None) -> list[dict[str, Any]]:
        params = {"category": category} if category else None
        return self._request(
            "GET", "/api/v1/knowledge", auth_required=True, params=params
        )

    def search_knowledge(
        self,
        keyword: str,
        *,
        category: str | None = None,
        limit: int = 5,
    ) -> list[dict[str, Any]]:
        params = {"keyword": keyword, "limit": limit}
        if category:
            params["category"] = category
        return self._request(
            "GET", "/api/v1/knowledge/search", auth_required=True, params=params
        )

    def get_knowledge(self, knowledge_id: int) -> dict[str, Any]:
        return self._request(
            "GET", f"/api/v1/knowledge/{knowledge_id}", auth_required=True
        )

    def create_knowledge(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self._request(
            "POST", "/api/v1/knowledge", auth_required=True, json=payload
        )

    def update_knowledge(
        self, knowledge_id: int, payload: dict[str, Any]
    ) -> dict[str, Any]:
        return self._request(
            "PUT", f"/api/v1/knowledge/{knowledge_id}", auth_required=True, json=payload
        )

    def delete_knowledge(self, knowledge_id: int) -> Any:
        return self._request(
            "DELETE", f"/api/v1/knowledge/{knowledge_id}", auth_required=True
        )

    async def stream_diagnosis(self, query: str) -> AsyncIterator[SSEEvent]:
        headers = {**self._headers(auth_required=True), "Accept": "text/event-stream"}
        async with httpx.AsyncClient(timeout=None, verify=self.verify) as client:
            async with client.stream(
                "POST",
                self._url("/api/v1/diagnosis/stream"),
                headers=headers,
                json={"query": query},
            ) as response:
                response.raise_for_status()
                async for event in iter_sse(response.aiter_lines()):
                    yield event

    async def replay_session_events(
        self,
        session_id: str,
        *,
        poll_interval: float = 1.5,
    ) -> AsyncIterator[SSEEvent]:
        seen_steps: set[int] = set()
        completed = False
        while not completed:
            traces = self.get_trace(session_id)
            for trace in traces:
                step_index = trace.get("stepIndex")
                if step_index in seen_steps:
                    continue
                seen_steps.add(step_index)
                yield SSEEvent(event="trace", data=trace, raw_data="")

            session_data = self.get_session(session_id)
            session = session_data.get("session", session_data)
            status = session.get("status") if isinstance(session, dict) else None
            if status in {"COMPLETED", "FAILED"}:
                yield SSEEvent(
                    event="done",
                    data={"sessionId": session_id, "status": status},
                    raw_data="",
                )
                completed = True
                break

            await asyncio.sleep(poll_interval)
