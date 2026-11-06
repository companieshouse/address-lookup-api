#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -f "$work_dir/curl" "$work_dir/docker" "$work_dir/calls" "$work_dir/output" "$work_dir/Makefile" "$work_dir/scripts/local-services.sh" "$work_dir/scripts/test-postcodes.sh"; rmdir "$work_dir/scripts" "$work_dir"' EXIT
mkdir "$work_dir/scripts"
cp "$repo_root/Makefile" "$work_dir/Makefile"
cp "$repo_root/scripts/test-postcodes.sh" "$work_dir/scripts/"
cat > "$work_dir/scripts/local-services.sh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$1" >> "$CALLS"
if [[ "${FAIL_START:-}" == "$1" ]]; then exit 7; fi
STUB

cat > "$work_dir/curl" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
while (( $# )); do
    case "$1" in
        --dump-header) headers="$2"; shift 2 ;;
        --output) body="$2"; shift 2 ;;
        --connect-timeout|--max-time|--write-out) shift 2 ;;
        --silent|--show-error) shift ;;
        *) url="$1"; shift ;;
    esac
done
postcode="${url##*=}"
printf '%s\n' "$url" >> "$CALLS"
printf 'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n' > "$headers"
printf '{"postcode":"%s","totalResults":1,"addresses":[{"udprn":19}]}\n' "$postcode" > "$body"
if [[ "$postcode" == NE119NU ]]; then
    case "${FAILURE:-}" in
        http) printf '503'; exit 0 ;;
        transport) exit 7 ;;
        json) printf 'not json' > "$body" ;;
        empty) printf '{"postcode":"NE119NU","totalResults":0,"addresses":[]}' > "$body" ;;
        mismatch) printf '{"postcode":"OTHER","totalResults":1,"addresses":[{}]}' > "$body" ;;
    esac
fi
printf '200'
STUB
chmod +x "$work_dir/curl"
cat > "$work_dir/docker" <<'STUB'
#!/usr/bin/env bash
[[ "${FAIL_DB:-0}" == 0 ]] || exit 7
[[ "${EMPTY_DB:-0}" == 0 ]] || exit 0
printf 'DG89JA\nDG27AZ\nNE119NU\nCF143AT\nKT138GL\nAB123CD\n'
STUB
chmod +x "$work_dir/docker"
export PATH="$work_dir:$PATH" CALLS="$work_dir/calls"
export POSTCODE="" POSTCODES=""
for target in test-postcode test-postcodes; do
    : > "$CALLS"
    make --no-print-directory -C "$work_dir" "$target" POSTCODE=CF143AT > "$work_dir/output"
    [[ "$(sed -n '1p' "$CALLS")" == db-start ]]
    [[ "$(sed -n '2p' "$CALLS")" == api-restart ]]
    grep -Fq 'http://localhost:' "$CALLS"
    : > "$CALLS"
    if FAIL_START=api-restart make --no-print-directory -C "$work_dir" "$target" \
        POSTCODE=CF143AT > "$work_dir/output" 2>&1; then
        printf 'Expected restart failure to prevent lookups.\n' >&2
        exit 1
    fi
    [[ "$(wc -l < "$CALLS" | tr -d ' ')" == 2 ]]
done
: > "$CALLS"
LOCAL_API_PORT=8081 bash "$repo_root/scripts/test-postcodes.sh" > "$work_dir/output"
[[ "$(wc -l < "$CALLS" | tr -d ' ')" == 6 ]]
grep -Fq 'GET http://localhost:8081/address-lookup-api/addresses?postcode=DG89JA' "$work_dir/output"
grep -Fq 'curl --silent' "$work_dir/output"
grep -Fq 'Content-Type: application/json' "$work_dir/output"
grep -Fq '"udprn": 19' "$work_dir/output"
grep -Fq 'All 6 postcode lookups passed.' "$work_dir/output"
for postcode in CF143AT KT138GL AB123CD; do
    grep -Fq "postcode=$postcode" "$CALLS"
done
for error in FAIL_DB EMPTY_DB; do
    : > "$CALLS"
    if env "$error=1" bash "$repo_root/scripts/test-postcodes.sh" > "$work_dir/output" 2>&1; then
        printf 'Expected database failure or empty database to fail.\n' >&2
        exit 1
    fi
    [[ ! -s "$CALLS" ]]
done
: > "$CALLS"
POSTCODE='cf14 3at' bash "$repo_root/scripts/test-postcodes.sh" single > "$work_dir/output"
[[ "$(wc -l < "$CALLS" | tr -d ' ')" == 1 ]]
grep -Fq 'postcode=CF143AT' "$CALLS"
grep -Fq 'All 1 postcode lookups passed.' "$work_dir/output"
for format in comma json plural-comma; do
    : > "$CALLS"
    if [[ "$format" == comma ]]; then
        POSTCODE='cf14 3at, CF14 6RP,CF23 9XN' \
            bash "$repo_root/scripts/test-postcodes.sh" single > "$work_dir/output"
    elif [[ "$format" == json ]]; then
        POSTCODES='["cf14 3at", "CF14 6RP", "CF23 9XN"]' \
            bash "$repo_root/scripts/test-postcodes.sh" single > "$work_dir/output"
    else
        POSTCODES='cf14 3at, CF14 6RP,CF23 9XN' \
            bash "$repo_root/scripts/test-postcodes.sh" single > "$work_dir/output"
    fi
    [[ "$(wc -l < "$CALLS" | tr -d ' ')" == 3 ]]
    grep -Fq 'postcode=CF143AT' "$CALLS"
    grep -Fq 'postcode=CF146RP' "$CALLS"
    grep -Fq 'postcode=CF239XN' "$CALLS"
done
for json in '[]' '{}' '["CF143AT",2]' '["CF143AT","bad"]' 'not-json' \
    '["CF143AT"] ["CF146RP"]'; do
    : > "$CALLS"
    if POSTCODES="$json" bash "$repo_root/scripts/test-postcodes.sh" single \
        > "$work_dir/output" 2>&1; then
        printf 'Expected invalid postcode array to fail.\n' >&2
        exit 1
    fi
    [[ ! -s "$CALLS" ]]
done
if POSTCODE=CF143AT POSTCODES='["CF146RP"]' \
    bash "$repo_root/scripts/test-postcodes.sh" validate-single > "$work_dir/output" 2>&1; then
    printf 'Expected conflicting postcode inputs to fail.\n' >&2
    exit 1
fi
for postcode in '' 'CF143AT&other=value' 'CF143AT,' ',CF143AT'; do
    if POSTCODE="$postcode" bash "$repo_root/scripts/test-postcodes.sh" validate-single \
        > "$work_dir/output" 2>&1; then
        printf 'Expected invalid postcode to fail.\n' >&2
        exit 1
    fi
done
for failure in http transport json empty mismatch; do
    : > "$CALLS"
    if FAILURE="$failure" bash "$repo_root/scripts/test-postcodes.sh" > "$work_dir/output" 2>&1; then
        printf 'Expected failure for %s.\n' "$failure" >&2
        exit 1
    fi
    [[ "$(wc -l < "$CALLS" | tr -d ' ')" == 3 ]]
    grep -Fq 'FAILED NE119NU' "$work_dir/output"
done
printf 'Postcode helper checks passed.\n'
