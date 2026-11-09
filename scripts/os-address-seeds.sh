#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
action="${1:-}"
case "$action" in
    add|refresh|download) ;;
    *) printf 'Expected add, refresh or download.\n' >&2; exit 2 ;;
esac
for tool in jq java mvn curl; do
    command -v "$tool" >/dev/null || { printf '%s is required.\n' "$tool" >&2; exit 1; }
done
postcodes=()
if [[ "$action" == add ]]; then
    [[ -z "${POSTCODE:-}" || -z "${POSTCODES:-}" ]] ||
        { printf 'Use POSTCODE or POSTCODES, not both.\n' >&2; exit 2; }
    input="${POSTCODES:-${POSTCODE:-}}"
    if [[ "$input" != *'['* ]]; then
        input="$(jq -cn --arg value "$input" '$value | split(",")')"
    fi
    normalized="$(jq -ers '
        if length != 1 then error("Expected one array") else .[0] end
        | if type != "array" or length == 0 then error("Expected nonempty array") else . end
        | map(if type != "string" then error("Expected postcode strings")
              else ascii_upcase | gsub(" "; "") end)
        | if all(.[]; test("^[A-Z]{1,2}[0-9][A-Z0-9]?[0-9][A-Z]{2}$"))
          then unique[] else error("Invalid UK postcode") end' <<<"$input")"
    while IFS= read -r postcode; do postcodes+=("$postcode"); done <<<"$normalized"
fi
seed_lock="$root/database/local/.add-postcode.lock"
mkdir "$seed_lock" 2>/dev/null ||
    { printf 'Postcode generation is locked; inspect %s before removing a stale lock.\n' "$seed_lock" >&2; exit 1; }
cleanup() { rmdir "$seed_lock"; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
tools="$root/scripts/address-seeds"
bash "$root/scripts/maven.sh" -q -f "$tools/pom.xml" compile dependency:build-classpath \
    "-Dmdep.outputFile=$tools/target/classpath" >&2
classpath="$tools/target/classes:$(cat "$tools/target/classpath")"
run() { java -Xmx512m -cp "$classpath" uk.gov.companieshouse.addresslookup.tools.AddressSeeds "$@"; }
if [[ "$action" == download || -z "${OS_SOURCE_DIR:-}" ]]; then
    source="$(run download "${OS_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/address-lookup-api/os}")"
else
    source="$OS_SOURCE_DIR"
fi
if [[ "$action" == download ]]; then
    printf 'Verified full OS source directory: %s\n' "$source"
else
    run "$action" "$source" "$root/database/local" \
        "$root/release-model/src/main/resources/catalog.json" "${postcodes[@]+"${postcodes[@]}"}"
fi
