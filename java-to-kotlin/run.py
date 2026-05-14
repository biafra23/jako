#!/usr/bin/env python3
"""CLI entrypoint: ties the four phases together with state-based resumability.

Usage:
  ./run.py                       # full pipeline (discover -> translate -> verify -> report)
  ./run.py --phase discover      # phase 1 only
  ./run.py --phase translate     # phase 2 (resumes from state)
  ./run.py --phase verify        # phase 3 (resumes)
  ./run.py --phase report        # phase 4 only
  ./run.py --only PATH PATH ...  # restrict translate/verify to specific files
  ./run.py --force               # ignore state, redo everything in scope
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from pipeline.config_loader import load_yaml
from pipeline.discover import discover, write_discovery, JavaUnit, DiscoveryResult
from pipeline.llm_client import LLMClient, LLMConfig
from pipeline.prompts import PROMPT_VERSION
from pipeline.report import write_reports
from pipeline.state import RunState
from pipeline.translate import TranslateConfig, translate_unit
from pipeline.verify import VerifyConfig, verify_and_retry, compile_module


def _resolve(base: Path, val: str) -> Path:
    """Expand `~` and resolve relative paths against the config file's directory."""
    p = Path(val).expanduser()
    if not p.is_absolute():
        p = (base / p).resolve()
    else:
        p = p.resolve()
    return p


def _load_config(path: Path) -> dict:
    cfg = load_yaml(path)
    return cfg


def _build_llm(cfg: dict, log_dir: Path) -> LLMClient:
    llm_cfg = cfg.get("llm", {})
    return LLMClient(
        LLMConfig(
            base_url=llm_cfg.get("base_url", "http://localhost:1234/v1"),
            model_name=llm_cfg["model_name"],
            temperature=float(llm_cfg.get("temperature", 0.1)),
            max_tokens=int(llm_cfg.get("max_tokens", 16384)),
            timeout_seconds=int(llm_cfg.get("timeout_seconds", 600)),
            transient_retries=int(llm_cfg.get("transient_retries", 3)),
            transient_backoff_seconds=int(llm_cfg.get("transient_backoff_seconds", 5)),
        ),
        log_dir=log_dir,
    )


def cmd_discover(args, cfg, base: Path) -> DiscoveryResult:
    proj = cfg["project"]
    src = _resolve(base, proj["source_root"])
    test = _resolve(base, proj["test_root"]) if proj.get("test_root") else None
    out = _resolve(base, proj["output_root"])
    sep = bool(cfg.get("discover", {}).get("separate_tests", True))
    print(f"[discover] source_root={src}")
    if test:
        print(f"[discover] test_root={test}")
    print(f"[discover] output_root={out}")
    result = discover(src, out, test_root=test, separate_tests=sep)
    state_dir = _resolve(base, cfg.get("state", {}).get("dir", "state"))
    write_discovery(result, state_dir / "discovery.json")
    print(f"[discover] units={len(result.units)} groups={len(result.order)} cycles={len(result.cycles)}")
    for c in result.cycles:
        print(f"[discover] cycle ({len(c)} files): {', '.join(Path(p).name for p in c)}")
    return result


def _load_or_run_discovery(args, cfg, base: Path) -> DiscoveryResult:
    state_dir = _resolve(base, cfg.get("state", {}).get("dir", "state"))
    disc_path = state_dir / "discovery.json"
    if disc_path.exists() and not args.force:
        import json
        raw = json.loads(disc_path.read_text())
        units = [JavaUnit(**u) for u in raw["units"]]
        return DiscoveryResult(units=units, order=raw["order"], cycles=raw["cycles"])
    return cmd_discover(args, cfg, base)


def cmd_translate(args, cfg, base: Path, discovery: DiscoveryResult, llm: LLMClient, state: RunState) -> None:
    tcfg_dict = cfg.get("translate", {})
    tcfg = TranslateConfig(
        max_dependency_signatures=int(tcfg_dict.get("max_dependency_signatures", 12)),
        max_signature_chars=int(tcfg_dict.get("max_signature_chars", 4000)),
    )
    units_by_path = {u.source_path: u for u in discovery.units}

    targets = discovery.units
    if args.only:
        only_set = {str(Path(p).expanduser().resolve()) for p in args.only}
        targets = [u for u in targets if u.source_path in only_set]
        if not targets:
            print(f"[translate] --only matched no units")
            return

    for i, unit in enumerate(targets, 1):
        ust = state.get_or_create(unit.source_path)
        if not args.force and ust.status in ("translated", "verified") and ust.prompt_version == PROMPT_VERSION:
            print(f"[translate] [{i}/{len(targets)}] SKIP {Path(unit.source_path).name} (status={ust.status})")
            continue
        t0 = time.time()
        try:
            kt = translate_unit(unit, units_by_path, llm, tcfg, state=state, log_tag=f"translate_{Path(unit.source_path).stem}")
        except Exception as e:
            elapsed = time.time() - t0
            state.mark(unit.source_path, status="failed", last_error=f"translate: {e!r}")
            state.save()
            print(f"[translate] [{i}/{len(targets)}] FAIL {Path(unit.source_path).name} ({elapsed:.1f}s): {e!r}")
            continue
        elapsed = time.time() - t0
        print(f"[translate] [{i}/{len(targets)}] OK   {Path(unit.source_path).name} -> {Path(kt).name} ({elapsed:.1f}s)")


def cmd_verify(args, cfg, base: Path, discovery: DiscoveryResult, llm: LLMClient, state: RunState) -> None:
    vcfg_dict = cfg.get("verify", {})
    classpath = list(vcfg_dict.get("extra_classpath") or [])
    cp_file = vcfg_dict.get("classpath_file")
    if cp_file:
        cp_path = _resolve(base, cp_file)
        if cp_path.exists():
            for line in cp_path.read_text().splitlines():
                line = line.strip()
                if line and not line.startswith("#"):
                    classpath.append(line)
            print(f"[verify] classpath_file={cp_path} ({len(classpath)} entries total)")
        else:
            print(f"[verify] WARNING: classpath_file not found: {cp_path}")
    vcfg = VerifyConfig(
        mode=vcfg_dict.get("mode", "both"),
        max_retries=int(vcfg_dict.get("max_retries", 3)),
        extra_classpath=classpath,
    )
    tcfg_dict = cfg.get("translate", {})
    tcfg = TranslateConfig(
        max_dependency_signatures=int(tcfg_dict.get("max_dependency_signatures", 12)),
        max_signature_chars=int(tcfg_dict.get("max_signature_chars", 4000)),
    )
    units_by_path = {u.source_path: u for u in discovery.units}

    targets = discovery.units
    if args.only:
        only_set = {str(Path(p).expanduser().resolve()) for p in args.only}
        targets = [u for u in targets if u.source_path in only_set]

    if vcfg.mode in ("per_file", "both"):
        for i, unit in enumerate(targets, 1):
            ust = state.get_or_create(unit.source_path)
            if ust.status == "verified" and not args.force:
                print(f"[verify] [{i}/{len(targets)}] SKIP {Path(unit.source_path).name} (verified)")
                continue
            if ust.status not in ("translated", "verified", "failed"):
                print(f"[verify] [{i}/{len(targets)}] SKIP {Path(unit.source_path).name} (status={ust.status})")
                continue
            if not Path(unit.target_path).exists():
                print(f"[verify] [{i}/{len(targets)}] SKIP {Path(unit.source_path).name} (no output)")
                continue
            t0 = time.time()
            res = verify_and_retry(unit, units_by_path, llm, vcfg, tcfg, state)
            elapsed = time.time() - t0
            tag = "OK" if res.ok else "FAIL"
            print(f"[verify] [{i}/{len(targets)}] {tag} {Path(unit.source_path).name} ({elapsed:.1f}s, retries={state.units[unit.source_path].retry_count})")

    if vcfg.mode in ("per_module", "both"):
        out = _resolve(base, cfg["project"]["output_root"])
        print(f"[verify] module compile sweep on {out}")
        res = compile_module(out, extra_classpath=vcfg.extra_classpath)
        if res.ok:
            print(f"[verify] module compile OK")
        else:
            err = (res.stderr or res.stdout).strip()
            print(f"[verify] module compile FAILED:\n{err[:4000]}")


def cmd_report(args, cfg, base: Path, discovery: DiscoveryResult, state: RunState, wall: float | None) -> None:
    state_dir = _resolve(base, cfg.get("state", {}).get("dir", "state"))
    write_reports(
        discovery,
        state,
        json_path=state_dir / "report.json",
        md_path=state_dir / "report.md",
        wall_clock_seconds=wall,
    )
    print(f"[report] wrote {state_dir / 'report.json'} and {state_dir / 'report.md'}")


def main() -> int:
    ap = argparse.ArgumentParser(description="Java -> Kotlin conversion pipeline")
    ap.add_argument("--config", default="config.yaml", help="path to config.yaml")
    ap.add_argument(
        "--phase",
        choices=["discover", "translate", "verify", "report", "all"],
        default="all",
    )
    ap.add_argument("--force", action="store_true", help="ignore existing state, redo work")
    ap.add_argument("--only", nargs="+", help="restrict translate/verify to specific source files")
    args = ap.parse_args()

    cfg_path = Path(args.config).expanduser().resolve()
    if not cfg_path.exists():
        print(f"config not found: {cfg_path}", file=sys.stderr)
        return 2
    base = cfg_path.parent
    cfg = _load_config(cfg_path)

    state_dir = _resolve(base, cfg.get("state", {}).get("dir", "state"))
    state_path = state_dir / "run-state.json"
    state = RunState.load(state_path) if not args.force else RunState(path=state_path)
    state.path = state_path

    llm = _build_llm(cfg, log_dir=state_dir / "llm-log")

    t0 = time.time()
    if args.phase in ("discover", "all"):
        discovery = cmd_discover(args, cfg, base)
    else:
        discovery = _load_or_run_discovery(args, cfg, base)

    if args.phase in ("translate", "all"):
        cmd_translate(args, cfg, base, discovery, llm, state)

    if args.phase in ("verify", "all"):
        cmd_verify(args, cfg, base, discovery, llm, state)

    if args.phase in ("report", "all"):
        cmd_report(args, cfg, base, discovery, state, wall=time.time() - t0)

    return 0


if __name__ == "__main__":
    sys.exit(main())
