# jako — Java → Kotlin migration orchestrator

A thin Kotlin/JVM orchestrator that converts Java Gradle/Maven projects into Kotlin Multiplatform projects by chaining tools other people already maintain.

The principle: **deterministic first, LLM second, JetBrains owns the prompts.** We don't write Java→Kotlin conversion prompts — we vendor the JetBrains [`kotlin-agent-skills`](https://github.com/Kotlin/kotlin-agent-skills) and point `claude -p` at them.

Full design: [`plan-2-thin-orchestrator.md`](plan-2-thin-orchestrator.md).

## Per-file backend chain

Every Java file walks the same ordered chain. First eligible backend wins; on error the chain advances.

1. **J2K — two strategies**:
   - `passthrough` (default): the `.kt` slot starts as a verbatim copy of the `.java` text and the LLM step does the entire conversion. Zero setup, slower refines.
   - `external` via `scripts/run-j2k-headless.sh`: drives JetBrains' real J2K converter headlessly through the bundled `:j2k-plugin` IntelliJ plugin. Output is already syntactically Kotlin so refine just polishes. Trade-off: ~20s of IDE startup per file (heavy for SCC batches today; a batch mode is the next PR target).
2. **Local LLM** — OpenAI-compatible endpoint (LM Studio / Ollama / vLLM). LOW-risk files only, reachability-probed once per run.
3. **Claude `-p`** — Haiku / Sonnet / Opus selected by risk tier, with the JetBrains `kotlin-tooling-java-to-kotlin` skill as system prompt.
4. **DeepSeek v4** — fallback when Claude's 5-hour subscription window is exhausted and `DEEPSEEK_API_KEY` is set.
5. **Wait** — park until the Claude window resets if no fallback is configured.

After each file: compile + run the original Java tests as the gate. Pass → commit. Fail → up to 3 retries, then mark `manual-review`.

## Layout

```
java-to-kotlin/                # the orchestrator (Gradle subproject)
  orchestrator/                # Kotlin/JVM sources (phase0 .. phase2)
  j2k-plugin/                  # optional IntelliJ plugin: headless J2K driver
  vendored-skills/upstream/    # git submodule, pinned to a kotlin-agent-skills commit
  config.yaml                  # project root, module, model tiers, backend toggles
  data/kmp-dependency-map.yaml # JVM → KMP-equivalent library mapping
  scripts/passthrough-j2k.sh   # default `cp` J2K step
  scripts/run-j2k-headless.sh  # opt-in real J2K via :j2k-plugin
  state/                       # generated: build-model.json, convert-order.json, run-state.json, ...
plan-2-thin-orchestrator.md    # design doc
CLAUDE.md                      # project rules (no Python, Kotlin/JVM preferred)
```

## Requirements

- JDK 21 (Gradle toolchain handles the rest).
- `claude` CLI on `PATH` for chain step 3.
- Optional: an OpenAI-compatible local server for step 2; `DEEPSEEK_API_KEY` exported for step 4.

### Opting into real J2K (`scripts/run-j2k-headless.sh`)

Flip `j2k.strategy: external` and `command: scripts/run-j2k-headless.sh` in `config.yaml`. **The first invocation downloads IntelliJ Community ~600 MB** into Gradle's cache (`~/.gradle/caches/.../ideaIC-<version>-<arch>/`). Gradle prints the download progress to stdout — if a run seems to be hanging on the first J2K call, check the Gradle output; it's almost certainly mid-download. Subsequent runs use the cache and start the IDE in ~20s.

No need to have IntelliJ installed locally; everything runs out of Gradle's cache. Works on macOS (Apple Silicon + Intel), Linux, and Windows — the IntelliJ Platform Gradle plugin picks the right artifact per host arch.

## Running

```bash
git clone --recurse-submodules https://github.com/biafra23/jako.git
cd jako/java-to-kotlin

# Point config.yaml at the target Gradle project + module, then:
./gradlew :orchestrator:run --args="--phase analyze"   # build graph, dep order, risk tiers
./gradlew :orchestrator:run --args="--phase scaffold"  # rewrite target to KMP layout
./gradlew :orchestrator:run --args="--phase convert"   # file-by-file conversion
./gradlew :orchestrator:run --args="--phase report"    # status / risk / model counts
./gradlew :orchestrator:run --args="--phase all"       # the four above, in order
```

`--force` ignores cached analysis/state. `--only PATH...` restricts convert to specific files.

## Status

- M0 analyze, M1 scaffold, M2 skills vendored, M3/M4 production convert (parallel + per-file refine#2), M5 test convert — done.
- M6 commonMain migration — post-MVP per the plan; deferred.
- Headless J2K plugin available as an opt-in (`scripts/run-j2k-headless.sh`); a batch mode that amortises IDE startup across all files in an SCC is the next planned PR.

## Why not just IntelliJ's "Convert to Kotlin"?

J2K alone produces non-idiomatic Kotlin and doesn't manage cross-file ordering, the test gate, KMP scaffolding, dependency mapping, or commit-per-file safety. This orchestrator wraps J2K (mechanical) with refine (LLM) and adds the rest — Phase 0 analysis, Phase 1 scaffold, Phase 2 convert with parallel refine + per-file refine#2 + a gradle gate + per-file commits.
