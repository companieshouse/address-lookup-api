#!/usr/bin/env bash
set -euo pipefail
fail() { printf '%s\n' "$*" >&2; exit 1; }
[[ "$(uname -s)" == Darwin && "$(uname -m)" == arm64 ]] ||
    fail 'install-fly supports Apple Silicon macOS (darwin/arm64) only.'
for tool in curl chmod sudo install mktemp; do
    command -v "$tool" >/dev/null || fail "$tool is required."
done
work="$(mktemp -d)"
cleanup() {
    rm -f "$work/fly"
    rmdir "$work"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
curl --fail --silent --show-error --location --proto '=https' \
    --proto-redir '=https' --connect-timeout 10 --max-time 120 \
    'https://ci-platform.companieshouse.gov.uk/api/v1/cli?arch=arm64&platform=darwin' \
    --output "$work/fly"
chmod +x "$work/fly"
"$work/fly" --version
sudo install "$work/fly" /usr/local/bin/fly
printf 'Installed fly at /usr/local/bin/fly.\n'
