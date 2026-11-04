#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -f "$work_dir/curl" "$work_dir/calls" "$work_dir/output"; rmdir "$work_dir"' EXIT

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
export PATH="$work_dir:$PATH" CALLS="$work_dir/calls"
LOCAL_API_PORT=8081 bash "$repo_root/scripts/test-postcodes.sh" > "$work_dir/output"
[[ "$(wc -l < "$CALLS" | tr -d ' ')" == 17 ]]
grep -Fq 'GET http://localhost:8081/address-lookup-api/addresses?postcode=DG89JA' "$work_dir/output"
grep -Fq 'curl --silent' "$work_dir/output"
grep -Fq 'Content-Type: application/json' "$work_dir/output"
grep -Fq '"udprn": 19' "$work_dir/output"
grep -Fq 'All 17 postcode lookups passed.' "$work_dir/output"
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
