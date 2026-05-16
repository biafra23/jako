#!/usr/bin/env bash
# scripts/run-j2k-headless.sh — invoke the :j2k-plugin headless via Gradle.
#
# Wires the J2KRunner's `external` / `headless_idea` strategies to the
# headless IntelliJ J2K converter:
#
#   j2k:
#     strategy: headless_idea
#     command: scripts/run-j2k-headless.sh
#
# Two call shapes — the orchestrator picks one per cycle:
#
#   scripts/run-j2k-headless.sh <java_in> <kt_out>
#   scripts/run-j2k-headless.sh --manifest <path>
#
# The single-file form is kept for debugging and one-shots. The manifest
# form is what attemptGroup uses for real runs: ~20s of IDE startup plus
# ~1s per file. A 26-file cycle drops from ~9 min (per-file) to ~1 min.

set -euo pipefail

if [[ $# -eq 2 && "$1" == "--manifest" ]]; then
  ide_args="jakoConvert --manifest $2"
elif [[ $# -eq 2 ]]; then
  ide_args="jakoConvert $1 $2"
else
  echo "usage: $0 <java_in> <kt_out>" >&2
  echo "   or: $0 --manifest <path>" >&2
  exit 2
fi

# scripts/ lives at the orchestrator root next to gradlew.
cd "$(dirname "$0")/.."

if [[ ! -x ./gradlew ]]; then
  echo "run-j2k-headless: ./gradlew not found at $(pwd)" >&2
  exit 1
fi

# `--args` is one string passed verbatim to the IDE's main(). The
# orchestrator gives us absolute paths so we don't need to worry about
# cwd-relative interpretation inside the IDE.
exec ./gradlew :j2k-plugin:runIde \
  --args="$ide_args" \
  --console=plain \
  --quiet
