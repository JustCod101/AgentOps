#!/usr/bin/env bash
set -euo pipefail

TARGET_DIR="${1:-./completions}"
mkdir -p "$TARGET_DIR"

_AGENTOPS_COMPLETE=bash_source agentops > "$TARGET_DIR/agentops.bash"
_AGENTOPS_COMPLETE=zsh_source agentops > "$TARGET_DIR/_agentops"
_AGENTOPS_COMPLETE=fish_source agentops > "$TARGET_DIR/agentops.fish"

printf 'Generated completion scripts in %s\n' "$TARGET_DIR"
