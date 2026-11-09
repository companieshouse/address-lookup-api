#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
exec bash "$root/scripts/os-address-seeds.sh" add
