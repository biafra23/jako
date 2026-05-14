"""Phase 4 — structured run report (JSON + human-readable Markdown)."""

from __future__ import annotations

import json
from collections import Counter
from dataclasses import asdict
from pathlib import Path

from .discover import DiscoveryResult
from .state import RunState


def _bucket(state: RunState) -> Counter:
    return Counter(u.status for u in state.units.values())


def write_reports(
    discovery: DiscoveryResult,
    state: RunState,
    *,
    json_path: Path,
    md_path: Path,
    wall_clock_seconds: float | None = None,
) -> None:
    counts = _bucket(state)
    total = sum(counts.values())
    failed = [u for u in state.units.values() if u.status == "failed"]
    retried = [u for u in state.units.values() if u.retry_count > 0 and u.status != "failed"]

    payload = {
        "totals": dict(counts),
        "total_units": total,
        "wall_clock_seconds": wall_clock_seconds,
        "cycles": discovery.cycles,
        "failed": [asdict(u) for u in failed],
        "retried_but_recovered": [
            {"source_path": u.source_path, "retry_count": u.retry_count} for u in retried
        ],
        "all_units": [asdict(u) for u in state.units.values()],
    }
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(payload, indent=2))

    avg = ""
    if wall_clock_seconds and total:
        avg = f" (avg {wall_clock_seconds / total:.1f}s/unit)"

    lines: list[str] = []
    lines.append("# Java → Kotlin conversion report")
    lines.append("")
    lines.append(f"- Total units: **{total}**{avg}")
    for status, n in sorted(counts.items()):
        lines.append(f"- `{status}`: {n}")
    if wall_clock_seconds is not None:
        lines.append(f"- Wall clock: {wall_clock_seconds:.1f}s")
    lines.append("")
    if discovery.cycles:
        lines.append(f"## Dependency cycles ({len(discovery.cycles)})")
        lines.append("")
        for c in discovery.cycles:
            lines.append(f"- {len(c)} files: " + ", ".join(Path(p).name for p in c))
        lines.append("")
    if retried:
        lines.append("## Recovered after retry")
        lines.append("")
        for u in retried:
            lines.append(f"- `{Path(u.source_path).name}` (retries: {u.retry_count})")
        lines.append("")
    if failed:
        lines.append(f"## Failed ({len(failed)})")
        lines.append("")
        for u in failed:
            lines.append(f"### `{Path(u.source_path).name}`")
            lines.append("")
            lines.append(f"- retries: {u.retry_count}")
            lines.append(f"- output: `{u.output_path or '(none)'}`")
            if u.last_error:
                lines.append("```")
                lines.append(u.last_error[:2000])
                lines.append("```")
            lines.append("")
    md_path.parent.mkdir(parents=True, exist_ok=True)
    md_path.write_text("\n".join(lines))
