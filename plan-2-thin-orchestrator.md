# Plan 2 — Thin Orchestrator over JetBrains Tooling

A leaner alternative to the original plan. Same end goal (Java/Gradle project → working Kotlin Multiplatform project, tests stay in Java during migration), but instead of building the conversion engine ourselves, we orchestrate three things JetBrains already maintains:

1. **The JetBrains J2K converter** — the same engine IntelliJ uses, also wrapped by the new `Kotlin/j2k-vscode` extension. Handles ~70-85% of mechanical Java → Kotlin translation deterministically.
2. **The `Kotlin/kotlin-agent-skills` repository** — JetBrains' official collection of agent skills. The `kotlin-tooling-java-to-kotlin` skill is the prompt-engineering layer for the LLM-driven idiomatic refinement step. The `kotlin-tooling-agp9-migration` skill is reusable later.
3. **Claude Code CLI (`claude -p`)** — the agent runtime that loads those skills and drives the file-by-file refinement.

Everything else — Gradle analysis, KMP scaffolding, the test gate, commit-per-file, conversion ordering — is our orchestrator. It is small (~400-800 LOC) and contains no LLM prompts of our own, only deterministic glue.

The principle is unchanged: **deterministic-first, LLM second, JetBrains owns the prompts.** Anything that has an authoritative answer is computed; anything that needs language-model judgment is delegated to a JetBrains-maintained skill. We do not write or maintain Java→Kotlin conversion prompts ourselves.

---

## What we keep, what we delete from Plan 1

| Plan 1 phase | Plan 2 status |
|---|---|
| 0.1 Build-graph extraction (Gradle Tooling API) | **Keep.** Nobody has this. |
| 0.2 Source-file inventory | **Keep.** |
| 0.3 Dependency graph + topological sort (JavaParser) | **Keep.** Nobody has this. |
| 0.4 Risk classification (LOW/MED/HIGH) | **Keep, smaller.** Used for model selection only. |
| 0.5 Dependency mapping table (KMP-equivalent libraries) | **Keep.** Curated YAML. |
| 1.1 Generate KMP `build.gradle.kts` | **Keep.** Templating, no AI. |
| 1.2 Move `.java` files into `jvmMain/java` | **Keep.** Pure scripting. |
| 2.1 First pass: J2K | **Keep, but use the headless invocation pattern from `j2k-vscode`** rather than calling J2K's internal API directly. |
| 2.2 Second pass: idiomatic refinement (custom prompts per tier) | **Replace.** Use `claude -p` with the JetBrains `kotlin-tooling-java-to-kotlin` skill. We do not author prompts. |
| 2.3 Compile + test gate | **Keep.** This is the safety net and it's ours. |
| 2.4 Cycles | **Keep.** SCC handling stays our responsibility. |
| Phase 3 — multiplatform migration | **Keep, but watch upstream.** JetBrains may ship a skill for this; if so, we orchestrate it instead of writing it. |
| Phase 4 — test conversion | **Keep.** Same JetBrains skill, just pointed at test files. |

The custom system prompts in `prompts/` (idiomatic-kotlin.md, reflection-heavy.md, etc.) from Plan 1 are **deleted**. They become a thin model-selection table that just picks which Claude model to give the JetBrains skill.

---

## Architecture

```
orchestrator/
├── pyproject.toml
├── orchestrator/
│   ├── main.py
│   ├── phase0_analyze.py        # build-graph, AST, dependency map (unchanged from Plan 1)
│   ├── phase1_scaffold.py       # generate build.gradle.kts, move files (unchanged from Plan 1)
│   ├── phase2_convert.py        # the loop — but the loop body is much shorter now
│   ├── j2k_runner.py            # headless J2K invocation
│   ├── claude_skill_runner.py   # `claude -p` with --append-system-prompt-file pointing at vendored skill
│   ├── gradle_runner.py
│   ├── git_runner.py
│   └── models.py
├── vendored-skills/             # git submodule of Kotlin/kotlin-agent-skills, pinned to a commit
│   └── skills/
│       ├── kotlin-tooling-java-to-kotlin/SKILL.md
│       └── kotlin-tooling-agp9-migration/SKILL.md
├── data/
│   └── kmp-dependency-map.yaml  # still ours; nobody else has this
└── tests/
    └── fixtures/
```

Two key files replace most of what Plan 1 owned:

- `claude_skill_runner.py` is **the** integration point. It builds a `claude -p` command that loads a JetBrains skill via `--append-system-prompt-file` (or by placing it under `.claude/skills/` and letting Claude Code's skill auto-discovery pick it up — see notes below).
- `vendored-skills/` is a pinned git submodule of the upstream JetBrains repo. We never modify these files. When JetBrains updates a skill, we bump the submodule.

---

## Phase 0 — Deterministic project analysis (unchanged from Plan 1)

Same five outputs (`build-model.json`, `source-inventory.json`, `convert-order.json`, `risk-classification.json`, `dependency-mapping.yaml`) produced by the same techniques (Gradle Tooling API, JavaParser, topological sort, curated YAML). No changes.

The risk classification is still useful but only for **model selection** — LOW → Haiku, MEDIUM → Sonnet, HIGH → Opus. The skill itself is the same in all cases.

---

## Phase 1 — KMP scaffolding (unchanged from Plan 1, with one bonus)

Same templating job. One small addition: if the source project uses Android Gradle Plugin and is targeting AGP 9+, vendor the **`kotlin-tooling-agp9-migration`** skill from the same JetBrains repo and run it once on the generated build files as a sanity pass. JetBrains already wrote and maintains the prompts for that migration; we just invoke them.

```bash
claude -p --model sonnet \
  --append-system-prompt-file vendored-skills/skills/kotlin-tooling-agp9-migration/SKILL.md \
  --permission-mode acceptEdits \
  "Apply the AGP 9 migration to this project. Working directory: $PROJECT_ROOT"
```

For a non-Android JVM project this step is skipped.

---

## Phase 2 — File-by-file conversion (the heart of Plan 2)

Per file, in `convert-order.json` order:

### 2.1 Headless J2K (deterministic first pass)
Same as Plan 1. Two viable invocation strategies:

- **Option A — Headless IntelliJ.** Use `idea.sh` in headless mode with a tiny IntelliJ plugin or a pre-existing community wrapper that calls `JavaToKotlinAction`. Meta did this internally.
- **Option B — Re-use the `j2k-vscode` engine.** The `Kotlin/j2k-vscode` extension is open-source and Apache-licensed; its conversion-engine entry point is invokable from a Node.js process. Wrap it in a small CLI shim.

Option B is preferred — it's smaller, faster to start, and tracks the upstream engine. Option A is the fallback if the VS Code extension's engine turns out to be hard to extract.

**Critical clarification:** the `j2k-vscode` extension *does* its own LLM call internally for the idiomatic-refinement step (it supports OpenAI / OpenRouter / Ollama / GitHub Copilot, no Anthropic provider yet). For Plan 2 we want to **use only its mechanical-translation pass**, not its LLM step, so that step 2.2 (below) can use Claude via the skill. If the extension can't easily be split this way, fall back to Option A (headless IntelliJ J2K — pure mechanical conversion, no LLM involved).

Output: a `.kt` file that compiles syntactically but is non-idiomatic.

### 2.2 Idiomatic refinement via JetBrains skill + Claude

This is the step that replaces Plan 1's custom prompts.

```bash
claude -p \
  --model "$MODEL_FOR_RISK_TIER" \
  --output-format json \
  --max-turns 3 \
  --permission-mode acceptEdits \
  --append-system-prompt-file vendored-skills/skills/kotlin-tooling-java-to-kotlin/SKILL.md \
  "Refine the Kotlin file at $KOTLIN_FILE.
   The original Java is at $JAVA_FILE for reference.
   Constraints:
   - Public API must remain Java-callable (Java tests in jvmTest/java will compile against this file).
   - Add @JvmStatic / @JvmField / @JvmOverloads / @JvmName as needed for interop.
   - No new external dependencies.
   Apply the JetBrains java-to-kotlin skill conventions."
```

Model selection table (from Phase 0.4):

| Risk tier | Model |
|---|---|
| LOW | `claude-haiku-4-5-20251001` |
| MEDIUM | `claude-sonnet-4-6` |
| HIGH | `claude-opus-4-7` |

Three things to notice about this step compared to Plan 1:

- **No `prompts/` directory of our own.** The system prompt is the JetBrains skill file. We pass it via `--append-system-prompt-file`. Whatever knowledge JetBrains encodes (about JUnit edge cases, Spring proxying, JPA entity nuances, `data class` traps for Hibernate, etc.) we get for free.
- **The user prompt is short and stable.** It's just the interop constraints plus the file paths. No language-conversion intelligence lives in our prompt.
- **The skill is versioned.** When JetBrains improves their skill, we `git submodule update` and re-run regression fixtures.

Two integration paths for the skill:

- **Path A — `--append-system-prompt-file`.** Simplest. The skill markdown is appended to Claude Code's system prompt for that single invocation.
- **Path B — Skill auto-discovery.** Place a copy (or symlink) of the JetBrains skill under `.claude/skills/` in the project root. Claude Code discovers it and loads it when its `description` matches. Cleaner long-term; lets the `description` field do the routing.

Start with Path A. It's a single CLI flag, fully explicit, easy to debug. Migrate to Path B once the orchestrator is stable.

### 2.3 Compile + test gate (unchanged from Plan 1)
The compiler and the original Java test suite remain the verification truth. This is ours to own; no skill replaces a green build.

```bash
./gradlew :module:compileKotlinJvm :module:test
```

Pass → commit. Fail → analyze the error class:

- Deterministic-fixable (missing import, missing `@JvmStatic`) → fix with a script.
- LLM-fixable → re-invoke step 2.2 once with the compiler error appended to the user prompt.
- Three strikes → revert, mark `manual-review`, move on.

### 2.4 Cycles (unchanged from Plan 1)
SCC detection runs in Phase 0. For cyclic groups, batch all files in the cycle into a single skill invocation, with all source files passed in the user prompt. The skill content stays the same.

---

## Phase 3 — Multiplatform migration (post-MVP, watch upstream)

When the project is fully Kotlin/JVM under the multiplatform plugin, moving code to `commonMain` involves the deterministic step (which symbols are KMP-safe?) and the `expect`/`actual` design step (which JVM-only code should become a multiplatform abstraction?).

**The deterministic step stays ours.** Walk each `.kt` file's AST, check imports against an allowlist of multiplatform-safe packages (`kotlin.*`, `kotlinx.*`, etc.), produce a `commonmain-eligible.json`. Move the green-listed files with `git mv`. Recompile.

**The `expect`/`actual` step is a candidate for a future JetBrains skill.** As of writing, no such skill exists in `kotlin-agent-skills`, but the repo is actively expanding (categories include `tooling`, `backend`, with more on the way). Two reasonable strategies:

- **Now:** invoke `claude -p` *without* a skill for these files, with a short user prompt describing the `expect`/`actual` pattern. Use Sonnet by default, Opus for trickier abstractions. Accept that this part is less leveraged than Phase 2.
- **Later:** monitor the JetBrains repo. If/when a `kotlin-tooling-expect-actual` (or similar) skill appears, vendor it and switch this step to skill-driven mode.

This is the one phase where Plan 2 is no leaner than Plan 1 today. That's fine — Phase 3 is post-MVP and can wait.

---

## Phase 4 — Convert tests (unchanged from Plan 1)

Same JetBrains skill, same orchestrator loop, just pointed at `src/jvmTest/java/**/*.java`. Production code is already Kotlin and proven by the still-Java test suite, so this phase is the lowest-risk one. Migrate JUnit 4/5 → `kotlin.test` selectively for tests that should run on multiple targets; tests exercising JVM-only behavior can stay JUnit.

---

## What changes if `j2k-vscode` adds an Anthropic provider

The `j2k-vscode` extension currently supports OpenAI, OpenRouter, Ollama, and GitHub Copilot as LLM backends — no Anthropic. If that changes (or someone — possibly us — submits a PR adding a Claude provider), the calculus shifts:

- Phase 2.1 and 2.2 collapse into a single `j2k-vscode` invocation with Claude as the configured backend.
- The orchestrator's job shrinks further: drive the extension headlessly per file, wrap it in our compile/test/commit loop.
- We lose the model-tiering control unless `j2k-vscode` exposes per-call model selection. That may or may not be worth giving up; for a small project it's fine, for a 100k-file project the cost difference between Haiku and Opus is real.

This is worth tracking but not worth blocking on. Ship Plan 2 against the JetBrains skill first.

---

## Risks specific to Plan 2

| Risk | Mitigation |
|---|---|
| The `kotlin-tooling-java-to-kotlin` skill changes shape and breaks our invocation | Pin the submodule to a specific commit. Bump deliberately, with regression fixtures. |
| The skill's `description` triggers on cases we don't want, or doesn't trigger when we do | Use Path A (`--append-system-prompt-file`) which is unconditional, not Path B (auto-discovery) until behavior is well understood. |
| Headless J2K is harder to drive than expected | Have both Option A (IntelliJ headless) and Option B (`j2k-vscode` engine) as candidates; pick whichever extracts cleanly first. |
| The skill assumes IntelliJ context (it can call IntelliJ refactorings) that aren't available to Claude Code | Read the SKILL.md before vendoring. If the skill is IntelliJ-coupled, fall back to a plain `claude -p` with a short, hand-written user prompt for Phase 2.2 only. The other phases are unaffected. |
| JetBrains discontinues or relicenses the skills repo | The submodule is pinned; we keep working off the pinned commit. Worst case, we fork. |

---

## Suggested milestones (revised)

1. **M0 — Phase 0 alone.** Same as Plan 1. Validates the analyzer.
2. **M1 — Phase 1 alone.** Same as Plan 1. Project moved to KMP layout, builds green, tests green, **zero conversion done**.
3. **M2 — Vendor the JetBrains skills.** Add the submodule, write `claude_skill_runner.py`, run it manually on one file end-to-end. No orchestrator yet.
4. **M3 — Phase 2 on 10 leaf files.** Sonnet only, Path A invocation. Validates the skill+J2K combination and the compile/test/commit loop.
5. **M4 — Phase 2 on the full project, tiered.** Haiku/Sonnet/Opus routing live.
6. **M5 — Phase 4 (tests).**
7. **M6 — Phase 3 (commonMain migration).** Deterministic part now; skill-driven part if/when JetBrains ships one.

The big difference from Plan 1's milestones: M2 exists, and M3 is significantly cheaper to build because we are not authoring or testing Kotlin-conversion prompts.

---

## Comparison summary

|  | Plan 1 (build it ourselves) | Plan 2 (orchestrate JetBrains) |
|---|---|---|
| Custom Java→Kotlin prompts to write & maintain | Yes, per risk tier | None |
| Lines of orchestrator code | ~800-1500 | ~400-800 |
| Quality of conversion prompts | Whatever we can write | JetBrains-maintained, evolves with Kotlin |
| Coupling to upstream | Low | Submodule on `Kotlin/kotlin-agent-skills` |
| Works if `kotlin-agent-skills` becomes inactive | N/A | Pinned commit keeps working; can fork |
| Phase 0/1 effort | Same | Same |
| Phase 3 effort today | Same | Same (skill doesn't exist yet) |
| Right default | When you need full control or can't take an external dependency | When you want the smallest thing that could work |

Plan 2 is the recommended starting point. Plan 1 is the right answer if `kotlin-agent-skills` turns out to be the wrong shape, or if the project's constraints (private network, no submodules of external repos, etc.) rule out vendoring upstream skills.
