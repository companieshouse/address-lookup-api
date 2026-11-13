#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
db_user="${SPRING_DATASOURCE_USERNAME:-postgres}"
db_password="${SPRING_DATASOURCE_PASSWORD:-postgres}"
startup_timeout="${LOCAL_API_STARTUP_TIMEOUT_SECONDS:-90}"
poll_interval="${LOCAL_API_HEALTH_POLL_INTERVAL_SECONDS:-2}"
db_name="address_lookup_tkt012_$(date +%Y%m%d%H%M%S)_$$"
health_url=""
api_log=""
api_pid=""
database_created=false

for command_name in docker mvn java curl; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        printf 'Required command is unavailable: %s\n' "$command_name" >&2
        exit 1
    fi
done

if [[ ! "$startup_timeout" =~ ^[1-9][0-9]*$ ]]; then
    printf 'LOCAL_API_STARTUP_TIMEOUT_SECONDS must be a positive integer.\n' >&2
    exit 2
fi

if [[ ! "$poll_interval" =~ ^[1-9][0-9]*$ ]]; then
    printf 'LOCAL_API_HEALTH_POLL_INTERVAL_SECONDS must be a positive integer.\n' >&2
    exit 2
fi

cleanup() {
    local exit_status=$?
    local api_status
    local remaining_databases

    trap - EXIT INT TERM

    if [[ -n "$api_pid" ]]; then
        if kill -0 "$api_pid" 2>/dev/null; then
            if ! kill -TERM "$api_pid" 2>/dev/null &&
                kill -0 "$api_pid" 2>/dev/null; then
                printf 'Could not stop API process %s.\n' "$api_pid" >&2
                exit_status=1
            fi
        fi

        if wait "$api_pid"; then
            :
        else
            api_status=$?
            if [[ "$api_status" -ne 143 && "$api_status" -ne 130 ]]; then
                printf 'API process exited with status %s during cleanup.\n' \
                    "$api_status" >&2
                exit_status=1
            fi
        fi
        api_pid=""
    fi

    if [[ "$database_created" == true ]]; then
        if [[ ! "$db_name" =~ ^address_lookup_tkt012_[0-9]{14}_[0-9]+$ ]]; then
            printf 'Refusing to remove unexpected database name: %s\n' \
                "$db_name" >&2
            exit_status=1
        elif docker compose -f compose.yaml exec -T postgres dropdb -U "$db_user" "$db_name"; then
            if remaining_databases="$(
                docker compose -f compose.yaml exec -T postgres psql -U "$db_user" \
                    -d postgres -qAt -c \
                    "SELECT 1 FROM pg_database WHERE datname = '$db_name'"
            )"; then
                if [[ -n "$remaining_databases" ]]; then
                    printf 'Temporary database still exists after cleanup: %s\n' \
                        "$db_name" >&2
                    exit_status=1
                else
                    printf 'Removed temporary database %s.\n' "$db_name"
                fi
            else
                printf 'Could not verify removal of temporary database %s.\n' \
                    "$db_name" >&2
                exit_status=1
            fi
        else
            printf 'Could not remove temporary database %s.\n' "$db_name" >&2
            exit_status=1
        fi
    fi

    [[ -z "$api_log" ]] || rm -f "$api_log"
    exit "$exit_status"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

api_log="$(mktemp)"
cd "$repo_root"
docker compose -f compose.yaml up -d --wait postgres
docker compose -f compose.yaml exec -T postgres createdb -U "$db_user" "$db_name"
database_created=true
printf 'Created temporary database %s.\n' "$db_name"

bash "$repo_root/scripts/maven.sh" -pl address-lookup-api -am package \
    -DskipTests=true -Dskip.unit.tests=true -Dskip.integration.tests=true
shopt -s nullglob
jars=("$repo_root"/address-lookup-api/target/address-lookup-api-*.jar)
if [[ ${#jars[@]} != 1 ]]; then
    printf 'Expected exactly one API JAR; clean stale build outputs first.\n' >&2
    exit 1
fi

(
    cd "$repo_root"
    export DOCKER_COMPOSE_ENABLED=false
    export FLYWAY_ENABLED=true
    export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5433/$db_name"
    export SPRING_DATASOURCE_USERNAME="$db_user"
    export SPRING_DATASOURCE_PASSWORD="$db_password"
    export SERVER_PORT=0
    # The smoke check must not import the full OS CSVs cached by db-reset.
    export LOCAL_SEED_DIR="$repo_root/address-lookup-api/src/test/resources/seed-csv"
    export LOGGING_FILE_NAME="$api_log"
    exec java -jar "${jars[0]}" --spring.profiles.active=local
) &
api_pid=$!

deadline=$((SECONDS + startup_timeout))
while (( SECONDS < deadline )); do
    if ! kill -0 "$api_pid" 2>/dev/null; then
        api_status=0
        if wait "$api_pid"; then
            :
        else
            api_status=$?
        fi
        api_pid=""
        printf 'API process exited before healthcheck (status %s).\n' \
            "$api_status" >&2
        exit 1
    fi

    api_port="$(sed -nE 's/.*Tomcat started on port ([0-9]+).*/\1/p' "$api_log" | tail -1)"
    if [[ -z "$api_port" ]]; then
        sleep "$poll_interval"
        continue
    fi
    health_url="http://127.0.0.1:$api_port/address-lookup-api/healthcheck"
    http_status=""
    if http_status="$(
        curl --silent --output /dev/null --write-out '%{http_code}' \
            --max-time 2 "$health_url" 2>/dev/null
    )" && [[ "$http_status" == 200 ]]; then
        migration_count=""
        if ! migration_count="$(
            docker compose -f compose.yaml exec -T postgres psql -U "$db_user" \
                -d "$db_name" -qAt \
                -c 'SELECT COUNT(*) FROM public.flyway_schema_history'
        )"; then
            printf 'Could not verify Flyway history in %s.\n' "$db_name" >&2
            exit 1
        fi
        if [[ ! "$migration_count" =~ ^[1-9][0-9]*$ ]]; then
            printf 'Flyway has no applied migrations in %s.\n' "$db_name" >&2
            exit 1
        fi

        printf 'Healthcheck returned HTTP 200.\n'
        printf 'Flyway applied %s migration(s).\n' "$migration_count"
        exit 0
    fi

    sleep "$poll_interval"
done

printf 'Timed out waiting for healthcheck%s.\n' "${health_url:+ at $health_url}" >&2
exit 1
