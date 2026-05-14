"""Persistent run state — JSON keyed by source path.

Loaded at startup so a re-run skips anything already `verified`. Saved after
every file so an interrupted run is cheap to resume.
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Literal

Status = Literal["pending", "translated", "verified", "failed"]


@dataclass
class UnitState:
    source_path: str
    status: Status = "pending"
    retry_count: int = 0
    output_path: str | None = None
    last_error: str | None = None
    prompt_version: str | None = None
    updated_at: float = 0.0
    # Free-form annotations (e.g. "in-cycle", "module-error").
    notes: list[str] = field(default_factory=list)


@dataclass
class RunState:
    units: dict[str, UnitState] = field(default_factory=dict)
    path: Path | None = None

    @classmethod
    def load(cls, path: Path) -> "RunState":
        if not path.exists():
            return cls(path=path)
        raw = json.loads(path.read_text())
        units = {k: UnitState(**v) for k, v in raw.get("units", {}).items()}
        return cls(units=units, path=path)

    def save(self) -> None:
        if self.path is None:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"units": {k: asdict(v) for k, v in self.units.items()}}
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(payload, indent=2))
        tmp.replace(self.path)

    def get_or_create(self, source_path: str) -> UnitState:
        st = self.units.get(source_path)
        if st is None:
            st = UnitState(source_path=source_path)
            self.units[source_path] = st
        return st

    def mark(
        self,
        source_path: str,
        *,
        status: Status | None = None,
        output_path: str | None = None,
        last_error: str | None = None,
        prompt_version: str | None = None,
        increment_retry: bool = False,
        add_note: str | None = None,
    ) -> UnitState:
        st = self.get_or_create(source_path)
        if status is not None:
            st.status = status
        if output_path is not None:
            st.output_path = output_path
        if last_error is not None:
            st.last_error = last_error
        if prompt_version is not None:
            st.prompt_version = prompt_version
        if increment_retry:
            st.retry_count += 1
        if add_note:
            st.notes.append(add_note)
        st.updated_at = time.time()
        return st
