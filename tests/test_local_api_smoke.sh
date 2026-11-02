#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
script="$repo_root/scripts/local-api-smoke.sh"
test_root="$(mktemp -d)"
stub_bin="$test_root/bin"
mkdir -p "$stub_bin"
trap 'rm -rf "$test_root"' EXIT

cat >"$stub_bin/d2" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'd2 %s\n' "$*" >>"$CALL_LOG"

if [[ " $* " == *" createdb "* ]]; then
    if [[ "${FAKE_CREATEDB_FAIL:-0}" == "1" ]]; then
        exit 31
    fi
    last_arg=""
    for arg in "$@"; do
        last_arg="$arg"
    done
    printf '%s\n' "$last_arg" >"$CREATED_DB_FILE"
fi

if [[ " $* " == *" dropdb "* ]]; then
    last_arg=""
    for arg in "$@"; do
        last_arg="$arg"
    done
    printf '%s\n' "$last_arg" >"$DROPPED_DB_FILE"
fi

if [[ " $* " == *" psql "* && " $* " != *" -d postgres "* ]]; then
    printf '%s' "${FAKE_MIGRATION_COUNT:-6}"
fi
STUB

cat >"$stub_bin/mvn" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'mvn %s\n' "$*" >>"$CALL_LOG"
printf '%s|%s|%s|%s|%s\n' \
    "$DOCKER_COMPOSE_ENABLED" \
    "$FLYWAY_ENABLED" \
    "$SPRING_DATASOURCE_URL" \
    "$SPRING_DATASOURCE_USERNAME" \
    "$SPRING_DATASOURCE_PASSWORD" >"$MAVEN_ENV_FILE"

if [[ "${FAKE_MVN_EXIT:-0}" != "0" ]]; then
    exit "$FAKE_MVN_EXIT"
fi

exec sleep 30
STUB

cat >"$stub_bin/curl" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'curl %s\n' "$*" >>"$CALL_LOG"
if [[ ! -e "$CURL_PREFLIGHT_FILE" ]]; then
    : >"$CURL_PREFLIGHT_FILE"
    if [[ "${FAKE_PREFLIGHT_LISTENING:-0}" == "1" ]]; then
        printf '%s' "${FAKE_PREFLIGHT_STATUS:-200}"
        exit 0
    fi
    exit 7
fi

for ((attempt = 0; attempt < 100; attempt++)); do
    [[ -f "$MAVEN_ENV_FILE" ]] && break
    sleep 0.01
done
if [[ ! -f "$MAVEN_ENV_FILE" ]]; then
    exit 7
fi

printf '%s' "${FAKE_HTTP_STATUS:-200}"
STUB

chmod +x "$stub_bin/d2" "$stub_bin/mvn" "$stub_bin/curl"

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

run_helper() {
    local name="$1"
    shift
    env \
        PATH="$stub_bin:$PATH" \
        CALL_LOG="$test_root/$name.log" \
        CREATED_DB_FILE="$test_root/$name.created" \
        DROPPED_DB_FILE="$test_root/$name.dropped" \
        CURL_PREFLIGHT_FILE="$test_root/$name.curl-preflight" \
        MAVEN_ENV_FILE="$test_root/$name.maven-env" \
        SPRING_DATASOURCE_PASSWORD=postgres \
        "$@" \
        "$script"
}

expect_failure() {
    local name="$1"
    shift
    if run_helper "$name" "$@" >"$test_root/$name.out" 2>&1; then
        fail "$name unexpectedly succeeded"
    fi
}

run_helper success
created_db="$(cat "$test_root/success.created")"
dropped_db="$(cat "$test_root/success.dropped")"
[[ "$created_db" =~ ^address_lookup_tkt012_[0-9]{14}_[0-9]+$ ]] ||
    fail "temporary database name was not unique and ticket-scoped"
[[ "$created_db" == "$dropped_db" ]] ||
    fail "success cleanup did not drop the exact created database"
grep -Fq 'd2 als.docker.compose up -d postgres' "$test_root/success.log" ||
    fail "PostGIS was not started through d2"
grep -Fq "createdb -U postgres $created_db" "$test_root/success.log" ||
    fail "temporary database was not created through d2"
grep -Fq "dropdb -U postgres $created_db" "$test_root/success.log" ||
    fail "temporary database was not dropped through d2"
grep -Fq "psql -U postgres -d $created_db" "$test_root/success.log" ||
    fail "Flyway history was not checked in the temporary database"
grep -Fq 'mvn -pl address-lookup-api spring-boot:run -Dspring-boot.run.profiles=local' \
    "$test_root/success.log" || fail "local-profile API command was not run"
grep -Fq "false|true|jdbc:postgresql://localhost:5433/$created_db|postgres|postgres" \
    "$test_root/success.maven-env" || fail "Maven did not receive the expected datasource settings"
if grep -Eq 'dropdb -U postgres address_lookup$' "$test_root/success.log"; then
    fail "the default address_lookup database was targeted for removal"
fi

expect_failure create-failure FAKE_CREATEDB_FAIL=1
if grep -Fq 'mvn ' "$test_root/create-failure.log"; then
    fail "Maven started after database creation failed"
fi
if grep -Fq 'dropdb ' "$test_root/create-failure.log"; then
    fail "cleanup targeted a database that was not created"
fi

expect_failure startup-failure FAKE_MVN_EXIT=23
[[ "$(cat "$test_root/startup-failure.created")" == \
    "$(cat "$test_root/startup-failure.dropped")" ]] ||
    fail "startup failure did not drop the exact temporary database"

expect_failure migration-failure FAKE_MIGRATION_COUNT=0
[[ "$(cat "$test_root/migration-failure.created")" == \
    "$(cat "$test_root/migration-failure.dropped")" ]] ||
    fail "Flyway verification failure did not drop the exact temporary database"

expect_failure health-timeout \
    FAKE_HTTP_STATUS=503 \
    LOCAL_API_STARTUP_TIMEOUT_SECONDS=1 \
    LOCAL_API_HEALTH_POLL_INTERVAL_SECONDS=1
[[ "$(cat "$test_root/health-timeout.created")" == \
    "$(cat "$test_root/health-timeout.dropped")" ]] ||
    fail "health timeout did not drop the exact temporary database"
grep -Fq 'Timed out waiting for healthcheck' "$test_root/health-timeout.out" ||
    fail "health timeout did not report its failure"

expect_failure existing-health-service \
    FAKE_PREFLIGHT_LISTENING=1 \
    FAKE_PREFLIGHT_STATUS=404
grep -Fq 'Health endpoint already responds' \
    "$test_root/existing-health-service.out" ||
    fail "an existing health endpoint was not reported"
if grep -Fq 'd2 als.docker.compose up -d postgres' \
    "$test_root/existing-health-service.log"; then
    fail "PostGIS startup continued when the health endpoint was already in use"
fi
if grep -Fq 'createdb ' "$test_root/existing-health-service.log"; then
    fail "a temporary database was created when the health endpoint was already in use"
fi
if grep -Fq 'mvn ' "$test_root/existing-health-service.log"; then
    fail "Maven started when the health endpoint was already in use"
fi

printf 'PASS: local API smoke helper success and failure cleanup\n'
