#!/usr/bin/env bash
# FIN-469: a successful transport must also produce the pinned, executable CLI before booting an AVD.
set -euo pipefail

: "${MAESTRO_VERSION:?MAESTRO_VERSION must be pinned}"
: "${MAESTRO_SHA256:?MAESTRO_SHA256 must be pinned}"
: "${RUNNER_TEMP:?RUNNER_TEMP is required}"
: "${GITHUB_PATH:?GITHUB_PATH is required}"
archive="$(mktemp "$RUNNER_TEMP/maestro-download.XXXXXX")"
install_dir="$(mktemp -d "$RUNNER_TEMP/maestro-cli.XXXXXX")"
trap 'rm -f "$archive"' EXIT
# Same versioned release archive as the official installer, with transport failure/retry and process bounds.
curl --fail --silent --show-error --location --connect-timeout 10 --max-time 60 \
    --retry 2 --retry-all-errors --retry-max-time 180 \
    --output "$archive" "https://github.com/mobile-dev-inc/maestro/releases/download/cli-${MAESTRO_VERSION}/maestro.zip"
if [[ ! -s "$archive" ]]; then
    echo 'Maestro archive download was empty' >&2
    exit 1
fi
python3 - "$archive" "$MAESTRO_SHA256" <<'PY'
import hashlib
import sys

digest = hashlib.sha256()
with open(sys.argv[1], 'rb') as archive:
    for chunk in iter(lambda: archive.read(1024 * 1024), b''):
        digest.update(chunk)
if digest.hexdigest() != sys.argv[2]:
    raise SystemExit('Maestro archive SHA256 mismatch')
PY
timeout --kill-after=5s 30s unzip -q "$archive" -d "$install_dir"

cli="$install_dir/maestro/bin/maestro"
if [[ ! -x "$cli" ]]; then
    echo 'Maestro archive did not contain an executable CLI' >&2
    exit 1
fi
version="$(MAESTRO_CLI_NO_ANALYTICS=true MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true \
    timeout --kill-after=5s 30s "$cli" --version)"
if [[ "$version" != "$MAESTRO_VERSION" ]]; then
    echo 'Installed Maestro version does not match MAESTRO_VERSION' >&2
    exit 1
fi
printf '%s\n' "$install_dir/maestro/bin" >> "$GITHUB_PATH"
