#!/usr/bin/env bash
# Copies the files of fin-assist/contracts the fake backend is tested against, all at one ref, and records the
# commit in VERSION next to each copy:
#   - fixtures/calc → mock/backend/src/test/resources/calc (calculation fixtures, FIN-108);
#   - openapi/openapi.yaml and docs/api.md → mock/backend/src/test/resources/contracts (contract tests, FIN-123).
# Usage: scripts/sync-contracts.sh [ref]   (default: main)
set -euo pipefail

ref="${1:-main}"
repo="https://github.com/fin-assist/contracts"
root="$(cd "$(dirname "$0")/.." && pwd)"
calc_dest="$root/mock/backend/src/test/resources/calc"
contract_dest="$root/mock/backend/src/test/resources/contracts"

sha="$(git ls-remote "$repo" "$ref" | awk 'NR==1 {print $1}')"
if [ -z "$sha" ]; then
  # A commit SHA is not listed by ls-remote: take it as is.
  sha="$ref"
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl -fsSL "$repo/archive/$sha.tar.gz" | tar -xz -C "$tmp"
src_root="$(find "$tmp" -maxdepth 1 -mindepth 1 -type d)"
src="$src_root/fixtures/calc"
[ -f "$src/scenario.json" ] || { echo "no fixtures/calc in $repo at $sha" >&2; exit 1; }
[ -f "$src_root/openapi/openapi.yaml" ] || { echo "no openapi/openapi.yaml in $repo at $sha" >&2; exit 1; }
[ -f "$src_root/docs/api.md" ] || { echo "no docs/api.md in $repo at $sha" >&2; exit 1; }

rm -rf "$calc_dest" "$contract_dest"
mkdir -p "$calc_dest" "$contract_dest"
cp -R "$src/statements" "$src/expected" "$calc_dest/"
cp "$src/scenario.json" "$src/categories.json" "$src/README.md" "$calc_dest/"
echo "$sha" > "$calc_dest/VERSION"
cp "$src_root/openapi/openapi.yaml" "$src_root/docs/api.md" "$contract_dest/"
echo "$sha" > "$contract_dest/VERSION"
if ignored="$(git -C "$root" check-ignore "$calc_dest"/statements/* "$calc_dest"/expected/* "$contract_dest"/* 2>/dev/null)"; then
  echo "ignored by .gitignore, would not be committed:" >&2
  echo "$ignored" >&2
  exit 1
fi
echo "fin-assist/contracts at $sha → ${calc_dest#"$root"/}, ${contract_dest#"$root"/}"
