#!/usr/bin/env bash
# Every Flag("...") key declared in core/toggles must be documented in docs/flags.md and vice versa.
set -euo pipefail
cd "$(dirname "$0")/.."

code_keys=$(grep -oE 'Flag\("[a-z0-9_.]+"' core/toggles/src/main/kotlin/ru/finassist/pf/core/toggles/Flags.kt | sed -E 's/Flag\("([^"]+)"/\1/' | sort)
doc_keys=$(grep -oE '^\| `[a-z0-9_.]+`' docs/flags.md | sed -E 's/\| `([^`]+)`/\1/' | sort)

if [ "$code_keys" != "$doc_keys" ]; then
  echo "Flag registry and docs/flags.md differ:"
  diff <(echo "$code_keys") <(echo "$doc_keys") || true
  exit 1
fi
echo "flags: $(echo "$code_keys" | wc -l | tr -d ' ') documented"
