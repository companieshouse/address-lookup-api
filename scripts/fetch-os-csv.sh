#!/usr/bin/env bash
# Downloads the newest FULL (not change-only COU) CSV release of the OS "Address Lookup Service Data v2" package and
# extracts the Royal Mail CSVs into database/local/data (git-ignored) for the local-only
# Flyway CSV seed migration. Needs OS_API_KEY (environment or ../.env). Never prints URLs.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
dest="${LOCAL_SEED_DIR:-$root/database/local/data}"
package_name='Address Lookup Service Data v2'
datasets=(add_gb_royalmailaddress add_isl_royalmailaddress)
api='https://api.os.uk/downloads/v1/dataPackages'

for tool in curl jq unzip; do
    command -v "$tool" >/dev/null || { printf 'Required command unavailable: %s\n' "$tool" >&2; exit 1; }
done

if [[ -z "${OS_API_KEY:-}" && -f "$root/../.env" ]]; then
    OS_API_KEY="$(sed -n 's/^\(export \)\{0,1\}OS_API_KEY=//p' "$root/../.env" | head -1 | tr -d "\"'")"
fi
[[ -n "${OS_API_KEY:-}" ]] || { printf 'OS_API_KEY is required.\n' >&2; exit 1; }

# The key travels as a query parameter; pass the URL on stdin so it never reaches argv.
get() {
    printf 'url = "%s"\n' "$1" | curl --silent --show-error --fail --location --config -
}

packages="$(get "$api?key=$OS_API_KEY")"
package_id="$(jq -r --arg n "$package_name" 'map(select(.name == $n)) | .[0].id // empty' <<<"$packages")"
[[ -n "$package_id" ]] || { printf 'Data package not found: %s\n' "$package_name" >&2; exit 1; }

package="$(get "$api/$package_id?key=$OS_API_KEY")"
version_id="$(jq -r '[.versions[] | select(.format == "CSV" and .supplyType == "FULL")] | sort_by(.createdOn) | reverse | .[0].id // empty' <<<"$package")"
[[ -n "$version_id" ]] || { printf 'No FULL CSV version found for package %s.\n' "$package_id" >&2; exit 1; }
printf 'Newest FULL CSV version: package %s version %s\n' "$package_id" "$version_id"

version="$(get "$api/$package_id/versions/$version_id?key=$OS_API_KEY")"
mkdir -p "$dest"

fetch_dataset() {
    local dataset="$1" marker csv url zip
    marker="$package_id/$version_id"
    csv="$dest/$dataset.csv"
    if [[ -s "$csv" && "$(cat "$csv.version" 2>/dev/null || true)" == "$marker" ]]; then
        printf '%s.csv is already at version %s; skipping.\n' "$dataset" "$marker"
        return 0
    fi
    url="$(jq -r --arg f "$dataset.zip" '.downloads[] | select(.fileName == $f) | .url' <<<"$version")"
    [[ -n "$url" ]] || { printf 'No download listed for %s.zip.\n' "$dataset" >&2; return 1; }
    case "$url" in *key=*) ;; *\?*) url="$url&key=$OS_API_KEY" ;; *) url="$url?key=$OS_API_KEY" ;; esac
    rm -f "$csv.version"
    printf 'Downloading and extracting %s.csv ...\n' "$dataset"
    zip="$csv.zip.part"
    # Each worker is its own process, so an EXIT trap with the paths captured now cleans up on any failure.
    trap "rm -f -- $(printf '%q %q' "$zip" "$csv.part")" EXIT
    trap 'exit 143' TERM INT
    # A ZIP cannot be streamed through tar (GNU tar reads only tar archives) or unzip, so keep it on disk.
    get "$url" >"$zip"
    unzip -p "$zip" "$dataset.csv" >"$csv.part"
    rm -f "$zip"
    [[ -s "$csv.part" ]] || { rm -f "$csv.part"; printf 'Extracted %s.csv is empty.\n' "$dataset" >&2; return 1; }
    mv "$csv.part" "$csv"
    printf '%s' "$marker" >"$csv.version"
}

# Each dataset downloads in its own background job and process group; all must succeed. Stopping the
# script terminates every worker and its curl/unzip children and waits, so none can publish a CSV later.
set -m
pids=()
stop_workers() {
    trap - INT TERM EXIT
    for pid in "${pids[@]}"; do
        kill -TERM -- "-$pid" 2>/dev/null || true
    done
    wait 2>/dev/null || true
    rm -f "$dest"/*.zip.part "$dest"/*.csv.part
}
trap stop_workers EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
for dataset in "${datasets[@]}"; do
    fetch_dataset "$dataset" &
    pids+=("$!")
done
status=0
for pid in "${pids[@]}"; do
    wait "$pid" || status=1
done
(( status == 0 )) || { printf 'Seed CSV download failed.\n' >&2; exit 1; }
printf 'Seed CSVs ready in %s\n' "$dest"
