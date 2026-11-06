#!/usr/bin/env bash
set -euo pipefail

mode="${1:-all}"
case "$mode" in
    all) ;;
    single|validate-single)
        if ! command -v jq >/dev/null 2>&1; then
            printf 'Required command is unavailable: jq\n' >&2
            exit 2
        fi
        if [[ -n "${POSTCODE:-}" && -n "${POSTCODES:-}" ]]; then
            printf 'Use POSTCODE or POSTCODES, not both.\n' >&2
            exit 2
        fi
        if [[ -n "${POSTCODES:-}" ]]; then
            input="$POSTCODES"
            if [[ "$input" != *'['* ]]; then
                input="$(jq -cn --arg postcodes "$input" '$postcodes | split(",")')"
            fi
        else
            input="$(jq -cn --arg postcodes "${POSTCODE:-}" '$postcodes | split(",")')"
        fi
        if ! normalized="$(printf '%s' "$input" | jq -ers '
            if length != 1 then error("Expected one JSON array") else .[0] end
            | if type != "array" or length == 0 then
                error("Expected a nonempty postcode array")
              else . end
            | map(if type != "string" then error("Postcodes must be strings")
                  else ascii_upcase | gsub(" "; "") end)
            | if all(.[]; test("^[A-Z]{1,2}[0-9][A-Z0-9]?[0-9][A-Z]{2}$"))
              then .[] else error("Invalid UK postcode") end
        ')"; then
            printf 'Use POSTCODE=CF143AT,CF146RP or POSTCODES='\''["CF143AT","CF146RP"]'\''.\n' >&2
            exit 2
        fi
        selected_postcodes=()
        while IFS= read -r postcode; do
            selected_postcodes+=("$postcode")
        done <<< "$normalized"
        [[ "$mode" != validate-single ]] || exit 0
        ;;
    *) printf 'Usage: test-postcodes.sh [all|single|validate-single]\n' >&2; exit 2 ;;
esac

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

if [[ "$mode" == single ]]; then
    postcodes=("${selected_postcodes[@]}")
else
    if ! command -v docker >/dev/null 2>&1; then
        printf 'Required command is unavailable: docker\n' >&2
        exit 1
    fi
    root="$(cd "$(dirname "$0")/.." && pwd)"
    if ! database_postcodes="$(docker compose -f "$root/compose.yaml" exec -T postgres \
        psql -X -A -t -v ON_ERROR_STOP=1 -U postgres -d address_lookup \
        -c "SELECT DISTINCT upper(replace(postcode, ' ', '')) AS postcode
            FROM address_lookup.address_lookup ORDER BY postcode;")"; then
        printf 'Failed to read postcodes from the local lookup database.\n' >&2
        exit 1
    fi
    postcodes=()
    while IFS= read -r postcode; do
        [[ -n "$postcode" ]] || continue
        if [[ ! "$postcode" =~ ^[A-Z0-9]+$ ]]; then
            printf 'Invalid postcode returned by database: %s\n' "$postcode" >&2
            exit 1
        fi
        postcodes+=("$postcode")
    done <<< "$database_postcodes"
    if (( ${#postcodes[@]} == 0 )); then
        printf 'No postcodes found in the local lookup database.\n' >&2
        exit 1
    fi
fi

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
