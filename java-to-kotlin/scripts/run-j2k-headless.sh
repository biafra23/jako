#!/usr/bin/env bash
# scripts/run-j2k-headless.sh — invoke the :j2k-plugin headless via Gradle.
#
# Wires the J2KRunner's `external` strategy to the headless IntelliJ J2K
# converter:
#
#   j2k:
#     strategy: external
#     command: scripts/run-j2k-headless.sh
#
# The orchestrator calls: scripts/run-j2k-headless.sh <java_in> <kt_out>
# We re-invoke `./gradlew :j2k-plugin:runIde` which builds the sandbox
# (cached after first run) and launches a headless IntelliJ that loads
# our `jakoConvert` ApplicationStarter.
#
# Per-file cost: ~20s of IDE startup + sub-second conversion. For a
# 26-file cycle that's ~9 min of startup overhead alone — significantly
# longer than passthrough J2K plus refine. A batch mode (one IDE
# invocation, many files) is a planned follow-up.

set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <java_in> <kt_out>" >&2
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
  --args="jakoConvert $1 $2" \
  --console=plain \
  --quiet
