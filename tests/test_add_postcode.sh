#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
cleanup() {
    find "$work/database/local" -maxdepth 1 -type f -name 'R__*.sql' -delete
    rm -f "$work/scripts/add-postcode.sh" "$work/bin/curl" "$work/expected" "$work/output"
    rmdir "$work/scripts" "$work/database/local" "$work/database" "$work/bin" "$work"
}
trap cleanup EXIT
mkdir -p "$work/scripts" "$work/database/local" "$work/bin"
cp "$root/scripts/add-postcode.sh" "$work/scripts/"
seed="$work/database/local/R__seed_synthetic_cardiff_addresses.sql"
cat > "$work/bin/curl" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
while (( $# )); do
    case "$1" in
        --output) output="$2"; shift 2 ;;
        *) url="$1"; shift ;;
    esac
done
if [[ "$url" == *CF146RP ]]; then printf 503; exit 0; fi
if [[ "$url" == *CF101PX ]]; then
    if [[ "$url" == */postcodes/* ]]; then printf 404; exit 0; fi
    printf '{"result":{"eastings":318138,"northings":176452,"latitude":51.481077,"longitude":-3.180247}}' > "$output"
    printf 200
    exit 0
fi
if [[ "${INVALID_COORDINATES:-0}" == 1 ]]; then
    printf '{"result":{"eastings":9999999,"northings":163647,"latitude":151,"longitude":0,"country":"England","admin_district":"Elmbridge"}}' > "$output"
    printf 200
    exit 0
fi
printf '{"result":{"eastings":507249,"northings":163647,"latitude":51.361775,"longitude":-0.460936,"country":"England","admin_district":"Elmbridge"}}' > "$output"
printf 200
STUB
chmod +x "$work/bin/curl"
export PATH="$work/bin:$PATH" POSTCODE="" POSTCODES=""
for format in single json comma; do
    find "$work/database/local" -maxdepth 1 -type f -name 'R__seed_KT*.sql' -delete
    printf "SELECT 'CF14 3AT';\n" > "$seed"
    case "$format" in
        single) POSTCODE='kt13 8gl' bash "$work/scripts/add-postcode.sh" ;;
        json) POSTCODES='["CF143AT","KT138GL"]' bash "$work/scripts/add-postcode.sh" ;;
        comma) POSTCODES=CF143AT,KT138GL bash "$work/scripts/add-postcode.sh" ;;
    esac
    grep -Fq "SELECT 'CF14 3AT';" "$seed"
    generated="$work/database/local/R__seed_KT138GL.sql"
    grep -Fq 'ST_MakePoint(507249, 163647)' "$generated"
    grep -Fq "DATE '$(date -u +%F)'" "$generated"
    cp "$generated" "$work/expected"
    POSTCODE=KT138GL bash "$work/scripts/add-postcode.sh"
    cmp "$generated" "$work/expected"
done
rm "$generated"
printf "SELECT 'CF14 3AT';\n" > "$seed"
cp "$seed" "$work/expected"
if POSTCODES=KT138GL,CF146RP bash "$work/scripts/add-postcode.sh" > "$work/output" 2>&1; then
    printf 'Expected lookup failure.\n' >&2; exit 1
fi
cmp "$seed" "$work/expected"
[[ ! -f "$work/database/local/R__seed_CF146RP,KT138GL.sql" ]]
for input in '[]' '["CF143AT",3]' 'bad' 'CF143AT,'; do
    if POSTCODES="$input" bash "$work/scripts/add-postcode.sh" > "$work/output" 2>&1; then
        printf 'Expected invalid input to fail.\n' >&2; exit 1
    fi
    cmp "$seed" "$work/expected"
done
POSTCODES='["KT138GL","KT128GL"]' bash "$work/scripts/add-postcode.sh"
group="$work/database/local/R__seed_KT128GL,KT138GL.sql"
[[ -f "$group" ]]
grep -Fq "release_id = 'local-generated-KT128GL,KT138GL'" "$group"
[[ "$(grep -c '^INSERT INTO' "$group")" == 2 ]]
[[ "$(grep '^    -[0-9]*,' "$group" | cut -d, -f1 | sort -u | wc -l | tr -d ' ')" == 2 ]]
POSTCODE=KT138GL bash "$work/scripts/add-postcode.sh"
[[ ! -f "$work/database/local/R__seed_KT138GL.sql" ]]
POSTCODE=CF101PX bash "$work/scripts/add-postcode.sh" > "$work/output"
grep -Fq 'Historical postcode: CF101PX' "$work/output"
grep -Fq "'SYNTHETIC LOCALITY'" "$work/database/local/R__seed_CF101PX.sql"
grep -Fq "NULL, '1A'" "$work/database/local/R__seed_CF101PX.sql"
if INVALID_COORDINATES=1 POSTCODE=AB101AU bash "$work/scripts/add-postcode.sh" \
    > "$work/output" 2>&1; then
    printf 'Expected invalid coordinates to fail.\n' >&2; exit 1
fi
[[ ! -f "$work/database/local/R__seed_AB101AU.sql" ]]
printf 'PASS: Bash postcode generator checks.\n'
