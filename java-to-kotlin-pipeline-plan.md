# Java → Non-Idiomatic Kotlin Conversion Pipeline

A detailed implementation plan for a custom, locally-run conversion pipeline.

**Design pattern:** Claude Opus designs and writes the orchestration tooling once. Gemma 4 31B (Q4_K_M, served by LM Studio) does the per-file translation work at runtime. No cloud calls during the actual conversion run.

**Target environment:** 64GB M1 Mac, LM Studio serving `google/gemma-4-31b` (Q4_K_M GGUF) on its local OpenAI-compatible endpoint (`http://localhost:1234/v1`), loaded with a context length of 64141 tokens.

**Model choice note:** An earlier version of this plan called for `Gemma 4 26B A4B Instruct Q6_K`. We switched to the larger 31B at Q4_K_M because it is the model that is loaded on this host with a usable context window; LM Studio's JIT-loaded models default to their GGUF-metadata context (≈4 K tokens), which truncates the reasoning-tokens stream this Gemma variant emits before any answer reaches `content`. The pipeline is model-agnostic — change `llm.model_name` in `config.yaml` to swap.

**Conversion goal:** Faithful, structure-preserving ("non-idiomatic") Kotlin — a 1:1 mechanical translation that compiles and behaves identically, *not* a refactor into idiomatic Kotlin patterns.

---

## 1. Overview and architecture

The pipeline is a Python program that runs in four phases:

1. **Discovery & ordering** — walk the Java project, parse dependencies, produce a topologically sorted list of files to convert.
2. **Translation loop** — for each file, build a prompt, call the local Gemma endpoint, write the resulting Kotlin to a parallel source tree.
3. **Verification & retry** — compile each converted file (or the whole module) with `kotlinc`; on failure, feed the error back to Gemma for a bounded number of retries.
4. **Reporting** — emit a structured log of what succeeded, what failed, and why; optionally a final review pass.

The phases are separable. You should be able to re-run phase 2 on a single file without re-running discovery, and re-run verification without re-translating. Persist state between phases (see §7).

```
java-to-kotlin/
  pipeline/
    __init__.py
    discover.py        # phase 1
    translate.py       # phase 2
    verify.py          # phase 3
    report.py          # phase 4
    llm_client.py      # LM Studio API wrapper
    prompts.py         # prompt templates
    state.py           # load/save run state (JSON)
  config.yaml          # project paths, model settings, knobs
  run.py               # CLI entrypoint, ties phases together
  state/               # persisted run state + logs
  output/              # generated Kotlin tree (mirrors Java package layout)
```

---

## 2. Phase 1 — Discovery and ordering

### 2.1 File discovery

Walk the configured source root (e.g. `src/main/java`) for `*.java` files. Keep `src/test/java` as a separate set — tests are converted last and benefit from different prompt instructions.

Record for each file: absolute path, package, declared top-level type name(s), and whether it is production or test code.

### 2.2 Dependency extraction

For correct ordering you need a dependency graph. Two viable approaches:

- **Lightweight (recommended to start):** parse `import` statements plus same-package type references with regex / simple tokenization. Imprecise but good enough to get an approximate order. Java's `import` statements give you most of the cross-package edges.
- **Accurate:** use a real parser — `javalang` (pure Python, easy) or tree-sitter with the Java grammar. This resolves same-package references and inner classes properly.

Start lightweight; upgrade to `javalang` if ordering problems show up in practice.

### 2.3 Topological sort

Build a directed graph (file A depends on file B if A references a type declared in B). Topologically sort so dependencies are converted before dependents. This means that when Gemma converts a file, the Kotlin versions of its dependencies already exist and can be included as reference context.

Handle cycles explicitly: Java allows circular dependencies between classes. Detect strongly-connected components (Tarjan's algorithm — `networkx` provides it) and convert each cycle as a group, or just break the cycle arbitrarily and rely on the retry loop to fix fallout. Log every cycle so you know where manual attention may be needed.

### 2.4 Output of phase 1

A JSON file: ordered list of conversion units, each with source path, target path, package, type names, direct dependencies, and a `category` (`interface`, `class`, `enum`, `test`, etc.). Categories let you apply tailored prompt instructions later.

---

## 3. Phase 2 — Translation loop

### 3.1 Per-file context assembly

For each conversion unit, the prompt contains:

- **The Java source file** being converted.
- **Signatures of its direct dependencies** — not full Kotlin files, just the public API surface (class/function/property signatures) of the already-converted Kotlin dependencies. This keeps the model consistent with names and types it already produced, without blowing the context budget. Extract these signatures from the generated Kotlin files (or cache them as you go).
- **The conversion instructions** (see §4).

Do **not** dump the entire converted project into context. Direct dependencies only. The 256K context window is generous but quality still degrades on very long prompts, and you want speed.

### 3.2 The LLM client

A thin wrapper around the LM Studio endpoint (`POST /v1/chat/completions`, OpenAI-compatible schema). Requirements:

- Configurable `temperature` — set this **low** (0.0–0.2) for deterministic, faithful translation.
- Generous `max_tokens` — Kotlin output is roughly comparable in length to the Java input; size the cap to the input file plus headroom.
- Timeout and a simple retry-on-transient-error (connection dropped, etc.) — distinct from the *semantic* retry loop in phase 3.
- Log every request/response pair to disk for debugging and for the review pass.

### 3.3 Output extraction

Instruct the model to return only the Kotlin file content inside a single fenced code block. Parse out the block. If the model wraps it in commentary anyway, the fence extraction still works. If no fence is found, treat it as a failed unit and log it.

Write the extracted Kotlin to the mirrored path in `output/` (e.g. `src/main/java/com/foo/Bar.java` → `output/src/main/kotlin/com/foo/Bar.kt`).

### 3.4 Parallelism

A single LM Studio instance serves requests sequentially — there is no real concurrency to exploit on one model instance, and parallel requests will just queue. So phase 2 is effectively sequential. What you *can* do:

- Pipeline phases 2 and 3: as soon as a file is translated, hand it to verification while the next file translates. Modest speedup.
- If you have a second machine or want to run two LM Studio instances, you could shard independent (non-dependent) files across them — but this is an optimization to defer.

Realistically: expect this to run for a while on a 31B model. Make it resumable (§7) so an interrupted run is cheap to continue. Run it overnight.

---

## 4. Prompt design

This is the highest-leverage part of the project, and the main reason to have Opus design the pipeline. Keep prompts in `prompts.py` as templates, versioned, so you can iterate.

### 4.1 Core instruction (the system prompt)

Key points the prompt must establish:

- Translate the given Java file to Kotlin.
- **Preserve structure 1:1.** Do not refactor into idiomatic Kotlin. Keep the same class layout, method order, field declarations, and control flow. The goal is a faithful mechanical port.
- Explicitly enumerate what *not* to do: don't collapse classes into `data class` unless trivially equivalent, don't replace loops with functional chains, don't introduce scope functions (`let`/`apply`/`also`) where Java had plain statements, don't "improve" null handling beyond what's required to compile.
- Do make the *mandatory* adjustments: Java types → Kotlin types, `;` removal, `final` → `val`, getters/setters as needed, nullability annotations honored where present.
- Output only the Kotlin file content in one fenced ```kotlin block, no commentary.

### 4.2 Category-specific addenda

Append extra instructions based on the unit's `category`:

- **Interfaces:** straightforward; note Kotlin interface property/method syntax.
- **Enums:** Java enums with bodies/methods need care — call this out.
- **Tests:** preserve the test framework calls (JUnit annotations carry over), keep test method names verbatim, don't restructure assertions.

### 4.3 Retry prompt

A distinct template for phase 3: includes the original Java, the broken Kotlin, and the `kotlinc` error output, asking for a corrected file with the same faithfulness constraints.

---

## 5. Phase 3 — Verification and retry

### 5.1 Compilation check

After a file is translated, compile it. Two granularities:

- **Per-file** is fast feedback but `kotlinc` needs the dependencies on the classpath — so you compile against the already-converted Kotlin (or against the original compiled Java classes as a stand-in).
- **Per-module** at the end is the real test — the whole converted source set compiled together. Cross-file inconsistencies (a name Gemma rendered differently in two places) only show up here.

Recommended: per-file compile for fast iteration during the run, then a full per-module compile in phase 3's final step to catch integration errors.

### 5.2 Retry loop

On compilation failure:

1. Capture the `kotlinc` stderr.
2. Build a retry prompt (§4.3) with Java + broken Kotlin + error.
3. Call Gemma again, write the new output, recompile.
4. Cap at **N retries** (start with N=2 or 3). After the cap, mark the file `failed`, keep the best attempt, and move on — do not let one file stall the run.

Log every retry attempt with its error so you can see patterns (e.g. "Gemma consistently mishandles `X`") and fix them via prompt edits rather than brute-force retries.

### 5.3 Test execution (optional, later)

Compilation proving is the baseline. If you want behavioral confidence, run the converted test suite against the converted code as a final, separate step. This is valuable but adds significant complexity (test framework setup, build tooling) — defer it to a v2 of the pipeline.

---

## 6. Phase 4 — Reporting and review

### 6.1 Structured run report

Emit a summary (JSON + a human-readable Markdown digest):

- Counts: total files, converted clean, converted after retry, failed.
- Per-file status with retry count and final error (if failed).
- List of dependency cycles encountered.
- Total wall-clock time and average time per file.

### 6.2 Optional Opus review pass

A separate, *cloud* step (not part of the local run): sample N files — especially ones that needed retries — and have Claude Opus review the Java/Kotlin pairs for **systematic** issues that compile fine but are wrong (subtle semantic drift, incorrect nullability, mistranslated generics). The output is feedback on your prompt templates, not per-file fixes. Run this once after a pilot batch, fold the lessons into `prompts.py`, then do the full run.

---

## 7. State management and resumability

The run will be long. Treat interruption as normal, not exceptional.

- Persist a `state.json` keyed by source file path, recording each unit's status (`pending` / `translated` / `verified` / `failed`), retry count, output path, and timestamps.
- On startup, load state and skip anything already `verified`. `--force` flag to re-do specific files or the whole run.
- Write state after every file, not at the end.
- Keep the full request/response log per file so a failed unit can be debugged or re-prompted without re-running discovery.

---

## 8. Configuration

A single `config.yaml`:

- `source_root`, `test_root`, `output_root`
- LM Studio: `base_url`, `model_name`, `temperature`, `max_tokens`, `timeout`
- `max_retries`
- `dependency_parser`: `regex` | `javalang`
- `verify_mode`: `per_file` | `per_module` | `both`
- `context`: how many dependency signatures to include, truncation limits

Everything tunable lives here so you are not editing code between runs.

---

## 9. Build sequence (how to actually build this)

A suggested order for the Opus-assisted scripting session, smallest testable increment first:

1. **`llm_client.py`** — get a single hardcoded prompt round-tripping through LM Studio. Proves the endpoint works.
2. **`discover.py` (regex mode)** — produce the ordered JSON for your real project. Eyeball the order.
3. **`translate.py`** — convert *one* file end to end, with hand-built context. Inspect the Kotlin by hand.
4. **`prompts.py`** — iterate on the template using that one file plus 3–4 more covering different categories (interface, enum, test, a gnarly class). This is where you spend real time.
5. **`verify.py`** — add per-file `kotlinc` compilation and the retry loop.
6. **`state.py` + `run.py`** — wire phases together, add resumability, run a ~10-file pilot batch.
7. **Opus review pass** on the pilot output → refine prompts.
8. **Full run**, overnight.
9. **`report.py`** — full-module compile + summary.

Do not build all of it before running any of it. The prompt template (step 4) is the part that determines whether the whole thing works, so get to a real translated file as fast as possible.

---

## 10. Known risks and mitigations

- **Cross-file naming drift** — Gemma renders a type or member differently in two files. Mitigation: include dependency signatures in context (§3.1); catch the rest at full-module compile.
- **Dependency cycles** — break into SCCs, convert as groups, lean on retries, log them all.
- **Generics and wildcards** — Java's `? extends` / `? super` map to Kotlin variance in non-obvious ways; a frequent retry cause. Consider a category-specific note if it recurs.
- **Checked exceptions** — Kotlin has none; `throws` clauses just drop. Make sure the prompt says so explicitly or the model may invent annotations.
- **Static members / nested classes** — `companion object` vs. top-level; call this out in the core instruction.
- **Throughput** — a 31B model on one M1 is not fast. Resumability + overnight runs are the answer, not heroic optimization.
- **Silent semantic errors** — compiles, runs, wrong. Only the optional test-execution step or the Opus review really catches these. Be honest with yourself about how much behavioral verification you need before trusting the output.
