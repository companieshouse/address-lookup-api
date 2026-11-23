#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
test_root="$(mktemp -d)"
mkdir -p "$test_root/scripts"
cp "$repo_root/scripts/local-api-smoke.sh" "$repo_root/scripts/maven.sh" "$test_root/scripts/"
script="$test_root/scripts/local-api-smoke.sh"
stub_bin="$test_root/bin"
mkdir -p "$stub_bin"
trap 'rm -rf "$test_root"' EXIT

cat >"$stub_bin/docker" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'docker %s\n' "$*" >>"$CALL_LOG"

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
if [[ "$*" == --version ]]; then
    printf 'Java version: 21.0.13\n'
    exit 0
fi
if [[ "${FAKE_MVN_EXIT:-0}" != "0" ]]; then
    exit "$FAKE_MVN_EXIT"
fi
mkdir -p address-lookup-api/target
touch address-lookup-api/target/address-lookup-api-test.jar
STUB

cat >"$stub_bin/java" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'java %s\n' "$*" >>"$CALL_LOG"
printf '%s|%s|%s|%s|%s\n' \
    "$DOCKER_COMPOSE_ENABLED" \
    "$FLYWAY_ENABLED" \
    "$SPRING_DATASOURCE_URL" \
    "$SPRING_DATASOURCE_USERNAME" \
    "$SPRING_DATASOURCE_PASSWORD" >"$MAVEN_ENV_FILE"

if [[ "${FAKE_JAVA_EXIT:-0}" != "0" ]]; then
    exit "$FAKE_JAVA_EXIT"
fi
[[ "$SERVER_PORT" == 0 ]] || exit 24
if [[ "${FAKE_NO_PORT:-0}" == 0 ]]; then
    printf 'Tomcat started on port 18998 (http)\n' >"$LOGGING_FILE_NAME"
fi

exec sleep 30
STUB

cat >"$stub_bin/curl" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'curl %s\n' "$*" >>"$CALL_LOG"
[[ "$*" == *http://127.0.0.1:18998/address-lookup-api/healthcheck* ]] || exit 25

for ((attempt = 0; attempt < 100; attempt++)); do
    [[ -f "$MAVEN_ENV_FILE" ]] && break
    sleep 0.01
done
if [[ ! -f "$MAVEN_ENV_FILE" ]]; then
    exit 7
fi

printf '%s' "${FAKE_HTTP_STATUS:-200}"
STUB

chmod +x "$stub_bin/docker" "$stub_bin/mvn" "$stub_bin/java" "$stub_bin/curl"

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
grep -Fq 'docker compose -f compose.yaml up -d --wait postgres' "$test_root/success.log" ||
    fail "PostGIS was not started through Compose with readiness checking"
grep -Fq "createdb -U postgres $created_db" "$test_root/success.log" ||
    fail "temporary database was not created through Compose"
grep -Fq "dropdb -U postgres $created_db" "$test_root/success.log" ||
    fail "temporary database was not dropped through Compose"
grep -Fq "psql -U postgres -d $created_db" "$test_root/success.log" ||
    fail "Flyway history was not checked in the temporary database"
grep -Fq 'mvn -pl address-lookup-api -am package -DskipTests=true -Dskip.unit.tests=true -Dskip.integration.tests=true' \
    "$test_root/success.log" || fail "API was not built with tests skipped"
grep -Fq "java -jar $test_root/address-lookup-api/target/address-lookup-api-test.jar --spring.profiles.active=local" \
    "$test_root/success.log" || fail "local-profile API JAR was not run directly"
grep -Fq "false|true|jdbc:postgresql://localhost:5433/$created_db|postgres|postgres" \
    "$test_root/success.maven-env" || fail "API did not receive the expected datasource settings"
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

expect_failure build-failure FAKE_MVN_EXIT=23
[[ "$(cat "$test_root/build-failure.created")" == \
    "$(cat "$test_root/build-failure.dropped")" ]] ||
    fail "build failure did not drop the exact temporary database"
if grep -Fq 'java ' "$test_root/build-failure.log"; then
    fail "API started after Maven build failure"
fi

expect_failure startup-failure FAKE_JAVA_EXIT=23
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

expect_failure port-timeout \
    FAKE_NO_PORT=1 \
    LOCAL_API_STARTUP_TIMEOUT_SECONDS=1 \
    LOCAL_API_HEALTH_POLL_INTERVAL_SECONDS=1
[[ "$(cat "$test_root/port-timeout.created")" == \
    "$(cat "$test_root/port-timeout.dropped")" ]] ||
    fail "port discovery timeout did not drop the exact temporary database"
grep -Fq 'Timed out waiting for healthcheck' "$test_root/port-timeout.out" ||
    fail "port discovery timeout did not report its failure"

grep -Fq 'http://127.0.0.1:18998/address-lookup-api/healthcheck' \
    "$test_root/success.log" || fail "healthcheck did not use the assigned API port"

printf 'PASS: local API smoke helper success and failure cleanup\n'
