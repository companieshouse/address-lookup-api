#!/usr/bin/env bash
set -euo pipefail

for command_name in curl jq; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        printf 'Required command is unavailable: %s\n' "$command_name" >&2
        exit 1
    fi
done

port="${LOCAL_API_PORT:-8080}"
if [[ ! "$port" =~ ^[0-9]+$ ]] || (( ${#port} > 5 )) ||
    (( 10#$port < 1024 || 10#$port > 65535 )); then
    printf 'LOCAL_API_PORT must be between 1024 and 65535.\n' >&2
    exit 2
fi

work_dir="$(mktemp -d)"
trap 'rm -f "$work_dir/headers" "$work_dir/body"; rmdir "$work_dir"' EXIT

postcodes=(
    DG89JA DG27AZ NE119NU NE311JA CA11AA CA101AT TD12SJ TD11BE
    CH11DE CH11DF SY11EB SY100AU HR11AD HR11AE SY217BL LL137TD NP253EQ
)

for postcode in "${postcodes[@]}"; do
    url="http://localhost:${port}/address-lookup-api/addresses?postcode=${postcode}"
    printf '\nGET %s\n' "$url"
    printf 'curl --silent --show-error --connect-timeout 5 --max-time 30 --include %q\n' "$url"
    : > "$work_dir/headers"
    : > "$work_dir/body"
    status=0
    http_status="$(curl --silent --show-error --connect-timeout 5 --max-time 30 \
        --dump-header "$work_dir/headers" --output "$work_dir/body" \
        --write-out '%{http_code}' "$url")" || status=$?
    printf '\nResponse headers:\n'
    if [[ -f "$work_dir/headers" ]]; then
        cat "$work_dir/headers"
    fi
    printf '\nResponse JSON:\n'
    json_status=0
    if [[ -s "$work_dir/body" ]]; then
        jq . "$work_dir/body" || { json_status=$?; cat "$work_dir/body"; }
    else
        printf '(empty response)\n'
        json_status=1
    fi
    if (( status != 0 )); then
        printf 'FAILED %s: curl exited with status %s.\n' "$postcode" "$status" >&2
        exit "$status"
    fi
    if [[ "$http_status" != 200 ]]; then
        printf 'FAILED %s: HTTP %s, expected 200.\n' "$postcode" "$http_status" >&2
        exit 1
    fi
    if (( json_status != 0 )) || ! jq -e --arg postcode "$postcode" \
        'type == "object" and .postcode == $postcode
        and (.addresses | type == "array" and length > 0)
        and .totalResults == (.addresses | length)' "$work_dir/body" >/dev/null; then
        printf 'FAILED %s: expected valid JSON with matching postcode and nonempty address results.\n' \
            "$postcode" >&2
        exit 1
    fi
    printf 'PASS %s\n' "$postcode"
done

printf '\nAll %s postcode lookups passed.\n' "${#postcodes[@]}"
