#!/usr/bin/env python3
"""Send a rich Discord release announcement.

Env vars (set by GitHub Actions):
  DISCORD_WEBHOOK_URL  - required https:// discord webhook
  VERSION_TAG          - e.g. v1.2.3
  RELEASE_URL          - https://github.com/org/repo/releases/tag/v1.2.3
  AVATAR_URL           - thumbnail / webhook avatar
  CHANGELOG            - multiline "- subject (@author)" bullets
  PREV_TAG_NAME        - previous release tag for compare link
  TODO_COMPLETED       - markdown block from todo_release_checklist.py (may be empty)
  TODO_OPEN            - markdown block (may be empty)
  GITHUB_REPOSITORY    - org/repo
  GITHUB_SERVER_URL    - https://github.com

 behaviour:
  - validates webhook is https discord host
  - truncates changelog to fit Discord 6000 char / 4096 embed desc limits
  - builds a single rich embed with thumbnail, timestamp, download links, changelog, TODO fields
  - retries on 429/5xx with backoff honouring Retry-After
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

USER_AGENT = "Houri-Release-Notifier/1.1 (GitHub Actions; +https://github.com/PineappleTwilight/komikku-pineapple)"
MAX_ATTEMPTS = 4
BASE_BACKOFF = 2

ALLOWED_WEBHOOK_HOSTS = ("discord.com", "discordapp.com", "canary.discord.com", "ptb.discord.com")


def _env(name: str, default: str = "") -> str:
    return (os.environ.get(name) or default).strip()


def _validate_webhook(url: str) -> None:
    if not url.startswith("https://"):
        raise ValueError("DISCORD_WEBHOOK_URL must be https://")
    try:
        from urllib.parse import urlparse

        host = urlparse(url).hostname or ""
        if not any(host == h or host.endswith("." + h) for h in ALLOWED_WEBHOOK_HOSTS):
            raise ValueError(f"DISCORD_WEBHOOK_URL host must be discord.com, got {host}")
    except ValueError:
        raise
    except Exception as e:
        raise ValueError(f"Invalid DISCORD_WEBHOOK_URL: {e}") from e


def _truncate_changelog(raw: str, limit: int = 1650) -> tuple[str, int]:
    if not raw or not raw.strip():
        return "- No notable changes", 0
    lines = [l.rstrip() for l in raw.strip().splitlines() if l.strip()]
    if not lines:
        return "- No notable changes", 0
    # keep at least one bullet
    out_lines: list[str] = []
    hidden = 0
    total = 0
    for i, line in enumerate(lines):
        # ensure bullet prefix
        if not line.startswith("- "):
            line = "- " + line
        # clip single line to 220 chars
        if len(line) > 220:
            line = line[:219] + "…"
        cost = len(line) + 1
        if total + cost > limit and out_lines:
            hidden = len(lines) - i
            break
        out_lines.append(line)
        total += cost
    if hidden:
        out_lines.append(f"- *…and {hidden} more — see [full changelog]({ _env('RELEASE_URL', 'https://github.com/' + _env('GITHUB_REPOSITORY')) })*")
    return "\n".join(out_lines), hidden


def build_payload() -> dict:
    repo = _env("GITHUB_REPOSITORY", "unknown/repo")
    server = _env("GITHUB_SERVER_URL", "https://github.com")
    version_tag = _env("VERSION_TAG", "v0.0.0")
    release_url = _env("RELEASE_URL") or f"{server}/{repo}/releases/tag/{version_tag}"
    avatar_url = _env("AVATAR_URL") or f"https://raw.githubusercontent.com/{repo}/master/.github/readme-images/app-icon.png"
    changelog_raw = _env("CHANGELOG")
    prev_tag = _env("PREV_TAG_NAME")
    todo_completed = _env("TODO_COMPLETED")
    todo_open = _env("TODO_OPEN")

    version_number = version_tag[1:] if version_tag.startswith("v") else version_tag
    # changelog
    changes, _hidden = _truncate_changelog(changelog_raw)

    # embed color: green if all closed, yellow if mixed, orange if nothing closed but still open
    if todo_completed and not todo_open:
        color = 0x57F287
    elif todo_completed and todo_open:
        color = 0xFEE75C
    elif todo_open:
        color = 0xED4245
    else:
        color = 0xF97316  # houri orange

    repo_url = f"{server}/{repo}"
    compare_url = f"{repo_url}/compare/{prev_tag}...{version_tag}" if prev_tag else release_url

    # description with changelog + links
    # Discord embed description limit 4096
    desc_parts = [
        f"**What's changed**",
        changes,
        "",
        f"[📦 Release]({release_url}) · [📊 Compare]({compare_url}) · [📋 TODO]({server}/{repo}/blob/master/TODO.md)",
    ]
    description = "\n".join(desc_parts)
    if len(description) > 3900:
        description = description[:3900] + "\n…"

    # download links as field
    short_tag = version_tag
    download_value = (
        f"[Universal]({release_url}) • "
        f"[arm64-v8a]({release_url}) • "
        f"[armeabi]({release_url}) • "
        f"[nomtl]({release_url})"
    )

    fields: list[dict] = []
    # Downloads field first
    fields.append({"name": "📥 Downloads", "value": download_value, "inline": False})

    # TODO fields — ensure each <=1024
    if todo_completed:
        val = todo_completed[:1020] + "…" if len(todo_completed) > 1024 else todo_completed
        fields.append({"name": "✅ Checked off", "value": val, "inline": True})
    if todo_open:
        val = todo_open[:1020] + "…" if len(todo_open) > 1024 else todo_open
        fields.append({"name": "📋 Still open", "value": val, "inline": True})

    # keep within 25 fields — we have at most 3
    embed = {
        "title": f"🍍 Houri {version_number} is out!",
        "url": release_url,
        "description": description,
        "color": color,
        "thumbnail": {"url": avatar_url},
        "fields": fields,
        "footer": {
            "text": f"{repo} • {version_tag}" + (f" • {prev_tag} → {version_tag}" if prev_tag else ""),
            "icon_url": avatar_url,
        },
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }
    # prune empty fields value?
    content = f"||<@&1540511662824759336>||"  # role ping — suppressed via allowed_mentions below, but visible if user allows
    payload = {
        "username": "Houri Releases",
        "avatar_url": avatar_url,
        "content": content,
        "allowed_mentions": {"parse": [], "roles": ["1540511662824759336"]},
        "embeds": [embed],
    }
    return payload


def post_with_retry(webhook: str, payload: dict) -> None:
    data = json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json", "User-Agent": USER_AGENT}
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            req = urllib.request.Request(webhook, data=data, headers=headers, method="POST")
            with urllib.request.urlopen(req, timeout=15) as resp:
                print(f"Discord webhook responded {resp.status}")
                return
        except urllib.error.HTTPError as e:
            body = e.read().decode(errors="replace")
            if e.code == 429:
                retry_after = float(e.headers.get("Retry-After", "1") or "1")
                wait = retry_after + BASE_BACKOFF ** (attempt - 1)
            elif e.code >= 500:
                wait = BASE_BACKOFF ** attempt
            else:
                print(f"Discord webhook error {e.code}: {body}")
                raise
            if attempt == MAX_ATTEMPTS:
                print(f"Discord still failing after {MAX_ATTEMPTS} attempts: {body}")
                raise
            print(f"Discord {e.code}, retrying in {wait:.1f}s (attempt {attempt}/{MAX_ATTEMPTS})")
            time.sleep(wait)
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            if attempt == MAX_ATTEMPTS:
                print(f"Discord unreachable after {MAX_ATTEMPTS} attempts: {e}")
                raise
            wait = BASE_BACKOFF ** attempt
            print(f"Discord network error: {e}; retrying in {wait}s (attempt {attempt}/{MAX_ATTEMPTS})")
            time.sleep(wait)
    raise RuntimeError("Discord delivery failed after retries")


def main() -> int:
    webhook = _env("DISCORD_WEBHOOK_URL")
    if not webhook:
        print("RELEASE_DISCORD_WEBHOOK not set; skipping Discord announcement (dry run).")
        payload = build_payload()
        print(json.dumps(payload, indent=2))
        return 0
    _validate_webhook(webhook)
    payload = build_payload()
    print(json.dumps(payload, indent=2))
    post_with_retry(webhook, payload)
    print("Discord announcement sent.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
