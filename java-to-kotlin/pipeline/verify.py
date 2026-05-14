"""Phase 3 — verification (kotlinc) and bounded semantic retry."""

from __future__ import annotations

import re
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
    stderr: str          # errors filtered to the target file (or generic ones)
    returncode: int
    raw_stderr: str = ""  # the full unfiltered kotlinc stderr, used by the
                          # cascading-dep guard in verify_and_retry


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


_RE_UNRESOLVED = re.compile(r"error:\s*unresolved reference '([A-Za-z_][A-Za-z0-9_]*)'", re.IGNORECASE)
_RE_OVERRIDES_NOTHING = re.compile(r"error:\s*'([A-Za-z_][A-Za-z0-9_]*)' overrides nothing", re.IGNORECASE)


def is_blocked_on_missing_deps(
    compiler_error: str,
    unit: JavaUnit,
    units_by_path: dict[str, JavaUnit],
    state: "RunState | None" = None,
) -> tuple[bool, set[str]]:
    """Return (True, missing_type_names) iff every kotlinc error is plausibly caused
    by a project-internal dependency that hasn't been translated yet.

    Heuristic — we consider a file "blocked on deps" when:
      * The error set is non-empty.
      * Every error is either:
          - `unresolved reference 'X'` where X is a top-level type declared by
            another in-project unit whose Kotlin output is missing or not yet
            verified, OR
          - `'X' overrides nothing` where X is a method on a type the file is
            implementing/extending whose Kotlin output is missing (downstream
            effect of the unresolved supertype).

    Errors that don't fit either pattern (real translation bugs) cause us to
    return (False, ...) so the normal retry loop runs.
    """
    if not compiler_error.strip():
        return False, set()

    # Names declared somewhere in the project.
    project_types: set[str] = set()
    declared_in: dict[str, JavaUnit] = {}
    for u in units_by_path.values():
        for n in u.type_names:
            project_types.add(n)
            declared_in[n] = u

    # Names where the unit IS in-project but its Kotlin output is missing or
    # not yet verified.
    def is_missing(name: str) -> bool:
        owner = declared_in.get(name)
        if owner is None:
            return False
        kt = Path(owner.target_path)
        if not kt.exists():
            return True
        if state is not None:
            owner_state = state.units.get(owner.source_path)
            if owner_state is None or owner_state.status != "verified":
                return True
        return False

    missing: set[str] = set()
    has_other_error = False
    for line in compiler_error.splitlines():
        line = line.strip()
        if not line:
            continue
        if "error:" not in line:
            continue
        m = _RE_UNRESOLVED.search(line)
        if m:
            name = m.group(1)
            if is_missing(name):
                missing.add(name)
                continue
            # Unresolved reference to something NOT in our project — that's
            # a translation bug (e.g. wrong import).
            has_other_error = True
            continue
        m = _RE_OVERRIDES_NOTHING.search(line)
        if m:
            # 'overrides nothing' is almost always a downstream consequence of
            # the supertype being unresolved. Only treat it as topology if we
            # already have at least one matching unresolved-reference error.
            if missing:
                continue
            has_other_error = True
            continue
        has_other_error = True

    if has_other_error:
        return False, missing
    return bool(missing), missing


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

    # Topology guard #1: target file itself is clean — only bundled dep .kt
    # files are broken. Retrying our translation can't fix dep files; treat as
    # blocked on deps and bail.
    target_filename = kt_path.name
    filtered_err = (result.stderr or "").strip()
    raw_err = (result.raw_stderr or "").strip()
    if not filtered_err and raw_err:
        deps_in_error = sorted({
            line.split(":", 1)[0].split("/")[-1]
            for line in raw_err.splitlines()
            if ".kt:" in line and "error:" in line
            and target_filename not in line
        })
        state.mark(
            unit.source_path,
            last_error=f"blocked_on_dep_compile: {deps_in_error}\n{raw_err[:1500]}",
            add_note="blocked_on_dep_compile:" + ",".join(deps_in_error),
        )
        state.save()
        return result

    # Topology guard #2: errors ARE in the target file, but every one of them
    # is an unresolved reference to an in-project type we haven't translated /
    # verified yet. Same conclusion — retrying our translation won't help.
    err = filtered_err or (result.stdout or "").strip()
    blocked, missing = is_blocked_on_missing_deps(err, unit, units_by_path, state)
    if blocked:
        state.mark(
            unit.source_path,
            last_error=f"blocked_on_deps: {sorted(missing)}\n{err[:1500]}",
            add_note="blocked_on_deps:" + ",".join(sorted(missing)),
        )
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
    if not ok:
        # Don't fall back to raw stderr if the filter strips everything — an
        # empty filtered set is the signal that the target itself is clean and
        # only the bundled dep .kt files are broken. The caller uses raw_stderr
        # separately to detect that cascading-topology case.
        filtered = _filter_errors_for(proc.stderr, target.name)
        return CompileResult(
            ok=False,
            stdout=proc.stdout,
            stderr=filtered,
            returncode=proc.returncode,
            raw_stderr=proc.stderr,
        )
    return CompileResult(
        ok=True,
        stdout=proc.stdout,
        stderr=proc.stderr,
        returncode=proc.returncode,
        raw_stderr=proc.stderr,
    )


def _filter_errors_for(stderr: str, target_filename: str) -> str:
    """Keep only lines referencing the target file, plus generic 'error:' messages."""
    keep: list[str] = []
    for line in stderr.splitlines():
        if target_filename in line:
            keep.append(line)
        elif line.strip().startswith("error:") and "/" not in line:
            keep.append(line)
    return "\n".join(keep)
