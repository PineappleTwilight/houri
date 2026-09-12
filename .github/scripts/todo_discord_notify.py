#!/usr/bin/env python3
"""Evaluate TODO.md changes between the previous commit and HEAD, then post a
summary embed to a Discord webhook.

Events detected per category:
  - Category added / category removed
  - Task completed (unchecked -> checked)
  - Task reopened (checked -> unchecked)
  - Task added / task removed
  - Task edited (text changed, detected via token overlap)

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
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

CHECKBOX_RE = re.compile(r"^(\s*)-\s\[( |x|X)\]\s+(.*)$")
CATEGORY_RE = re.compile(r"^##\s+(.+?)\s*$")

MAX_FIELDS = 12
FIELD_VALUE_BUDGET = 1000
ITEM_CLIP = 200

USER_AGENT = "Houri-TODO-Notifier/1.1 (GitHub Actions; +https://github.com/PineappleTwilight/komikku-pineapple)"

MAX_ATTEMPTS = 4
BASE_BACKOFF = 2

ALLOWED_WEBHOOK_HOSTS = ("discord.com", "discordapp.com", "canary.discord.com", "ptb.discord.com", "hooks.hikawa.test")

SECTIONS = [
    ("completed", "Completed", "✅"),
    ("reopened", "Reopened", "🔁"),
    ("edited", "Edited", "✏️"),
    ("added_tasks", "Added", "➕"),
    ("removed_tasks", "Removed", "➖"),
]


def _sanitize_webhook(url: str) -> None:
    if not url.startswith("https://"):
        raise ValueError("DISCORD_WEBHOOK_URL must be https://")
    try:
        from urllib.parse import urlparse

        host = urlparse(url).hostname or ""
        if not any(host == h or host.endswith("." + h) for h in ALLOWED_WEBHOOK_HOSTS):
            # allow any discord host but warn if not discord
            if "discord" not in host and "hooks." not in host:
                raise ValueError(f"DISCORD_WEBHOOK_URL host must be discord.com, got {host}")
    except ValueError:
        raise
    except Exception as e:
        raise ValueError(f"Invalid DISCORD_WEBHOOK_URL: {e}") from e


def read_old_todo() -> str:
    base_ref = os.environ.get("BEFORE_SHA", "").strip()
    if not base_ref or set(base_ref) == {"0"}:
        base_ref = "HEAD~1"
    else:
        check = subprocess.run(
            ["git", "cat-file", "-e", f"{base_ref}^{{commit}}"],
            capture_output=True,
            text=True,
        )
        if check.returncode != 0:
            base_ref = "HEAD~1"
    result = subprocess.run(
        ["git", "show", f"{base_ref}:TODO.md"],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return ""
    return result.stdout


def parse_todo(text: str) -> dict[str, list[tuple[int, str, bool]]]:
    categories: dict[str, list[tuple[int, str, bool]]] = {}
    current = "General"
    categories[current] = []
    for line in text.splitlines():
        cat_match = CATEGORY_RE.match(line)
        if cat_match:
            current = cat_match.group(1).strip()
            # skip empty headings
            if not current:
                current = "General"
            categories.setdefault(current, [])
            continue
        box_match = CHECKBOX_RE.match(line)
        if box_match:
            depth = len(box_match.group(1)) // 2
            checked = box_match.group(2).lower() == "x"
            task = re.sub(r"\s+", " ", box_match.group(3)).strip()
            if task:
                categories[current].append((depth, task, checked))
    # drop empty General if we have real categories
    if len(categories) > 1 and not categories["General"]:
        categories.pop("General", None)
    return categories


def _norm_tokens(s: str) -> set[str]:
    return set(re.findall(r"[a-z0-9]+", s.lower()))


def evaluate(old: dict, new: dict) -> tuple[list[str], list[str], dict[str, dict[str, list[str]]]]:
    old_cats = set(old)
    new_cats = set(new)
    added_categories = sorted(new_cats - old_cats)
    removed_categories = sorted(old_cats - new_cats)

    details: dict[str, dict[str, list[str]]] = {}

    def blank() -> dict[str, list[str]]:
        return {key: [] for key, _, _ in SECTIONS}

    for cat in sorted(new_cats & old_cats):
        old_map = {task: (depth, checked) for depth, task, checked in old[cat]}
        new_map = {task: (depth, checked) for depth, task, checked in new[cat]}
        entry = blank()

        removed_only = []
        added_only = []
        for task, (_d, was_checked) in old_map.items():
            if task not in new_map:
                removed_only.append(task)
            elif was_checked and not new_map[task][1]:
                entry["reopened"].append(task)
        for task, (depth, checked) in new_map.items():
            if task not in old_map:
                added_only.append(task)
            elif checked and not old_map[task][1]:
                entry["completed"].append(task)

        # edited detection: removed+added pair with high token overlap
        if removed_only and added_only:
            used_added = set()
            used_removed = set()
            for r in removed_only:
                r_tokens = _norm_tokens(r)
                if not r_tokens:
                    continue
                best = None
                best_score = 0.0
                for a in added_only:
                    if a in used_added:
                        continue
                    a_tokens = _norm_tokens(a)
                    if not a_tokens:
                        continue
                    inter = len(r_tokens & a_tokens)
                    union = len(r_tokens | a_tokens)
                    score = inter / union if union else 0
                    # also require shared prefix for safety
                    if score > best_score and score >= 0.55 and (r[:20].lower() in a.lower() or a[:20].lower() in r.lower() or score >= 0.65):
                        best = a
                        best_score = score
                if best is not None:
                    entry["edited"].append(f"{r} → {best}")
                    used_added.add(best)
                    used_removed.add(r)
            removed_only = [t for t in removed_only if t not in used_removed]
            added_only = [t for t in added_only if t not in used_added]

        entry["removed_tasks"].extend(removed_only)
        entry["added_tasks"].extend(added_only)

        def render(tasks_with_depth: list[tuple[int, str]]) -> list[str]:
            return ["  " * depth + text for depth, text in sorted(tasks_with_depth)]

        entry["completed"] = render([(new_map[t][0], t) for t in entry["completed"]])
        entry["reopened"] = render([(new_map[t][0], t) for t in entry["reopened"]])
        entry["edited"] = render([(new_map[t.split(" → ")[-1]][0] if " → " in t and t.split(" → ")[-1] in new_map else 0, t) for t in entry["edited"]])
        entry["added_tasks"] = render([(new_map[t][0], t) for t in entry["added_tasks"]])
        entry["removed_tasks"] = render([(old_map[t][0], t) for t in entry["removed_tasks"]])

        if any(entry.values()):
            details[cat] = entry

    for cat in added_categories:
        entry = blank()
        entry["added_tasks"] = ["  " * d + t for d, t, _c in new[cat]]
        if any(entry.values()):
            details[cat] = entry

    for cat in removed_categories:
        entry = blank()
        entry["removed_tasks"] = ["  " * d + t for d, t, _c in old[cat]]
        if any(entry.values()):
            details[cat] = entry

    return added_categories, removed_categories, details


def clip_lines(lines: list[str], emoji: str, heading: str) -> list[str]:
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
    full_sha = os.environ.get("GITHUB_SHA", "")
    server = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
    commit_message = (os.environ.get("COMMIT_MESSAGE") or "").strip().splitlines()
    commit_title = commit_message[0] if commit_message else "Updated TODO.md"
    commit_title = commit_title[:140]
    commit_body = "\n".join(commit_message[1:]).strip()[:300]
    author = (
        os.environ.get("COMMIT_AUTHOR")
        or os.environ.get("COMMIT_AUTHOR_NAME")
        or os.environ.get("GITHUB_ACTOR")
        or "unknown"
    )
    author_url = f"{server}/{author}" if author != "unknown" and "/" not in author else None
    before_sha = os.environ.get("BEFORE_SHA", "")[:7]
    # prefer GITHUB_EVENT compare url if available
    compare = f"{server}/{repo}/compare/{before_sha}...{full_sha[:7]}" if before_sha and full_sha else f"{server}/{repo}/commit/{full_sha}"
    blob_url = f"{server}/{repo}/blob/master/TODO.md"

    # counts
    total_completed = sum(len(d["completed"]) for d in details.values())
    total_added = sum(len(d["added_tasks"]) for d in details.values())
    total_removed = sum(len(d["removed_tasks"]) for d in details.values())
    total_reopened = sum(len(d["reopened"]) for d in details.values())
    total_edited = sum(len(d.get("edited", [])) for d in details.values())

    has_positive = bool(added_categories or total_completed or total_added)
    has_negative = bool(removed_categories or total_removed or total_reopened)
    has_edited = bool(total_edited)
    if has_positive and has_negative:
        color = 0xFEE75C
    elif has_edited and not has_negative:
        color = 0x57F287 if has_positive else 0x5865F2
    elif has_negative:
        color = 0xED4245
    elif has_positive:
        color = 0x57F287
    else:
        color = 0x5865F2

    # title with counts
    summary_parts = []
    if total_completed:
        summary_parts.append(f"{total_completed} ✅")
    if total_added:
        summary_parts.append(f"{total_added} ➕")
    if total_edited:
        summary_parts.append(f"{total_edited} ✏️")
    if total_removed:
        summary_parts.append(f"{total_removed} ➖")
    if total_reopened:
        summary_parts.append(f"{total_reopened} 🔁")
    summary = " · ".join(summary_parts) if summary_parts else "no task changes"
    title = f"📋 TODO — {commit_title}"
    if len(title) > 250:
        title = title[:249] + "…"

    fields: list[dict] = []

    if added_categories or removed_categories:
        cat_lines = []
        cat_lines += [f"➕ `{c}`" for c in added_categories]
        cat_lines += [f"🗑️ `{c}`" for c in removed_categories]
        fields.append({"name": "Categories", "value": "\n".join(cat_lines)[:1024]})

    # sort categories by total changes desc, then alphabetically
    sorted_cats = sorted(
        details.items(),
        key=lambda kv: (-sum(len(v) for v in kv[1].values()), kv[0].lower()),
    )

    overflow = []
    for cat, entry in sorted_cats:
        section_lines: list[str] = []
        for key, heading, emoji in SECTIONS:
            if entry.get(key):
                section_lines.extend(clip_lines(entry[key], emoji, heading))
        if not section_lines:
            continue
        if len(fields) < MAX_FIELDS:
            fields.append({"name": cat[:250], "value": "\n".join(section_lines)[:1024]})
        else:
            counts = ", ".join(
                f"{len(entry[k])} {heading.lower()}" for k, heading, _e in SECTIONS if entry.get(k)
            )
            overflow.append(f"- **{cat}**: {counts}")
    if overflow:
        fields.append({"name": "More categories", "value": "\n".join(overflow)[:1024]})

    if not fields:
        fields.append({"name": "No categories changed", "value": f"Categories added/removed but no task diff. [View TODO]({blob_url})"})

    description_parts = [
        f"**{author}** — [{commit_title}]({compare})",
    ]
    if commit_body:
        description_parts.append(f"> {commit_body[:280]}")
    description_parts.append(f"[View TODO.md]({blob_url}) • [Compare]({compare})")
    description = "\n".join(description_parts)[:3800]

    embed: dict = {
        "title": title,
        "url": blob_url,
        "color": color,
        "description": description,
        "fields": fields,
        "footer": {"text": f"{repo} @ {sha} • {summary}", "icon_url": "https://raw.githubusercontent.com/PineappleTwilight/komikku-pineapple/master/.github/readme-images/app-icon.png"},
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }
    # thumbnail for recognizability
    embed["thumbnail"] = {"url": "https://raw.githubusercontent.com/PineappleTwilight/komikku-pineapple/master/.github/readme-images/app-icon.png"}
    if author_url:
        embed["author"] = {"name": author, "url": author_url}
    return embed


def post_with_retry(webhook: str, payload: dict) -> None:
    data = json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json", "User-Agent": USER_AGENT}
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            request = urllib.request.Request(webhook, data=data, headers=headers, method="POST")
            with urllib.request.urlopen(request, timeout=15) as response:
                print(f"Discord webhook responded {response.status}")
                return
        except urllib.error.HTTPError as error:
            body = error.read().decode(errors="replace")
            if error.code == 429:
                retry_after = float(error.headers.get("Retry-After", "1") or "1")
                wait = retry_after + BASE_BACKOFF ** (attempt - 1)
            elif error.code >= 500:
                wait = BASE_BACKOFF ** attempt
            else:
                print(f"Discord webhook error {error.code}: {body}")
                print(f"Payload was: {json.dumps(payload)[:800]}")
                raise
            if attempt == MAX_ATTEMPTS:
                print(f"Discord still failing after {MAX_ATTEMPTS} attempts: {body}")
                raise
            print(f"Discord {error.code}, retrying in {wait:.1f}s (attempt {attempt}/{MAX_ATTEMPTS})")
            time.sleep(wait)
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            if attempt == MAX_ATTEMPTS:
                print(f"Discord unreachable after {MAX_ATTEMPTS} attempts: {error}")
                raise
            wait = BASE_BACKOFF ** attempt
            print(f"Discord network error: {error}; retrying in {wait}s (attempt {attempt}/{MAX_ATTEMPTS})")
            time.sleep(wait)
    raise RuntimeError("Discord delivery failed after retries")


def send(embed: dict) -> None:
    webhook = os.environ.get("DISCORD_WEBHOOK_URL", "").strip()
    payload = {"username": "Houri TODO", "avatar_url": "https://raw.githubusercontent.com/PineappleTwilight/komikku-pineapple/master/.github/readme-images/app-icon.png", "embeds": [embed]}
    if not webhook:
        print("DISCORD_WEBHOOK_URL not set - dry run. Payload:")
        print(json.dumps(payload, indent=2))
        return
    _sanitize_webhook(webhook)
    post_with_retry(webhook, payload)


def main() -> int:
    args = sys.argv[1:]
    if len(args) == 2:
        old_text = Path(args[0]).read_text(encoding="utf-8")
        new_text = Path(args[1]).read_text(encoding="utf-8")
    else:
        # skip bot pushes that just bump tags/releases without meaningful TODO
        actor = os.environ.get("GITHUB_ACTOR", "")
        if actor == "github-actions[bot]" and not os.environ.get("FORCE_TODO_NOTIFY"):
            # Still process but log; don't skip entirely unless desired
            print(f"Note: triggered by {actor}, proceeding anyway")
        old_text = read_old_todo()
        todo_path = Path("TODO.md")
        new_text = todo_path.read_text(encoding="utf-8") if todo_path.exists() else ""

    old = parse_todo(old_text)
    new = parse_todo(new_text)
    added_categories, removed_categories, details = evaluate(old, new)
    if not added_categories and not removed_categories and not details:
        print("No notable TODO.md changes detected; nothing to send.")
        return 0
    embed = build_embed(added_categories, removed_categories, details)
    print(json.dumps({"embeds": [embed]}, indent=2))
    send(embed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
