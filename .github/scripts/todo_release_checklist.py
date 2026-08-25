#!/usr/bin/env python3
"""Compute TODO.md progress between the previous release tag and HEAD.

Produces two Discord-ready markdown blocks:
  - COMPLETED: tasks checked off since the previous release tag
  - OPEN: tasks still unchecked at HEAD (tasks added after the tag are marked)

Usage (CI):
  PREV_TAG_NAME=v1.2.3 python3 .github/scripts/todo_release_checklist.py --gh-output

Usage (local):
  python3 .github/scripts/todo_release_checklist.py old_TODO.md new_TODO.md

With --gh-output the blocks are appended to $GITHUB_OUTPUT as multiline values
named COMPLETED and OPEN. Without it they are printed to stdout.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

CHECKBOX_RE = re.compile(r"^(\s*)-\s\[( |x|X)\]\s+(.*)$")
CATEGORY_RE = re.compile(r"^##\s+(.+?)\s*$")

FIELD_VALUE_BUDGET = 1000  # Discord caps embed field values at 1024 chars
ITEM_CLIP = 180


def git_show(ref: str) -> str:
    """Read TODO.md at a ref; empty string when the ref or file is missing."""
    result = subprocess.run(
        ["git", "show", f"{ref}:TODO.md"],
        capture_output=True,
        text=True,
    )
    return result.stdout if result.returncode == 0 else ""


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


def diff_since_tag(
    old_text: str, new_text: str
) -> tuple[list[tuple[str, int, str]], list[tuple[str, int, str, bool]], bool]:
    """Return (completed, open_items, had_old_file).

    completed: (category, depth, task) unchecked at the tag, checked at HEAD.
    open_items: (category, depth, task, is_new_since_tag) still unchecked.
    Tasks are matched per category by exact text; a task added and finished
    within the same cycle never counts as completed (it was never pending).
    """
    old_map_full = parse_todo(old_text)
    new_map_full = parse_todo(new_text)
    had_old_file = bool(old_text.strip())

    completed: list[tuple[str, int, str]] = []
    open_items: list[tuple[str, int, str, bool]] = []

    for category, tasks in new_map_full.items():
        old_checked = {task: checked for _d, task, checked in old_map_full.get(category, [])}
        for depth, task, checked in tasks:
            if checked:
                if task in old_checked and not old_checked[task]:
                    completed.append((category, depth, task))
            else:
                open_items.append((category, depth, task, task not in old_checked))
    return completed, open_items, had_old_file


def clip_lines(lines: list[str], budget: int, item_flags: list[bool]) -> str:
    used = 0
    out: list[str] = []
    for i, line in enumerate(lines):
        text = line[: ITEM_CLIP - 1] + "…" if len(line) > ITEM_CLIP else line
        cost = len(text) + 1
        remaining_items = sum(item_flags[i + 1:])
        if used + cost > budget and remaining_items > 0:
            out.append(f"- *…and {remaining_items + 1} more*")
            break
        out.append(text)
        used += cost
    return "\n".join(out)


def build_markdown(
    completed: list[tuple[str, int, str]],
    open_items: list[tuple[str, int, str, bool]],
    mark_new: bool,
) -> tuple[str, str]:
    def render(entries: list[tuple[str, str, bool]]) -> str:
        if not entries:
            return ""
        lines: list[str] = []
        flags: list[bool] = []
        current_category: str | None = None
        for category, rendered_task, is_new in entries:
            if category != current_category:
                current_category = category
                lines.append(f"**{category}**")
                flags.append(False)
            suffix = " *(new)*" if mark_new and is_new else ""
            lines.append(f"- {rendered_task}{suffix}")
            flags.append(True)
        return clip_lines(lines, FIELD_VALUE_BUDGET, flags)

    def clip_item(text: str) -> str:
        return text[: ITEM_CLIP - 1] + "…" if len(text) > ITEM_CLIP else text

    completed_entries = [(c, clip_item(t), False) for c, _d, t in completed]
    open_entries = [
        (c, clip_item(t), is_new) for c, _d, t, is_new in open_items
    ]
    return render(completed_entries), render(open_entries)


def write_gh_outputs(completed_md: str, open_md: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as fh:
        fh.write("COMPLETED<<TODO_COMPLETED_DELIM\n")
        fh.write(f"{completed_md}\n")
        fh.write("TODO_COMPLETED_DELIM\n")
        fh.write("OPEN<<TODO_OPEN_DELIM\n")
        fh.write(f"{open_md}\n")
        fh.write("TODO_OPEN_DELIM\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("old", nargs="?", help="old TODO.md file (local mode)")
    parser.add_argument("new", nargs="?", help="new TODO.md file (local mode)")
    parser.add_argument(
        "--gh-output",
        action="store_true",
        help="append COMPLETED/OPEN blocks to $GITHUB_OUTPUT",
    )
    args = parser.parse_args()

    if args.old is not None and args.new is not None:
        old_text = Path(args.old).read_text(encoding="utf-8")
        new_text = Path(args.new).read_text(encoding="utf-8")
    else:
        prev_tag = os.environ.get("PREV_TAG_NAME", "").strip()
        if not prev_tag:
            print("PREV_TAG_NAME is not set and no file pair given", file=sys.stderr)
            return 1
        old_text = git_show(prev_tag)
        todo_path = Path("TODO.md")
        new_text = todo_path.read_text(encoding="utf-8") if todo_path.exists() else ""

    completed, open_items, had_old_file = diff_since_tag(old_text, new_text)
    completed_md, open_md = build_markdown(completed, open_items, mark_new=had_old_file)

    print(
        f"TODO progress since {'files' if args.old is not None else 'last release'}: "
        f"{len(completed)} completed, {len(open_items)} open"
    )
    print("--- COMPLETED ---")
    print(completed_md or "(none)")
    print("--- OPEN ---")
    print(open_md or "(none)")

    if args.gh_output:
        write_gh_outputs(completed_md, open_md)
    return 0


if __name__ == "__main__":
    sys.exit(main())
