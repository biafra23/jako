#!/usr/bin/env bash
# Pass-through "J2K" — copies the .java source verbatim into the .kt target.
#
# We use this when there's no real headless J2K available. The Kotlin file
# starts as syntactically-invalid (it's still Java) but that doesn't matter:
# the next step (claude -p with the JetBrains kotlin-tooling-java-to-kotlin
# skill) reads the file, does the full Java -> Kotlin conversion, and
# overwrites it. The orchestrator only checks that a non-empty file appeared.
#
# Wire via config.yaml:
#   j2k:
#     strategy: external
#     command: scripts/passthrough-j2k.sh
#
# Usage: passthrough-j2k.sh <java_in> <kt_out>

set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <java_in> <kt_out>" >&2
  exit 2
fi

java_in="$1"
kt_out="$2"

if [[ ! -f "$java_in" ]]; then
  echo "passthrough-j2k: no such file: $java_in" >&2
  exit 1
fi

mkdir -p "$(dirname "$kt_out")"
cp -- "$java_in" "$kt_out"
