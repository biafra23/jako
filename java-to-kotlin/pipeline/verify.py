"""Phase 3 — verification (kotlinc) and bounded semantic retry."""

from __future__ import annotations

import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

from .discover import JavaUnit
from .llm_client import LLMClient
from .prompts import (
    PROMPT_VERSION,
    RetryPromptInputs,
    build_retry_messages,
)
from .state import RunState
from .translate import (
    TranslateConfig,
    assemble_dep_signatures,
    extract_kotlin_block,
)


@dataclass
class VerifyConfig:
    mode: str = "both"               # per_file | per_module | both
    max_retries: int = 3
    extra_classpath: list[str] | None = None


@dataclass
class CompileResult:
    ok: bool
    stdout: str
    stderr: str
    returncode: int


def _kotlinc_path() -> str:
    path = shutil.which("kotlinc")
    if path is None:
        raise RuntimeError("kotlinc not on PATH — install Kotlin to verify")
    return path


def compile_per_file(
    kotlin_path: Path,
    *,
    classpath_paths: list[Path],
    extra_classpath: list[str] | None = None,
) -> CompileResult:
    """Compile a single .kt file. Discard the .class output — we only care about errors."""
    out_dir = kotlin_path.parent / ".verify-out"
    out_dir.mkdir(exist_ok=True)
    cp_parts: list[str] = []
    for p in classpath_paths:
        cp_parts.append(str(p))
    for p in extra_classpath or []:
        cp_parts.append(p)
    args = [_kotlinc_path(), "-nowarn", "-d", str(out_dir), str(kotlin_path)]
    if cp_parts:
        args.extend(["-cp", ":".join(cp_parts)])
    try:
        proc = subprocess.run(args, capture_output=True, text=True, timeout=180)
    except subprocess.TimeoutExpired as e:
        return CompileResult(ok=False, stdout="", stderr=f"kotlinc timeout: {e!r}", returncode=-1)
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)
    ok = proc.returncode == 0 and "error:" not in (proc.stderr + proc.stdout)
    return CompileResult(ok=ok, stdout=proc.stdout, stderr=proc.stderr, returncode=proc.returncode)


def compile_module(output_root: Path, *, extra_classpath: list[str] | None = None) -> CompileResult:
    """Compile every .kt file under output_root together. Catches cross-file inconsistencies."""
    files = sorted(output_root.rglob("*.kt"))
    if not files:
        return CompileResult(ok=True, stdout="", stderr="", returncode=0)
    out_dir = output_root / ".module-verify-out"
    out_dir.mkdir(exist_ok=True)
    args = [_kotlinc_path(), "-nowarn", "-d", str(out_dir)]
    if extra_classpath:
        args.extend(["-cp", ":".join(extra_classpath)])
    args.extend(str(p) for p in files)
    try:
        proc = subprocess.run(args, capture_output=True, text=True, timeout=900)
    except subprocess.TimeoutExpired as e:
        return CompileResult(ok=False, stdout="", stderr=f"kotlinc timeout: {e!r}", returncode=-1)
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)
    ok = proc.returncode == 0 and "error:" not in (proc.stderr + proc.stdout)
    return CompileResult(ok=ok, stdout=proc.stdout, stderr=proc.stderr, returncode=proc.returncode)


def _dep_kotlin_paths(unit: JavaUnit, units_by_path: dict[str, JavaUnit]) -> list[Path]:
    paths: list[Path] = []
    for dep_path in unit.dependencies:
        dep = units_by_path.get(dep_path)
        if dep is None:
            continue
        kt = Path(dep.target_path)
        if kt.exists():
            paths.append(kt)
    return paths


def verify_and_retry(
    unit: JavaUnit,
    units_by_path: dict[str, JavaUnit],
    llm: LLMClient,
    cfg: VerifyConfig,
    translate_cfg: TranslateConfig,
    state: RunState,
) -> CompileResult:
    """Compile the unit; on failure, loop the LLM up to cfg.max_retries times."""
    kt_path = Path(unit.target_path)
    java_path = Path(unit.source_path)

    # Compile per-file against the kotlin sources of its deps.
    dep_paths = _dep_kotlin_paths(unit, units_by_path)
    # kotlinc treats `-cp` entries as compiled class output or jars; bundling the
    # deps as additional .kt source inputs is the simplest correct option here.
    result = _compile_with_sources(kt_path, dep_paths, cfg.extra_classpath)
    if result.ok:
        state.mark(unit.source_path, status="verified", last_error=None)
        state.save()
        return result

    for attempt in range(1, cfg.max_retries + 1):
        java_source = java_path.read_text(encoding="utf-8", errors="replace")
        prev_kotlin = kt_path.read_text(encoding="utf-8", errors="replace")
        compiler_err = (result.stderr or result.stdout or "").strip()

        dep_sigs = assemble_dep_signatures(
            unit,
            units_by_path,
            max_count=translate_cfg.max_dependency_signatures,
            max_chars_each=translate_cfg.max_signature_chars,
        )

        messages = build_retry_messages(
            RetryPromptInputs(
                java_source=java_source,
                java_filename=java_path.name,
                package=unit.package,
                category=unit.category,
                previous_kotlin=prev_kotlin,
                compiler_error=compiler_err,
                dependency_signatures=dep_sigs,
            )
        )

        try:
            output = llm.chat(messages, log_tag=f"retry_{java_path.stem}_a{attempt}")
            kotlin = extract_kotlin_block(output)
        except Exception as e:
            state.mark(
                unit.source_path,
                last_error=f"retry {attempt} failed: {e!r}",
                increment_retry=True,
            )
            state.save()
            continue

        kt_path.write_text(kotlin, encoding="utf-8")
        state.mark(
            unit.source_path,
            increment_retry=True,
            prompt_version=PROMPT_VERSION,
        )
        state.save()

        result = _compile_with_sources(kt_path, dep_paths, cfg.extra_classpath)
        if result.ok:
            state.mark(unit.source_path, status="verified", last_error=None)
            state.save()
            return result

    # Exhausted retries.
    state.mark(
        unit.source_path,
        status="failed",
        last_error=(result.stderr or result.stdout or "kotlinc rejected after retries").strip()[:4000],
    )
    state.save()
    return result


def _compile_with_sources(
    target: Path,
    extra_sources: list[Path],
    extra_classpath: list[str] | None,
) -> CompileResult:
    """Compile `target` together with `extra_sources` (its dependency .kt files).

    Bundling the deps as source inputs sidesteps having to first compile deps to
    .class files and pass them on -cp.
    """
    out_dir = target.parent / ".verify-out"
    out_dir.mkdir(exist_ok=True)
    args = [_kotlinc_path(), "-nowarn", "-d", str(out_dir)]
    if extra_classpath:
        args.extend(["-cp", ":".join(extra_classpath)])
    args.append(str(target))
    for s in extra_sources:
        args.append(str(s))
    try:
        proc = subprocess.run(args, capture_output=True, text=True, timeout=180)
    except subprocess.TimeoutExpired as e:
        return CompileResult(ok=False, stdout="", stderr=f"kotlinc timeout: {e!r}", returncode=-1)
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)
    ok = proc.returncode == 0 and "error:" not in (proc.stderr + proc.stdout)
    # Filter kotlinc errors so the model only sees errors involving the target file.
    if not ok:
        filtered = _filter_errors_for(proc.stderr, target.name) or proc.stderr
        return CompileResult(ok=False, stdout=proc.stdout, stderr=filtered, returncode=proc.returncode)
    return CompileResult(ok=True, stdout=proc.stdout, stderr=proc.stderr, returncode=proc.returncode)


def _filter_errors_for(stderr: str, target_filename: str) -> str:
    """Keep only lines referencing the target file, plus generic 'error:' messages."""
    keep: list[str] = []
    for line in stderr.splitlines():
        if target_filename in line:
            keep.append(line)
        elif line.strip().startswith("error:") and "/" not in line:
            keep.append(line)
    return "\n".join(keep)
