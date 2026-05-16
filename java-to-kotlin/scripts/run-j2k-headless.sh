#!/usr/bin/env bash
# scripts/run-j2k-headless.sh — invoke the :j2k-plugin headless via Gradle.
#
# Wires the J2KRunner's `headless_idea` strategy to the headless IntelliJ
# J2K converter:
#
#   j2k:
#     strategy: headless_idea
#     command: scripts/run-j2k-headless.sh
#
# Supported call shapes:
#
#   scripts/run-j2k-headless.sh <java_in> <kt_out>
#   scripts/run-j2k-headless.sh --manifest <path>
#   scripts/run-j2k-headless.sh --project <root> <java_in> <kt_out>
#   scripts/run-j2k-headless.sh --project <root> --manifest <path>
#
# The orchestrator passes `--project <root>` whenever it has a real Gradle
# project to point the IDE at — that gives J2K the full classpath context
# (proper type resolution, smart casts, idiomatic interop). Without it
# the plugin falls back to `defaultProject` (fast but type-resolution-blind).
#
# All args are forwarded verbatim to the plugin's `jakoConvert` starter
# via Gradle's `--args` — this script doesn't parse them.

set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "usage: $0 [--project <root>] <java_in> <kt_out>" >&2
  echo "   or: $0 [--project <root>] --manifest <path>" >&2
  exit 2
fi

cd "$(dirname "$0")/.."

if [[ ! -x ./gradlew ]]; then
  echo "run-j2k-headless: ./gradlew not found at $(pwd)" >&2
  exit 1
fi

# `--args` is a single string that Gradle splits with shell-style quoting,
# so each arg needs explicit quoting to survive a path / value containing
# spaces (e.g. `~/Source Files/jako`). printf %s/%q is overkill for our
# use case (paths, no embedded double quotes); plain double-quotes work.
ide_args="jakoConvert"
for arg in "$@"; do
  ide_args=$(printf '%s "%s"' "$ide_args" "$arg")
done

exec ./gradlew :j2k-plugin:runIde \
  --args="$ide_args" \
  --console=plain \
  --quiet
