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


# Strict opener: must say `kotlin` (or `kt`) so we don't accidentally pair with
# unrelated fenced blocks (json, java, plain text) that the model might emit.
_RE_FENCE_STRICT = re.compile(r"```(?:kotlin|kt)\s*\n(.*?)\n?```", re.DOTALL)
_RE_FENCE_LOOSE = re.compile(r"```(?:kotlin|kt)?\s*\n(.*?)\n?```", re.DOTALL)

# Heuristics for recognising the real translated file vs. a deliberation snippet.
_RE_PACKAGE_DECL = re.compile(r"^\s*package\s+[\w.]+", re.MULTILINE)


class TranslationError(RuntimeError):
    pass


@dataclass
class TranslateConfig:
    max_dependency_signatures: int = 12
    max_signature_chars: int = 4000


def extract_kotlin_block(model_output: str) -> str:
    """Pull the translated Kotlin file out of the model output.

    Picks the largest fence whose body looks like a real Kotlin source file
    (contains a ``package`` declaration). Falls back to the longest fence
    overall if none match — and finally to the loose-regex (no language tag)
    variant if the strict matcher finds nothing.
    """
    for matcher in (_RE_FENCE_STRICT, _RE_FENCE_LOOSE):
        matches = matcher.findall(model_output)
        if not matches:
            continue
        with_pkg = [m for m in matches if _RE_PACKAGE_DECL.search(m)]
        if with_pkg:
            return max(with_pkg, key=len)
        # No fence had a package — fall through to next matcher; if also none,
        # the loose pass will pick the longest as last-resort.
    matches = _RE_FENCE_LOOSE.findall(model_output)
    if not matches:
        raise TranslationError("no kotlin code fence found in model output")
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
