"""Phase 2 — per-file translation.

For each unit:
  1. Assemble dependency-signature context from already-converted Kotlin output.
  2. Build the translate prompt.
  3. Call the LLM.
  4. Extract the single fenced ```kotlin block.
  5. Write to mirrored output path.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

from .discover import JavaUnit
from .llm_client import LLMClient
from .prompts import (
    PROMPT_VERSION,
    TranslatePromptInputs,
    build_translate_messages,
    extract_kotlin_signatures,
)
from .state import RunState


_RE_FENCE = re.compile(r"```(?:kotlin|kt)?\s*\n(.*?)\n?```", re.DOTALL)


class TranslationError(RuntimeError):
    pass


@dataclass
class TranslateConfig:
    max_dependency_signatures: int = 12
    max_signature_chars: int = 4000


def extract_kotlin_block(model_output: str) -> str:
    """Pull the single ```kotlin block out of the model output, or raise."""
    matches = _RE_FENCE.findall(model_output)
    if not matches:
        raise TranslationError("no kotlin code fence found in model output")
    # If multiple fences are returned, prefer the longest — heuristic for
    # "real file" vs. an inline snippet inside commentary.
    return max(matches, key=len)


def assemble_dep_signatures(
    unit: JavaUnit,
    units_by_path: dict[str, JavaUnit],
    *,
    max_count: int,
    max_chars_each: int,
) -> str:
    blocks: list[str] = []
    for dep_path in unit.dependencies[:max_count]:
        dep = units_by_path.get(dep_path)
        if dep is None:
            continue
        kt_path = Path(dep.target_path)
        if not kt_path.exists():
            # Skip — the dep wasn't (yet) converted (cycle, failed unit, etc.).
            continue
        kt_source = kt_path.read_text(encoding="utf-8", errors="replace")
        sigs = extract_kotlin_signatures(kt_source, max_chars_each)
        if not sigs.strip():
            continue
        blocks.append(f"// --- {kt_path.name} ---\n{sigs}")
    return "\n\n".join(blocks)


def translate_unit(
    unit: JavaUnit,
    units_by_path: dict[str, JavaUnit],
    llm: LLMClient,
    cfg: TranslateConfig,
    state: RunState | None = None,
    *,
    log_tag: str | None = None,
) -> str:
    """Translate one Java unit. Writes the Kotlin file and returns its path."""
    java_path = Path(unit.source_path)
    java_source = java_path.read_text(encoding="utf-8", errors="replace")

    dep_sigs = assemble_dep_signatures(
        unit,
        units_by_path,
        max_count=cfg.max_dependency_signatures,
        max_chars_each=cfg.max_signature_chars,
    )

    inputs = TranslatePromptInputs(
        java_source=java_source,
        java_filename=java_path.name,
        package=unit.package,
        category=unit.category,
        dependency_signatures=dep_sigs,
    )
    messages = build_translate_messages(inputs)

    output = llm.chat(messages, log_tag=log_tag or f"translate_{java_path.stem}")
    kotlin = extract_kotlin_block(output)

    target = Path(unit.target_path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(kotlin, encoding="utf-8")

    if state is not None:
        state.mark(
            unit.source_path,
            status="translated",
            output_path=str(target),
            prompt_version=PROMPT_VERSION,
            last_error=None,
        )
        state.save()

    return str(target)
