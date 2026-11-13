#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
mkdir "$work_dir/home" "$work_dir/cwd"

# The repository check fails (no .git/config), which is the early-failure path.
for mask in 000 022; do
    rm -rf "$work_dir/home/.m2"
    if (umask "$mask"; cd "$work_dir/cwd" &&
        printf 'secret-token\n' | HOME="$work_dir/home" perl "$repo_root/scripts/tests/m2-check.pl" \
        > "$work_dir/output" 2>&1); then
        printf 'Expected the repository check to fail outside the repo.\n' >&2
        exit 1
    fi
    settings="$work_dir/home/.m2/settings.xml"
    grep -Fq 'Bearer secret-token' "$settings"
    mode="$(perl -e 'printf "%04o", (stat shift)[2] & 07777' "$settings")"
    if [[ "$mode" != 0600 ]]; then
        printf 'settings.xml mode is %s with umask %s, expected 0600.\n' "$mode" "$mask" >&2
        exit 1
    fi
done
printf 'm2-check permission checks passed.\n'
