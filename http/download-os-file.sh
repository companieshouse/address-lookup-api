#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 || ! $1 =~ ^[0-9]+$ || ! $2 =~ ^[0-9]+$ ||
      ! $3 =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ || $3 == *..* ]]; then
  printf 'Usage: %s <package-id> <version-id> <filename>\n' "$0" >&2
  exit 1
fi

http_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
destination="$http_dir/downloads/$1/$2/$3"
if [[ -e $destination ]]; then
  printf 'Download already exists: %s\n' "$destination" >&2
  exit 1
fi

key=$(jq -er '.dev.os_api_key | select(type == "string" and test("^[A-Za-z0-9_-]+$"))' \
  "$http_dir/http-client.private.env.json")

url="https://api.os.uk/downloads/v1/dataPackages/$1/versions/$2/downloads?fileName=$3"
signed_url=$(curl -fsS --max-time 60 -D - -o /dev/null \
  --config <(printf 'header = "key: %s"\n' "$key") "$url" |
  awk 'tolower($1) == "location:" {sub(/\r$/, "", $2); print $2}')
unset key

case "$signed_url" in
  https://*.blob.core.windows.net/*) ;;
  *) printf 'OS did not return an expected HTTPS download redirect\n' >&2; exit 1 ;;
esac

mkdir -p "$(dirname "$destination")"
curl -fL --proto '=https' --proto-redir '=https' --progress-bar \
  --continue-at - --output "$destination.part" \
  --config <(printf 'url = "%s"\n' "$signed_url")
unset signed_url
mv "$destination.part" "$destination"
printf 'Saved %s\n' "$destination"
