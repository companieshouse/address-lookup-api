#!/usr/bin/env bash
# Downloads every file of an OS data package version into http/downloads/<package>/<version>/,
# skipping files that already exist with the size and MD5 the API reports,
# so a file updated remotely (different size or content) is downloaded again.
# Usage: ./download-os-package.sh [env]   (default env: dev; merges http-client.env.json with http-client.private.env.json, the private file winning)
set -euo pipefail

cd "$(dirname "$0")"
env_name="${1:-dev}"
# Values from http-client.private.env.json override http-client.env.json, as in IntelliJ.
config=$(jq -s --arg env "$env_name" '(.[0][$env] // {}) + (.[1][$env] // {})' http-client.private.env.json)
package_id=$(jq -r '.dataPackageId' <<<"$config")
version_id=$(jq -r '.versionId' <<<"$config")
api_key=$(jq -r '.os_api_key' <<<"$config")
base="https://api.os.uk/downloads/v1/dataPackages/${package_id}/versions/${version_id}"
dest="downloads/${package_id}/${version_id}"
mkdir -p "$dest"

file_md5() {
    if command -v md5sum >/dev/null; then md5sum "$1" | cut -d' ' -f1; else md5 -q "$1"; fi
}

curl -fsS -H "key: ${api_key}" -H "Accept: application/json" "$base" |
    jq -r '.downloads[] | [.fileName, .size, .url, .md5] | @tsv' |
    while IFS=$'\t' read -r name size url md5; do
        target="${dest}/${name}"
        [[ -f "$target" ]] && echo "checking ${name} ..."
        if [[ -f "$target" && "$(wc -c <"$target" | tr -d ' ')" == "$size" && "$(file_md5 "$target")" == "$md5" ]]; then
            echo "skip     ${name} (already downloaded, size and MD5 match)"
            continue
        fi
        echo "download ${name} (${size} bytes)"
        curl -fSL --progress-bar -H "key: ${api_key}" -o "${target}.part" \
            "${url}"
        mv "${target}.part" "$target"
    done
