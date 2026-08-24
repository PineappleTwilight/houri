#!/usr/bin/env python3
"""Evaluate TODO.md changes between the previous commit and HEAD, then post a
summary embed to a Discord webhook.

Events detected per category:
  - Category added / category removed
  - Task completed (unchecked -> checked)
  - Task reopened (checked -> unchecked)
  - Task added / task removed

Usage (CI):
  python3 .github/scripts/todo_discord_notify.py

Usage (local testing, no network):
  python3 .github/scripts/todo_discord_notify.py old_TODO.md new_TODO.md

Requires the DISCORD_WEBHOOK_URL environment variable to actually send.
Without it, the payload is printed to stdout instead (dry run).
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

CHECKBOX_RE = re.compile(r"^(\s*)-\s\[( |x|X)\]\s+(.*)$")
CATEGORY_RE = re.compile(r"^##\s+(.+?)\s*$")

MAX_FIELDS = 10
FIELD_VALUE_BUDGET = 1000  # stay safely under Discord's 1024 char field limit
ITEM_CLIP = 180  # max rendered length of a single task line

# (key, heading, emoji) order of sections inside each category field
SECTIONS = [
    ("completed", "Completed", "✅"),
    ("reopened", "Reopened", "🔁"),
    ("added_tasks", "Added", "➕"),
    ("removed_tasks", "Removed", "➖"),
]


def read_old_todo() -> str:
    """Read TODO.md as of HEAD~1; empty string if the file did not exist."""
    result = subprocess.run(
        ["git", "show", "HEAD~1:TODO.md"],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        # File was newly added in this commit (or history was rewritten shallowly).
        return ""
    return result.stdout


def parse_todo(text: str) -> dict[str, list[tuple[int, str, bool]]]:
    """Parse TODO.md into {category: [(nesting_depth, task_text, checked), ...]}."""
    categories: dict[str, list[tuple[int, str, bool]]] = {}
    current = "General"
    categories[current] = []

    for line in text.splitlines():
        cat_match = CATEGORY_RE.match(line)
        if cat_match:
            current = cat_match.group(1)
            categories.setdefault(current, [])
            continue
        box_match = CHECKBOX_RE.match(line)
        if box_match:
            depth = len(box_match.group(1)) // 2
            checked = box_match.group(2).lower() == "x"
            task = re.sub(r"\s+", " ", box_match.group(3)).strip()
            if task:
                categories[current].append((depth, task, checked))
    return categories


def evaluate(old: dict, new: dict) -> tuple[list[str], list[str], dict[str, dict[str, list[str]]]]:
    """Return (added_categories, removed_categories, per-category change details)."""
    old_cats = set(old)
    new_cats = set(new)
    added_categories = sorted(new_cats - old_cats)
    removed_categories = sorted(old_cats - new_cats)

    details: dict[str, dict[str, list[str]]] = {}

    def blank() -> dict[str, list[str]]:
        return {key: [] for key, _, _ in SECTIONS}

    # Categories that exist in both versions: diff their tasks by text.
    for cat in sorted(new_cats & old_cats):
        old_map = {task: (depth, checked) for depth, task, checked in old[cat]}
        new_map = {task: (depth, checked) for depth, task, checked in new[cat]}
        entry = blank()
        for task, (_d, was_checked) in old_map.items():
            if task not in new_map:
                entry["removed_tasks"].append(task)
            elif was_checked and not new_map[task][1]:
                entry["reopened"].append(task)
        for task, (depth, checked) in new_map.items():
            if task not in old_map:
                entry["added_tasks"].append(task)
            elif checked and not old_map[task][1]:
                entry["completed"].append(task)

        def render(tasks_with_depth: list[tuple[int, str]]) -> list[str]:
            return ["  " * depth + text for depth, text in sorted(tasks_with_depth)]

        entry["completed"] = render(
            [(new_map[t][0], t) for t in entry["completed"]]
        )
        entry["reopened"] = render([(new_map[t][0], t) for t in entry["reopened"]])
        entry["added_tasks"] = render(
            [(new_map[t][0], t) for t in entry["added_tasks"]]
        )
        entry["removed_tasks"] = render(
            [(old_map[t][0], t) for t in entry["removed_tasks"]]
        )

        if any(entry.values()):
            details[cat] = entry

    # Categories that only exist in the new version: everything in them is "added".
    for cat in added_categories:
        entry = blank()
        entry["added_tasks"] = ["  " * d + t for d, t, _c in new[cat]]
        if any(entry.values()):
            details[cat] = entry

    # Categories that only exist in the old version: everything in them is "removed".
    for cat in removed_categories:
        entry = blank()
        entry["removed_tasks"] = ["  " * d + t for d, t, _c in old[cat]]
        if any(entry.values()):
            details[cat] = entry

    return added_categories, removed_categories, details


def clip_lines(lines: list[str], emoji: str, heading: str) -> list[str]:
    """Render one section within the Discord field-value character budget."""
    out = [f"{emoji} **{heading}:**"]
    used = sum(len(l) + 1 for l in out)
    shown = 0
    for i, item in enumerate(lines):
        text = item.replace("\n", " ")
        if len(text) > ITEM_CLIP:
            text = text[: ITEM_CLIP - 1] + "…"
        line = f"- {text}"
        remaining_after = len(lines) - i - 1
        if used + len(line) + 1 > FIELD_VALUE_BUDGET - 60 and (
            remaining_after > 0 or used + len(line) + 1 > FIELD_VALUE_BUDGET
        ):
            hidden = remaining_after + 1
            out.append(f"- *…and {hidden} more*")
            return out
        out.append(line)
        used += len(line) + 1
        shown += 1
    return out if shown else []


def build_embed(
    added_categories: list[str],
    removed_categories: list[str],
    details: dict[str, dict[str, list[str]]],
) -> dict:
    repo = os.environ.get("GITHUB_REPOSITORY", "unknown/repo")
    sha = os.environ.get("GITHUB_SHA", "")[:7]
    server = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
    commit_message = (os.environ.get("COMMIT_MESSAGE") or "").strip().splitlines()
    commit_title = commit_message[0] if commit_message else "Updated TODO.md"
    author = (
        os.environ.get("COMMIT_AUTHOR")
        or os.environ.get("COMMIT_AUTHOR_NAME")
        or "unknown"
    )

    has_positive = bool(
        added_categories or any(d["completed"] or d["added_tasks"] for d in details.values())
    )
    has_negative = bool(
        removed_categories or any(d["reopened"] or d["removed_tasks"] for d in details.values())
    )
    if has_positive and has_negative:
        color = 0xFEE75C  # mixed
    elif has_negative:
        color = 0xED4245  # removals only
    else:
        color = 0x57F287  # progress only

    fields = []

    if added_categories or removed_categories:
        cat_lines = []
        cat_lines += [f"➕ `{c}`" for c in added_categories]
        cat_lines += [f"🗑️ `{c}`" for c in removed_categories]
        fields.append({"name": "Categories", "value": "\n".join(cat_lines)[:1024]})

    overflow = []
    for cat, entry in details.items():
        section_lines: list[str] = []
        for key, heading, emoji in SECTIONS:
            if entry[key]:
                section_lines.extend(clip_lines(entry[key], emoji, heading))
        if not section_lines:
            continue
        if len(fields) < MAX_FIELDS:
            fields.append({"name": cat[:250], "value": "\n".join(section_lines)[:1024]})
        else:
            counts = ", ".join(
                f"{len(entry[k])} {heading.lower()}"
                for k, heading, _e in SECTIONS
                if entry[k]
            )
            overflow.append(f"- **{cat}**: {counts}")
    if overflow:
        fields.append(
            {"name": "More categories", "value": "\n".join(overflow)[:1024]}
        )

    embed = {
        "title": "📋 TODO.md updated",
        "url": f"{server}/{repo}/blob/master/TODO.md",
        "color": color,
        "fields": fields,
        "footer": {"text": f"{repo} @ {sha}"},
        "description": f"**{author}** — {commit_title}",
    }
    return embed


def send(embed: dict) -> None:
    webhook = os.environ.get("DISCORD_WEBHOOK_URL", "").strip()
    payload = {
        "username": "Houri TODO",
        "embeds": [embed],
    }
    if not webhook:
        print("DISCORD_WEBHOOK_URL not set - dry run. Payload:")
        print(json.dumps(payload, indent=2))
        return

    request = urllib.request.Request(
        webhook,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            # Discord sits behind Cloudflare; the default Python-urllib UA is
            # bot-flagged and rejected with 403 "error code: 1010" on CI IPs.
            "User-Agent": "Houri-TODO-Notifier/1.0 (GitHub Actions; +https://github.com/PineappleTwilight/komikku-pineapple)",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            print(f"Discord webhook responded {response.status}")
    except urllib.error.HTTPError as error:
        print(f"Discord webhook error {error.code}: {error.read().decode(errors='replace')}")
        raise


def main() -> int:
    args = sys.argv[1:]
    if len(args) == 2:
        old_text = Path(args[0]).read_text(encoding="utf-8")
        new_text = Path(args[1]).read_text(encoding="utf-8")
    else:
        old_text = read_old_todo()
        todo_path = Path("TODO.md")
        if todo_path.exists():
            new_text = todo_path.read_text(encoding="utf-8")
        else:
            new_text = ""

    old = parse_todo(old_text)
    new = parse_todo(new_text)

    added_categories, removed_categories, details = evaluate(old, new)

    if not added_categories and not removed_categories and not details:
        print("No notable TODO.md changes detected; nothing to send.")
        return 0

    embed = build_embed(added_categories, removed_categories, details)
    print(json.dumps({"embeds": [embed]}, indent=2))  # visible in Actions log
    send(embed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
