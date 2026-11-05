#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -f "$work_dir/mvn" "$work_dir/calls" "$work_dir/output"; rmdir "$work_dir"' EXIT
export CALL_LOG="$work_dir/calls"
cat > "$work_dir/mvn" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$CALL_LOG"
if [[ "$*" == --version ]]; then
    printf 'Java version: %s.0.1, vendor: test\n' "${TEST_JAVA:-21}"
elif [[ "$*" == *-Dartifact=org.jacoco:jacoco-maven-plugin:RELEASE* ]]; then
    if [[ "${RESOLUTION_FAIL:-0}" == 1 ]]; then
        printf 'Repository unavailable.\n' >&2
        exit 1
    fi
    for argument in "$@"; do
        if [[ "$argument" == -Doutput=* ]]; then
            printf '%s' "${RELEASE_VERSION:-0.8.99}" > "${argument#-Doutput=}"
        fi
    done
else
    exit "${BUILD_STATUS:-0}"
fi
STUB
chmod +x "$work_dir/mvn"
export PATH="$work_dir:$PATH"
MAKE_JACOCO_AUTO=false TEST_JAVA=25 bash "$root/scripts/maven.sh" test
[[ "$(cat "$CALL_LOG")" == test ]]
: > "$CALL_LOG"
MAKE_JACOCO_AUTO=true TEST_JAVA=21 bash "$root/scripts/maven.sh" test
[[ "$(tail -1 "$CALL_LOG")" == test ]]
[[ "$(wc -l < "$CALL_LOG" | tr -d ' ')" == 2 ]]
MAKE_JACOCO_AUTO=true TEST_JAVA=25 bash "$root/scripts/maven.sh" test -Pcoverage
[[ "$(tail -1 "$CALL_LOG")" == 'test -Pcoverage -Djacoco.version=0.8.99' ]]
for mode in resolution invalid jdk; do
    : > "$CALL_LOG"
    case "$mode" in
        resolution) args=(TEST_JAVA=25 RESOLUTION_FAIL=1) ;;
        invalid) args=(TEST_JAVA=25 RELEASE_VERSION=invalid) ;;
        jdk) args=(TEST_JAVA=17) ;;
    esac
    if env MAKE_JACOCO_AUTO=true "${args[@]}" bash "$root/scripts/maven.sh" test \
        > "$work_dir/output" 2>&1; then
        printf 'Expected selector failure: %s\n' "$mode" >&2
        exit 1
    fi
    ! grep -Eq '^test( |$)' "$CALL_LOG"
done
status=0
MAKE_JACOCO_AUTO=true TEST_JAVA=25 BUILD_STATUS=7 bash "$root/scripts/maven.sh" test || status=$?
[[ "$status" == 7 ]]
printf 'Maven JDK selection checks passed.\n'
