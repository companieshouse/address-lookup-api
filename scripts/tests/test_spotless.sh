#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
mkdir -p "$work_dir/project/scripts" "$work_dir/project/nested path" "$work_dir/bin"
cp "$root/scripts/spotless.sh" "$work_dir/project/scripts/"
cp "$root/pom.xml" "$work_dir/project/"
cat >"$work_dir/bin/mvn" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
pom=""
while (( $# )); do
    if [[ "$1" == -f ]]; then
        pom="$2"
        shift
    fi
    shift
done
[[ -f "$pom" ]] || exit 9
while IFS= read -r -d '' file; do
    sed 's/[[:blank:]]*$//' "$file" >"$file.formatted"
    mv "$file.formatted" "$file"
done < <(find "$(dirname "$pom")" -type f -name '*.java' -print0)
if [[ -n "${EDIT_FILE:-}" ]]; then
    printf 'class SavedDuringFormatting {}\n' >"$EDIT_FILE"
fi
printf 'Formatter log must not appear on successful stdout.\n'
if [[ "${FORMATTER_FAIL:-0}" == 1 ]]; then
    printf 'Simulated formatter failure after snapshot edits.\n' >&2
    exit 7
fi
STUB
chmod +x "$work_dir/bin/mvn"
export PATH="$work_dir/bin:$PATH"
export FILE="" FORMATTER_FAIL=0
export EDIT_FILE=""
cd "$work_dir/project"

pass=0
check() {
    if ! "$@"; then
        printf 'FAIL: %s\n' "$*" >&2
        cat "$work_dir/stdout" "$work_dir/stderr" >&2
        exit 1
    fi
    pass=$((pass + 1))
}
run() {
    local expected="$1" status=0
    shift
    bash scripts/spotless.sh "$@" >"$work_dir/stdout" 2>"$work_dir/stderr" || status=$?
    check test "$status" -eq "$expected"
}
same() {
    [[ "$(cat "$1"; printf .)" == "$(cat "$2"; printf .)" ]]
}

printf 'class One {}  \n' >"nested path/One.java"
printf 'class Two {}  \n' >Two.java
cp "nested path/One.java" "$work_dir/one-original"
cp Two.java "$work_dir/two-original"
printf 'class One {}\n' >"$work_dir/one-clean"
printf 'class Two {}\n' >"$work_dir/two-clean"

FILE="nested path/One.java" run 0 tidy
check same "nested path/One.java" "$work_dir/one-clean"
check same Two.java "$work_dir/two-original"
check test ! -s "$work_dir/stdout"
check test ! -s "$work_dir/stderr"

run 0 tidy <"$work_dir/one-original"
check same "$work_dir/stdout" "$work_dir/one-clean"
check test ! -s "$work_dir/stderr"
check same Two.java "$work_dir/two-original"

run 255 check
printf 'Two.java\n' >"$work_dir/expected-paths"
check same "$work_dir/stdout" "$work_dir/expected-paths"
check test ! -s "$work_dir/stderr"
check same Two.java "$work_dir/two-original"

mkdir -p module/target/generated-sources
printf 'class Generated {}  \n' >module/target/generated-sources/Generated.java
cp module/target/generated-sources/Generated.java "$work_dir/generated-original"
FILE=. run 0 tidy
check same Two.java "$work_dir/two-clean"
check same "nested path/One.java" "$work_dir/one-clean"
check same module/target/generated-sources/Generated.java "$work_dir/generated-original"
run 0 check
check test ! -s "$work_dir/stdout"
check test ! -s "$work_dir/stderr"

cp "$work_dir/one-original" "nested path/One.java"
cp "$work_dir/two-original" Two.java
for mode in single tree stream check; do
    case "$mode" in
        single) FILE="nested path/One.java" FORMATTER_FAIL=1 run 1 tidy ;;
        tree) FILE=. FORMATTER_FAIL=1 run 1 tidy ;;
        stream) FORMATTER_FAIL=1 run 1 tidy <"$work_dir/one-original" ;;
        check) FORMATTER_FAIL=1 run 1 check ;;
    esac
    check test ! -s "$work_dir/stdout"
    check grep -q 'Simulated formatter failure' "$work_dir/stderr"
    check grep -q 'no source files were changed' "$work_dir/stderr"
    check same "nested path/One.java" "$work_dir/one-original"
    check same Two.java "$work_dir/two-original"
done

printf 'class SavedDuringFormatting {}\n' >"$work_dir/intervening-edit"
FILE="nested path/One.java" EDIT_FILE="$PWD/nested path/One.java" run 1 tidy
check same "nested path/One.java" "$work_dir/intervening-edit"
check same Two.java "$work_dir/two-original"
check grep -q 'Source changed during formatting' "$work_dir/stderr"
check test ! -s "$work_dir/stdout"

cp "$work_dir/one-original" "nested path/One.java"
FILE=. EDIT_FILE="$PWD/Two.java" run 1 tidy
check same Two.java "$work_dir/intervening-edit"
check same "nested path/One.java" "$work_dir/one-original"
check grep -q 'no source files were written back' "$work_dir/stderr"
check test ! -s "$work_dir/stdout"

printf 'PASS: %s standalone Spotless helper checks\n' "$pass"
