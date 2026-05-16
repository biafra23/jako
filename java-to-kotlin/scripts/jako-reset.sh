#!/usr/bin/env bash
# scripts/jako-reset.sh — wipe the artefacts of a previous jako run so you
# can start a fresh one.
#
# What it removes:
#   - the orchestrator state dir (default: java-to-kotlin/state/)
#   - the git worktree at $WORKTREE_PATH (registered + the directory)
#   - the jako/<module> branch in the source repo
#   - any stray "literal ~" directory inside java-to-kotlin/ left over from
#     pre-fix-worktree-tilde-expansion runs
#
# What it does NOT touch:
#   - the source project working tree (it's a worktree-based reset; the
#     source repo is left alone). If a non-worktree run dirtied the source
#     repo, reset it manually: `cd $PROJECT_ROOT && git reset --hard <ref>`.
#
# Defaults match config.yaml (project.root: ~/tuweni-gemma4-pipe,
# project.module: bytes). Override via flags if your config differs.

set -euo pipefail

PROJECT_ROOT="${HOME}/tuweni-gemma4-pipe"
WORKTREE_PATH="${HOME}/tuweni-gemma4-pipe_out"
MODULE="bytes"
JAKO_REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STATE_DIR="${JAKO_REPO_ROOT}/state"

usage() {
  cat <<USAGE
Usage: $0 [--project PATH] [--worktree PATH] [--module NAME] [--state-dir PATH]

Defaults:
  --project    ${HOME}/tuweni-gemma4-pipe
  --worktree   ${HOME}/tuweni-gemma4-pipe_out
  --module     bytes               (used for the jako/<module> branch)
  --state-dir  ${JAKO_REPO_ROOT}/state
USAGE
}

# Each `--FLAG VALUE` shift consumes two tokens. `set -e` + a bare
# `shift 2` on a flag passed without its value gives a confusing "shift:
# can't shift that many" instead of a usage hint.
require_value() {
  local flag="$1" value="${2:-}"
  if [[ -z "$value" || "$value" == --* ]]; then
    echo "error: $flag requires a value" >&2
    usage >&2
    exit 2
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project)   require_value "$1" "${2:-}"; PROJECT_ROOT="$2"; shift 2 ;;
    --worktree)  require_value "$1" "${2:-}"; WORKTREE_PATH="$2"; shift 2 ;;
    --module)    require_value "$1" "${2:-}"; MODULE="$2"; shift 2 ;;
    --state-dir) require_value "$1" "${2:-}"; STATE_DIR="$2"; shift 2 ;;
    -h|--help)   usage; exit 0 ;;
    *)           echo "unknown arg: $1" >&2; usage >&2; exit 2 ;;
  esac
done

BRANCH="jako/${MODULE}"
LITERAL_TILDE="${JAKO_REPO_ROOT}/~"

say() { printf '[jako-reset] %s\n' "$*"; }

# Refuse to rm-rf paths that almost certainly aren't a jako artefact.
# Catches typos that would otherwise wipe a home directory or worse —
# `--worktree ""`, `--worktree /`, `--worktree ~`, `--state-dir .`, etc.
# The whitelist of forbidden targets is small on purpose; the canonical
# check is `realpath`-based so symlinks and trailing slashes don't slip
# through.
# Pure-bash path canonicalisation (no realpath/Python dependency). Walks
# up to the nearest existing parent (so missing tails still resolve), uses
# `cd && pwd -P` to expand symlinks, and re-attaches the missing leaf.
canon_path() {
  local p="$1"
  [[ -z "$p" ]] && { printf ''; return; }
  [[ "$p" != /* ]] && p="$PWD/$p"
  local parent base tail=""
  if [[ -d "$p" ]]; then
    (cd "$p" && pwd -P)
    return
  fi
  parent="${p%/*}"
  base="${p##*/}"
  while [[ -n "$parent" && "$parent" != "/" && ! -d "$parent" ]]; do
    tail="${parent##*/}/${tail:-}"
    parent="${parent%/*}"
  done
  [[ -z "$parent" ]] && parent="/"
  if [[ -d "$parent" ]]; then
    printf '%s/%s%s' "$(cd "$parent" && pwd -P)" "${tail:+$tail}" "$base"
  else
    printf '%s' "$p"
  fi
}

forbid_dangerous_target() {
  local label="$1" raw="$2"
  if [[ -z "$raw" ]]; then
    echo "error: $label is empty — refusing to operate" >&2
    exit 2
  fi
  local canon
  canon="$(canon_path "$raw")"
  case "$canon" in
    "" | "/" | "/Users" | "/home" | "/tmp" | "/var" | "/usr" | "/etc" | "/opt")
      echo "error: $label resolves to system path $canon — refusing" >&2
      exit 2 ;;
  esac
  if [[ "$canon" == "$HOME" ]]; then
    echo "error: $label resolves to \$HOME ($canon) — refusing" >&2
    exit 2
  fi
  printf '%s' "$canon"
}

PROJECT_ROOT="$(forbid_dangerous_target --project   "$PROJECT_ROOT")"
WORKTREE_PATH="$(forbid_dangerous_target --worktree "$WORKTREE_PATH")"
STATE_DIR="$(forbid_dangerous_target --state-dir "$STATE_DIR")"

# 1) orchestrator state
if [[ -d "$STATE_DIR" ]]; then
  say "removing state dir: $STATE_DIR"
  rm -rf "$STATE_DIR"
else
  say "state dir absent: $STATE_DIR (nothing to do)"
fi

# 2) the bogus literal-tilde directory the pre-fix bug created
if [[ -e "$LITERAL_TILDE" ]]; then
  say "removing stray literal-tilde dir: $LITERAL_TILDE"
  rm -rf "$LITERAL_TILDE"
fi

# 3) the git worktree, registered + on disk.
# `git worktree list --porcelain` reports canonicalised paths; comparing
# against an un-canonicalised $WORKTREE_PATH (relative input, trailing
# slash, symlinked parent) would leave the entry registered while we
# happily rm-rf'd the directory. We canonicalised $WORKTREE_PATH above,
# so the grep is a clean equality check.
if [[ -d "$PROJECT_ROOT/.git" || -f "$PROJECT_ROOT/.git" ]]; then
  if git -C "$PROJECT_ROOT" worktree list --porcelain | grep -qxF "worktree $WORKTREE_PATH"; then
    say "removing registered git worktree: $WORKTREE_PATH"
    git -C "$PROJECT_ROOT" worktree remove "$WORKTREE_PATH" --force
  fi
  say "pruning stale worktree refs in $PROJECT_ROOT"
  git -C "$PROJECT_ROOT" worktree prune
  # 4) jako/<module> branch the worktree had checked out
  if git -C "$PROJECT_ROOT" show-ref --quiet "refs/heads/$BRANCH"; then
    say "deleting branch $BRANCH in $PROJECT_ROOT"
    git -C "$PROJECT_ROOT" branch -D "$BRANCH"
  fi
else
  say "project root is not a git repo, skipping worktree cleanup: $PROJECT_ROOT"
fi

# 5) worktree dir if it survived (e.g. never a registered worktree)
if [[ -e "$WORKTREE_PATH" ]]; then
  say "removing leftover worktree dir: $WORKTREE_PATH"
  rm -rf "$WORKTREE_PATH"
fi

say "done — ready for a fresh run."
