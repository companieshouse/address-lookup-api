#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
bash "$root/scripts/maven.sh" -q -f "$root/scripts/address-seeds/pom.xml" test
work="$(mktemp -d)"
cleanup() {
    result=$?
    if (( result != 0 )) && [[ -f "$work/output" ]]; then cat "$work/output" >&2; fi
    rm -f "$work/scripts/add-postcode.sh" "$work/scripts/os-address-seeds.sh" \
        "$work/scripts/maven.sh" "$work/scripts/address-seeds/target/classpath" \
        "$work/bin/java" "$work/output" "$work/calls"
    rmdir "$work/scripts/address-seeds/target" "$work/scripts/address-seeds" \
        "$work/scripts" "$work/database/local" "$work/database" "$work/bin" "$work"
}
trap cleanup EXIT
mkdir -p "$work/scripts/address-seeds/target" "$work/database/local" "$work/bin"
cp "$root/scripts/add-postcode.sh" "$root/scripts/os-address-seeds.sh" "$work/scripts/"
cat > "$work/scripts/maven.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
printf 'stub\n' > "$root/scripts/address-seeds/target/classpath"
STUB
cat > "$work/bin/java" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$CALLS"
[[ "${FAIL_GENERATOR:-0}" == 0 ]]
STUB
chmod +x "$work/bin/java"
export PATH="$work/bin:$PATH" CALLS="$work/calls" POSTCODE="" POSTCODES="" OS_SOURCE_DIR="$work/source"
for format in single json comma; do
    : > "$CALLS"
    case "$format" in
        single) POSTCODE='kt13 8gl' bash "$work/scripts/add-postcode.sh" ;;
        json) POSTCODES='["KT13 8GL","CF14 3AT"]' bash "$work/scripts/add-postcode.sh" ;;
        comma) POSTCODES=KT138GL,CF143AT bash "$work/scripts/add-postcode.sh" ;;
    esac
    grep -Fq 'KT138GL' "$CALLS"
    [[ ! -d "$work/database/local/.add-postcode.lock" ]]
done
for input in '[]' '["CF143AT",3]' 'bad' 'CF143AT,'; do
    : > "$CALLS"
    if POSTCODES="$input" bash "$work/scripts/add-postcode.sh" > "$work/output" 2>&1; then
        printf 'Expected invalid input failure.\n' >&2; exit 1
    fi
    [[ ! -s "$CALLS" ]]
done
if POSTCODE=CF143AT POSTCODES=KT138GL bash "$work/scripts/add-postcode.sh" > "$work/output" 2>&1; then exit 1; fi
mkdir "$work/database/local/.add-postcode.lock"
if POSTCODE=KT138GL bash "$work/scripts/add-postcode.sh" > "$work/output" 2>&1; then exit 1; fi
grep -Fq 'generation is locked' "$work/output"
rmdir "$work/database/local/.add-postcode.lock"
if FAIL_GENERATOR=1 POSTCODE=KT138GL bash "$work/scripts/add-postcode.sh" > "$work/output" 2>&1; then exit 1; fi
[[ ! -d "$work/database/local/.add-postcode.lock" ]]
printf 'PASS: source-backed postcode generator checks.\n'
