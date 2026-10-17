#!/usr/bin/env bash
# Copies the calculation fixtures (fixtures/calc) of fin-assist/contracts at a given ref into
# mock/backend/src/test/resources/calc and records the commit in VERSION (FIN-108).
# Usage: scripts/sync-calc-fixtures.sh [ref]   (default: main)
set -euo pipefail

ref="${1:-main}"
repo="https://github.com/fin-assist/contracts"
root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$root/mock/backend/src/test/resources/calc"

sha="$(git ls-remote "$repo" "$ref" | awk 'NR==1 {print $1}')"
if [ -z "$sha" ]; then
  # A commit SHA is not listed by ls-remote: take it as is.
  sha="$ref"
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl -fsSL "$repo/archive/$sha.tar.gz" | tar -xz -C "$tmp"
src="$(find "$tmp" -maxdepth 1 -mindepth 1 -type d)/fixtures/calc"
[ -f "$src/scenario.json" ] || { echo "no fixtures/calc in $repo at $sha" >&2; exit 1; }

rm -rf "$dest"
mkdir -p "$dest"
cp -R "$src/statements" "$src/expected" "$dest/"
cp "$src/scenario.json" "$src/categories.json" "$src/README.md" "$dest/"
echo "$sha" > "$dest/VERSION"
echo "fixtures/calc of fin-assist/contracts at $sha → ${dest#"$root"/}"
