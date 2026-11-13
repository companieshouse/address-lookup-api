#!/usr/bin/env bash
# Offline tests of scripts/fetch-os-csv.sh with a stub curl that serves canned OS API responses.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
mkdir -p "$temp/bin" "$temp/zips" "$temp/data"
export STUB_LOG="$temp/calls.log" STUB_PIDS="$temp/curl.pids" STUB_DIR="$temp/zips"
export OS_API_KEY=fixture-key-not-a-credential LOCAL_SEED_DIR="$temp/data"
: >"$STUB_LOG"

for dataset in add_gb_royalmailaddress add_isl_royalmailaddress; do
    printf 'udprn\n1\n' >"$temp/$dataset.csv"
    (cd "$temp" && zip -q "zips/$dataset.zip" "$dataset.csv")
done

cat >"$temp/bin/curl" <<'STUB'
#!/usr/bin/env bash
url="$(sed -n 's/^url = "\(.*\)"$/\1/p')"
printf '%s\n' "${url%%\?*}" >>"$STUB_LOG"
case "$url" in
    *dataPackages\?key=*)
        printf '[{"name":"Address Lookup Service Data v2","id":"P1"}]' ;;
    *dataPackages/P1\?key=*)
        if [[ "${NO_FULL:-0}" == 1 ]]; then
            printf '{"versions":[{"id":"cou","format":"CSV","supplyType":"COU","createdOn":"2026-04-01"}]}'
        else
            printf '{"versions":['
            printf '{"id":"old","format":"CSV","supplyType":"FULL","createdOn":"2026-01-01"},'
            printf '{"id":"newest-full","format":"CSV","supplyType":"FULL","createdOn":"2026-03-01"},'
            printf '{"id":"gpkg","format":"GeoPackage","supplyType":"FULL","createdOn":"2026-05-01"},'
            printf '{"id":"cou","format":"CSV","supplyType":"COU","createdOn":"2026-04-01"}]}'
        fi ;;
    *versions/*)
        version="${url##*/versions/}"
        version="${version%%\?*}"
        printf '{"downloads":['
        printf '{"fileName":"add_gb_royalmailaddress.zip","url":"https://dl.example/%s/add_gb_royalmailaddress.zip"},' "$version"
        printf '{"fileName":"add_isl_royalmailaddress.zip","url":"https://dl.example/%s/add_isl_royalmailaddress.zip"}]}' "$version" ;;
    https://dl.example/*)
        if [[ "${HANG:-0}" == 1 ]]; then
            printf '%s\n' "$$" >>"$STUB_PIDS"
            head -c 10 /dev/zero >&1
            exec sleep 30
        fi
        file="${url##*/}"
        cat "$STUB_DIR/${file%%\?*}" ;;
esac
STUB
chmod +x "$temp/bin/curl"
export PATH="$temp/bin:$PATH"

pass=0
check() {
    if "$@"; then
        pass=$((pass + 1))
    else
        printf 'FAIL: %s\n--- output ---\n' "$*" >&2
        cat "$temp/out" "$STUB_LOG" >&2 2>/dev/null || true
        exit 1
    fi
}

# The newest FULL CSV is used, never the newer COU or non-CSV releases.
bash "$root/scripts/fetch-os-csv.sh" >"$temp/out" 2>&1
check grep -q 'version newest-full' "$temp/out"
check grep -q 'dl.example/newest-full/add_gb_royalmailaddress.zip' "$STUB_LOG"
check grep -q 'dl.example/newest-full/add_isl_royalmailaddress.zip' "$STUB_LOG"
check test "$(grep -c 'dl.example/' "$STUB_LOG")" == 2
check test -s "$temp/data/add_gb_royalmailaddress.csv"
check test -s "$temp/data/add_isl_royalmailaddress.csv"
check grep -qx 'P1/newest-full' <(cat "$temp/data/add_gb_royalmailaddress.csv.version"; echo)
check test -z "$(ls "$temp"/data/*.part 2>/dev/null)"

# Without any FULL release it fails before downloading a dataset.
rm -f "$temp"/data/* && : >"$STUB_LOG"
check env NO_FULL=1 bash -c "! bash '$root/scripts/fetch-os-csv.sh' >'$temp/out' 2>&1"
check grep -q 'No FULL CSV version' "$temp/out"
check test "$(grep -c 'dl.example/' "$STUB_LOG")" == 0
check test -z "$(ls -A "$temp/data")"

# Stopping the script terminates the workers and their transfers and leaves no temporary files.
HANG=1 bash "$root/scripts/fetch-os-csv.sh" >"$temp/out" 2>&1 &
script_pid=$!
for _ in $(seq 1 50); do
    [[ -s "$STUB_PIDS" && "$(wc -l <"$STUB_PIDS")" -ge 2 ]] && break
    sleep 0.1
done
kill -TERM "$script_pid"
wait "$script_pid" || true
sleep 0.3
check test "$(wc -l <"$STUB_PIDS")" -ge 2
for curl_pid in $(cat "$STUB_PIDS"); do
    check bash -c "! kill -0 $curl_pid 2>/dev/null"
done
check test -z "$(ls -A "$temp/data")"

printf 'PASS: %d fetch-os-csv offline checks\n' "$pass"
