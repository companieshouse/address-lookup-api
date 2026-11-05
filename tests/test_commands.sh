#!/usr/bin/env bash
set -euo pipefail
for tool in jq ps; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        printf 'Command helper tests require %s in the build image (ps is provided by procps on Linux).\n' \
            "$tool" >&2
        exit 1
    fi
done
root="$(cd "$(dirname "$0")/.." && pwd)"
original_path="$PATH"
temp="$(mktemp -d)"
mkdir -p "$temp/scripts" "$temp/bin"
cp "$root/scripts/local-services.sh" "$root/scripts/sonar.sh" "$root/scripts/db-exec.sh" "$root/scripts/maven.sh" "$temp/scripts/"
cp "$root/Makefile" "$temp/Makefile"
export CALL_LOG="$temp/calls"
export PATH="$temp/bin:$PATH"
export LOCAL_API_PORT=18997 LOCAL_API_TIMEOUT=2
export CONFIRM="" PR="" PR_BRANCH=feature/commands PR_BASE=main
export SONAR_HOST_URL=https://sonar.example.invalid SONAR_PROJECT_KEY=test-project
export SONAR_TOKEN=fixture-token-not-a-credential
cleanup() {
    if [[ -f "$temp/.local-run/api.json" ]]; then
        bash "$temp/scripts/local-services.sh" api-stop >/dev/null
    fi
    rm -rf "$temp"
}
trap cleanup EXIT
cat >"$temp/bin/tool" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
tool="$(basename "$0")"
printf '%s %s\n' "$tool" "$*" >>"$CALL_LOG"
case "$tool" in
    mvn)
        if [[ "$*" == --version ]]; then
            printf 'Java version: 21.0.13\n'
            exit 0
        fi
        [[ "${FAIL_MAVEN:-0}" == 0 ]] || exit 7
        if [[ "$*" == *" package "* ]]; then
            [[ " $* " == *" -Dskip.unit.tests=true "* ]] || exit 8
            [[ " $* " == *" -Dskip.integration.tests=true "* ]] || exit 8
        fi
        mkdir -p address-lookup-api/target
        touch address-lookup-api/target/address-lookup-api-test.jar
        ;;
    java)
        trap 'exit 0' TERM
        while :; do sleep 0.1; done
        ;;
    lsof)
        [[ "${OCCUPIED:-0}" == 0 ]] || { printf '12345\n'; exit 0; }
        exit 1
        ;;
    curl)
        [[ "${FAIL_HTTP:-0}" == 0 ]] || exit 22
        output=""
        while (( $# )); do
            if [[ "$1" == --output ]]; then output="$2"; shift; fi
            shift
        done
        if [[ "$output" == /dev/null ]]; then
            [[ "${FAIL_HEALTH:-0}" == 0 ]] || exit 7
            printf 200
        else
            IFS= read -r header
            [[ "$header" == "Authorization: Bearer $SONAR_TOKEN" ]] || exit 23
            if [[ "${EMPTY_REPORT:-0}" == 1 ]]; then
                printf '{"component":{"measures":[]}}'
            else
                printf '{"component":{"measures":[{"metric":"coverage","value":"82.1"}]}}'
            fi
        fi
        ;;
    docker)
        if [[ "$*" == *psql* && "$*" == *ON_ERROR_STOP=1* ]]; then
            [[ "${FAIL_SQL:-0}" == 0 ]] || exit 3
            [[ "${*: -2:1}" == --file ]] && cat >"$CALL_LOG.stdin"
            for argument in "$@"; do printf '%s\n' "$argument"; done >"$CALL_LOG.args"
        fi
        if [[ "$*" == *psql* ]]; then printf '%s\n' "${SEED_COUNT:-5}"; fi
        ;;
    git) printf '%s\n' "${MOCK_BRANCH:-feature/commands}" ;;
esac
STUB
chmod +x "$temp/bin/tool"
for tool in mvn java lsof curl docker git; do ln -s tool "$temp/bin/$tool"; done

pass=0
success() {
    if "$@" >"$temp/output" 2>&1; then
        pass=$((pass + 1))
    else
        cat "$temp/output" >&2
        printf 'FAIL expected success: %s\n' "$*" >&2
        exit 1
    fi
}
failure() {
    if "$@" >"$temp/output" 2>&1; then
        printf 'FAIL expected failure: %s\n' "$*" >&2
        exit 1
    fi
    pass=$((pass + 1))
}
check() {
    "$@" || {
        printf 'FAIL assertion: %s\n' "$*" >&2
        if [[ -f "$temp/output" ]]; then
            printf 'Last command output:\n' >&2
            cat "$temp/output" >&2
        fi
        exit 1
    }
    pass=$((pass + 1))
}
local_command() { bash "$temp/scripts/local-services.sh" "$@"; }
sonar_command() { bash "$temp/scripts/sonar.sh" "$@"; }

query="SELECT 'apostrophe''s; literal \$(not_a_make_variable)' AS value;"
success make --no-print-directory -C "$temp" db
check grep -Fxq 'docker compose -f compose.yaml exec postgres psql -X -U postgres -d address_lookup' "$CALL_LOG"
success env SQL="$query" bash "$temp/scripts/db-exec.sh"
check grep -Fxq -- "$query" "$CALL_LOG.args"
check grep -Fxq -- ON_ERROR_STOP=1 "$CALL_LOG.args"
failure env SQL='SELECT invalid;' FAIL_SQL=1 bash "$temp/scripts/db-exec.sh"
success env SQL='SELECT 42;' make --no-print-directory -C "$temp" db-exec
check grep -Fxq -- 'SELECT 42;' "$CALL_LOG.args"
printf '%s\n' "$query" >"$temp/query.sql"
success env SQL= make --no-print-directory -C "$temp" db-exec <"$temp/query.sql"
check test "$(cat "$temp/query.sql"; printf '.')" = "$(cat "$CALL_LOG.stdin"; printf '.')"

failure env OCCUPIED=1 bash "$temp/scripts/local-services.sh" api-start
check grep -q occupied "$temp/output"
check test ! -f "$temp/.local-run/api.json"
failure local_command db-reset
check grep -q CONFIRM=address_lookup "$temp/output"
success local_command start
check grep -Fxq 'mvn -pl address-lookup-api -am package -DskipTests=true -Dskip.unit.tests=true -Dskip.integration.tests=true' "$CALL_LOG"
check grep -q 'up -d --wait postgres' "$CALL_LOG"
success local_command api-start
check test "$(grep -c '^java ' "$CALL_LOG")" == 1
success local_command api-restart
success local_command stop
check test ! -e "$temp/.local-run/api.json"
check grep -q 'stop postgres' "$CALL_LOG"

LC_ALL=C ps -p $$ -o lstart= >"$temp/identity"
jq -n --argjson pid "$$" --arg identity unrelated --argjson port "$LOCAL_API_PORT" \
    '{pid:$pid,identity:$identity,port:$port}' >"$temp/.local-run/api.json"
failure local_command api-stop
check kill -0 "$$"
rm "$temp/.local-run/api.json"
printf '{"pid":"bad"}' >"$temp/.local-run/api.json"
failure local_command api-stop
check grep -q 'Invalid API PID state' "$temp/output"
rm "$temp/.local-run/api.json"

success env CONFIRM=address_lookup bash "$temp/scripts/local-services.sh" db-reset
check grep -q 'dropdb -U postgres --if-exists --force address_lookup' "$CALL_LOG"
success local_command api-stop
failure env CONFIRM=address_lookup SEED_COUNT=0 bash "$temp/scripts/local-services.sh" db-reset
check grep -q 'no seeded addresses' "$temp/output"
success local_command api-stop
failure env FAIL_HEALTH=1 LOCAL_API_TIMEOUT=1 bash "$temp/scripts/local-services.sh" api-start
check test ! -e "$temp/.local-run/api.json"
failure env FAIL_MAVEN=1 bash "$temp/scripts/local-services.sh" api-start
check test ! -e "$temp/.local-run/api.json"

success sonar_command sonar
check grep -q 'sonar-maven-plugin:5.0.0.4389:sonar' "$CALL_LOG"
check test "$(grep -c fixture-token "$CALL_LOG" || true)" == 0
failure env SONAR_TOKEN= bash "$temp/scripts/sonar.sh" sonar-report
check grep -q 'SONAR_TOKEN is missing' "$temp/output"
failure env SONAR_HOST_URL=http://sonar.invalid bash "$temp/scripts/sonar.sh" sonar-report
failure sonar_command sonar-pr
success env PR=42 bash "$temp/scripts/sonar.sh" sonar-pr
check grep -q -- '-Dsonar.pullrequest.key=42' "$CALL_LOG"
failure env PR=42 MOCK_BRANCH=other bash "$temp/scripts/sonar.sh" sonar-pr
failure env PR='42;echo unsafe' bash "$temp/scripts/sonar.sh" sonar-pr
success env PR=42 bash "$temp/scripts/sonar.sh" sonar-report
check jq -e '.component.measures[0].value == "82.1"' "$temp/output"
check test ! -e "$temp/.local-run/sonar-coverage-pr-42.json"
failure env FAIL_HTTP=1 bash "$temp/scripts/sonar.sh" sonar-report
check test ! -e "$temp/.local-run/sonar-coverage.json"
failure env EMPTY_REPORT=1 bash "$temp/scripts/sonar.sh" sonar-report
failure env FAIL_MAVEN=1 bash "$temp/scripts/sonar.sh" sonar
success env SONAR_HOST_URL= SONAR_PROJECT_KEY= make --no-print-directory -C "$temp" sonar-report PR=83
check jq -e '.component.measures[0].metric == "coverage"' "$temp/output"
check test ! -e "$temp/.local-run/sonar-coverage-pr-83.json"
check grep -q 'https://sonarqube.companieshouse.gov.uk/api/measures/component' "$CALL_LOG"
check grep -q 'component=uk.gov.companieshouse:address-lookup-api' "$CALL_LOG"
success env SONAR_HOST_URL=https://sonar.override.invalid SONAR_PROJECT_KEY=other-project \
    make --no-print-directory -C "$temp" sonar-report PR=84
check grep -q 'https://sonar.override.invalid/api/measures/component' "$CALL_LOG"
check grep -q 'component=other-project' "$CALL_LOG"
printf 'PASS: %s local lifecycle and Sonar command checks\n' "$pass"

mkdir -p "$temp/formatting/scripts" "$temp/formatting/nested path" \
    "$temp/formatting/module/target/generated-sources"
cp "$root/pom.xml" "$root/Makefile" "$temp/formatting/"
cp "$root/scripts/spotless.sh" "$temp/formatting/scripts/"
dirty_java='import java.util.List; class Example{void run(){System.out.println("hello");}}'
printf '%s\n' "$dirty_java" >"$temp/formatting/nested path/Example.java"
printf 'not Java\n' >"$temp/formatting/module/target/generated-sources/Ignored.java"
for number in {1..12}; do
    printf 'class Example%s{void run(){}}\n' "$number" >"$temp/formatting/Example$number.java"
done
format_command() {
    PATH="$original_path" bash "$temp/formatting/scripts/spotless.sh" "$@"
}
status=0
format_command check >"$temp/violations" || status=$?
check test "$status" -eq 255
check test "$(wc -l <"$temp/violations" | tr -d ' ')" -eq 13
check grep -Fxq 'nested path/Example.java' "$temp/violations"
check test "$(cat "$temp/formatting/nested path/Example.java")" = "$dirty_java"
success env PATH="$original_path" make --no-print-directory -C "$temp/formatting" \
    tidy 'FILE=nested path/Example.java'
check grep -Fq '    void run() {' "$temp/formatting/nested path/Example.java"
check test "$(grep -c 'java.util.List' "$temp/formatting/nested path/Example.java" || true)" -eq 0
check test "$(cat "$temp/formatting/Example1.java")" = 'class Example1{void run(){}}'
printf '%s\n' "$dirty_java" >"$temp/input.java"
success env FILE= PATH="$original_path" bash "$temp/formatting/scripts/spotless.sh" tidy <"$temp/input.java"
check test "$(cat "$temp/output"; printf .)" = \
    "$(cat "$temp/formatting/nested path/Example.java"; printf .)"
success env FILE= PATH="$original_path" make --no-print-directory -C "$temp/formatting" tidy <"$temp/input.java"
check test "$(cat "$temp/output"; printf .)" = \
    "$(cat "$temp/formatting/nested path/Example.java"; printf .)"
failure env FILE= PATH="$original_path" bash "$temp/formatting/scripts/spotless.sh" tidy </dev/null
failure env FILE=missing.java PATH="$original_path" bash "$temp/formatting/scripts/spotless.sh" tidy
success env PATH="$original_path" make --no-print-directory -C "$temp/formatting" tidy FILE=.
success format_command check
check test ! -s "$temp/output"
success env PATH="$original_path" make --no-print-directory -C "$temp/formatting" spotless-check
check test ! -s "$temp/output"
printf '%s\n' "$dirty_java" >"$temp/formatting/Example1.java"
status=0
PATH="$original_path" make --no-print-directory -C "$temp/formatting" spotless-check \
    >"$temp/output" 2>"$temp/error" || status=$?
check test "$status" -eq 2
check grep -Fxq Example1.java "$temp/output"
failure env FILE="$temp/formatting/Example1.java" FAIL_MAVEN=1 \
    bash "$temp/formatting/scripts/spotless.sh" tidy
check grep -q 'no source files were changed' "$temp/output"
check test "$(cat "$temp/formatting/Example1.java")" = "$dirty_java"
printf 'class Invalid {\n' >"$temp/formatting/Invalid.java"
failure env FILE="$temp/formatting/Invalid.java" PATH="$original_path" \
    bash "$temp/formatting/scripts/spotless.sh" tidy
check grep -q 'no source files were changed' "$temp/output"
status=0
format_command check >"$temp/output" 2>&1 || status=$?
check test "$status" -eq 1
check grep -q 'no source files were changed' "$temp/output"
printf 'PASS: %s command checks including Spotless regressions\n' "$pass"

# This is the existing PR quality-check entry point, not just a stub test.
PATH="$original_path" bash "$root/scripts/spotless.sh" check
