"""Minimal YAML loader.

We only need the subset of YAML used in config.yaml: scalar mappings, lists with
`- ` items, two-space indentation, `#` comments, and `~` expanded by callers.
This avoids a PyYAML dependency for an otherwise zero-dep pipeline.

If a richer config shape is ever needed, install PyYAML and replace this module.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any


def _parse_scalar(s: str) -> Any:
    s = s.strip()
    if not s:
        return ""
    if s == "[]":
        return []
    if s == "{}":
        return {}
    if (s.startswith('"') and s.endswith('"')) or (s.startswith("'") and s.endswith("'")):
        return s[1:-1]
    low = s.lower()
    if low in ("true", "yes", "on"):
        return True
    if low in ("false", "no", "off"):
        return False
    if low in ("null", "~", ""):
        return None
    try:
        if "." in s:
            return float(s)
        return int(s)
    except ValueError:
        return s


def _indent(line: str) -> int:
    n = 0
    for ch in line:
        if ch == " ":
            n += 1
        else:
            break
    return n


def load_yaml(path: Path) -> dict[str, Any]:
    """Parse a small mapping-only YAML file.

    Supports:
      key: value
      key:
        nested_key: value
        list_key:
          - item1
          - item2
      []  (empty list)
    """
    lines = path.read_text().splitlines()

    # Pre-process: drop comments and blank lines, normalise.
    cleaned: list[tuple[int, str]] = []
    for raw in lines:
        # Strip end-of-line comments — but not inside quotes; we keep this simple.
        stripped_comment = raw
        if "#" in raw:
            # Only strip if # isn't inside quotes (rough check).
            in_quote = False
            quote_ch = ""
            for i, ch in enumerate(raw):
                if ch in ('"', "'"):
                    if not in_quote:
                        in_quote = True
                        quote_ch = ch
                    elif quote_ch == ch:
                        in_quote = False
                elif ch == "#" and not in_quote:
                    stripped_comment = raw[:i]
                    break
        if not stripped_comment.strip():
            continue
        cleaned.append((_indent(stripped_comment), stripped_comment.rstrip()))

    def parse_block(start: int, base_indent: int) -> tuple[Any, int]:
        # Decide if this block is a list (first line is `-`) or a mapping.
        if start >= len(cleaned):
            return {}, start
        ind, content = cleaned[start]
        stripped = content.strip()

        if stripped == "[]":
            return [], start + 1

        if stripped.startswith("- "):
            items: list[Any] = []
            i = start
            while i < len(cleaned):
                ind_i, content_i = cleaned[i]
                if ind_i < base_indent:
                    break
                if ind_i != base_indent:
                    # Indented continuation belongs to the previous item — but our
                    # config doesn't use complex list items, so skip.
                    i += 1
                    continue
                s = content_i.strip()
                if not s.startswith("- "):
                    break
                items.append(_parse_scalar(s[2:]))
                i += 1
            return items, i

        # Mapping.
        mapping: dict[str, Any] = {}
        i = start
        while i < len(cleaned):
            ind_i, content_i = cleaned[i]
            if ind_i < base_indent:
                break
            if ind_i != base_indent:
                i += 1
                continue
            s = content_i.strip()
            if ":" not in s:
                i += 1
                continue
            key, _, val = s.partition(":")
            key = key.strip()
            val = val.strip()
            if val == "":
                # Look ahead — child block at deeper indent.
                if i + 1 < len(cleaned) and cleaned[i + 1][0] > base_indent:
                    child, j = parse_block(i + 1, cleaned[i + 1][0])
                    mapping[key] = child
                    i = j
                else:
                    mapping[key] = None
                    i += 1
            else:
                mapping[key] = _parse_scalar(val)
                i += 1
        return mapping, i

    result, _ = parse_block(0, 0)
    return result if isinstance(result, dict) else {}
