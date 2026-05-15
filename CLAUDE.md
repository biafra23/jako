# Project rules for `/Users/biafra/jako`

## Language constraint — no Python

**No Python code and no Python runtime dependency** anywhere under this project.

- Preferred language: Kotlin on the JVM.
- Acceptable: Java, shell (`bash`/`zsh`), Node/TypeScript when wrapping a JS-only library.
- Not acceptable: `*.py`, `pyproject.toml`, `requirements.txt`, a `.venv/`, or shelling out to Python from anywhere.

The earlier Plan-1 / Gemma-4 era used Python; that exception no longer applies. If a tool you need only exists in Python, raise it with the user before adding any Python — do not silently reintroduce a runtime.

## Pipeline

The Java→Kotlin orchestrator lives under `java-to-kotlin/`. Design is in `plan-2-thin-orchestrator.md` (orchestrate JetBrains J2K + `kotlin-agent-skills` + `claude -p`, with DeepSeek as a rate-limit fallback). The orchestrator runs on the JVM and is invoked via the Gradle wrapper.
