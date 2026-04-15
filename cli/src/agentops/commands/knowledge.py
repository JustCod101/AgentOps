from __future__ import annotations

from typing import Any

import click

from agentops.formatters.output import knowledge_table, print_json


def _csv_to_list(value: str | None) -> list[str]:
    if not value:
        return []
    return [item.strip() for item in value.split(",") if item.strip()]


def register(group: click.Group) -> None:
    @group.group("knowledge")
    def knowledge_group() -> None:
        """Manage the AgentOps knowledge base."""

    @knowledge_group.command("list")
    @click.option("--category")
    @click.option("--limit", type=int, default=20, show_default=True)
    @click.pass_obj
    def knowledge_list(ctx: Any, category: str | None, limit: int) -> None:
        items = ctx.client.list_knowledge(category=category)[:limit]
        if ctx.json_output:
            print_json(ctx.console, items)
        else:
            ctx.console.print(knowledge_table(items))

    @knowledge_group.command("search")
    @click.argument("keyword")
    @click.option("--limit", type=int, default=5, show_default=True)
    @click.option("--category")
    @click.pass_obj
    def knowledge_search(
        ctx: Any, keyword: str, limit: int, category: str | None
    ) -> None:
        items = ctx.client.search_knowledge(keyword, limit=limit, category=category)
        if ctx.json_output:
            print_json(ctx.console, items)
        else:
            ctx.console.print(knowledge_table(items))

    @knowledge_group.command("add")
    @click.option("--title", required=True)
    @click.option("--category", required=True)
    @click.option("--content", required=True)
    @click.option("--tags")
    @click.option("--priority", type=int, default=0, show_default=True)
    @click.pass_obj
    def knowledge_add(
        ctx: Any,
        title: str,
        category: str,
        content: str,
        tags: str | None,
        priority: int,
    ) -> None:
        payload = {
            "title": title,
            "category": category,
            "content": content,
            "tags": _csv_to_list(tags),
            "priority": priority,
        }
        created = ctx.client.create_knowledge(payload)
        if ctx.json_output:
            print_json(ctx.console, created)
        else:
            ctx.console.print(
                f"[green]Created knowledge entry[/green] #{created.get('id', '-')}: {created.get('title', '-')}"
            )

    @knowledge_group.command("update")
    @click.argument("knowledge_id", type=int)
    @click.option("--title")
    @click.option("--category")
    @click.option("--content")
    @click.option("--tags")
    @click.option("--priority", type=int)
    @click.pass_obj
    def knowledge_update(
        ctx: Any,
        knowledge_id: int,
        title: str | None,
        category: str | None,
        content: str | None,
        tags: str | None,
        priority: int | None,
    ) -> None:
        payload = {
            key: value
            for key, value in {
                "title": title,
                "category": category,
                "content": content,
                "tags": _csv_to_list(tags) if tags is not None else None,
                "priority": priority,
            }.items()
            if value is not None
        }
        updated = ctx.client.update_knowledge(knowledge_id, payload)
        if ctx.json_output:
            print_json(ctx.console, updated)
        else:
            ctx.console.print(
                f"[green]Updated knowledge entry[/green] #{updated.get('id', knowledge_id)}"
            )

    @knowledge_group.command("delete")
    @click.argument("knowledge_id", type=int)
    @click.option("--force", is_flag=True, help="Delete without confirmation.")
    @click.pass_obj
    def knowledge_delete(ctx: Any, knowledge_id: int, force: bool) -> None:
        if not force and not click.confirm(f"Delete knowledge entry {knowledge_id}?"):
            raise click.Abort()
        ctx.client.delete_knowledge(knowledge_id)
        if ctx.json_output:
            print_json(ctx.console, {"deleted": knowledge_id})
        else:
            ctx.console.print(f"[green]Deleted knowledge entry[/green] #{knowledge_id}")
