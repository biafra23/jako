# jako — Java → Kotlin migration orchestrator

A thin Kotlin/JVM orchestrator that converts Java Gradle/Maven projects into Kotlin Multiplatform projects by chaining tools other people already maintain.

The principle: **deterministic first, LLM second, JetBrains owns the prompts.** We don't write Java→Kotlin conversion prompts — we vendor the JetBrains [`kotlin-agent-skills`](https://github.com/Kotlin/kotlin-agent-skills) and point `claude -p` at them.

Full design: [`plan-2-thin-orchestrator.md`](plan-2-thin-orchestrator.md).

## Per-file backend chain

Every Java file walks the same ordered chain. First eligible backend wins; on error the chain advances.

1. **J2K** — JetBrains' headless converter. Mechanical AST translation, no LLM. Falls through to passthrough if not wired up.
2. **Local LLM** — OpenAI-compatible endpoint (LM Studio / Ollama / vLLM). LOW-risk files only, reachability-probed once per run.
3. **Claude `-p`** — Haiku / Sonnet / Opus selected by risk tier, with the JetBrains `kotlin-tooling-java-to-kotlin` skill as system prompt.
4. **DeepSeek v4** — fallback when Claude's 5-hour subscription window is exhausted and `DEEPSEEK_API_KEY` is set.
5. **Wait** — park until the Claude window resets if no fallback is configured.

After each file: compile + run the original Java tests as the gate. Pass → commit. Fail → up to 3 retries, then mark `manual-review`.

## Layout

```
java-to-kotlin/                # the orchestrator (Gradle subproject)
  orchestrator/                # Kotlin/JVM sources (phase0 .. phase2)
  vendored-skills/upstream/    # git submodule, pinned to a kotlin-agent-skills commit
  config.yaml                  # project root, module, model tiers, backend toggles
  data/kmp-dependency-map.yaml # JVM → KMP-equivalent library mapping
  scripts/passthrough-j2k.sh   # fallback when headless J2K isn't built
  state/                       # generated: build-model.json, convert-order.json, run-state.json, ...
plan-2-thin-orchestrator.md    # design doc
CLAUDE.md                      # project rules (no Python, Kotlin/JVM preferred)
```

## Requirements

- JDK 21 (Gradle toolchain handles the rest).
- `claude` CLI on `PATH` for chain step 3.
- Optional: an OpenAI-compatible local server for step 2; `DEEPSEEK_API_KEY` exported for step 4.

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

- M0 (analyze) and M2 (skills vendored) — done.
- M1 (KMP scaffold), M3 (10 leaf files), M4 (full project, tiered), M5 (tests), M6 (commonMain) — pending.

## Why not just IntelliJ's "Convert to Kotlin"?

J2K alone produces non-idiomatic Kotlin and doesn't manage cross-file ordering, the test gate, KMP scaffolding, dependency mapping, or commit-per-file safety. This orchestrator wraps J2K (when available) and adds the rest.
