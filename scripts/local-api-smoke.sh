#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
db_user="${SPRING_DATASOURCE_USERNAME:-postgres}"
db_password="${SPRING_DATASOURCE_PASSWORD:-postgres}"
startup_timeout="${LOCAL_API_STARTUP_TIMEOUT_SECONDS:-90}"
poll_interval="${LOCAL_API_HEALTH_POLL_INTERVAL_SECONDS:-2}"
db_name="address_lookup_tkt012_$(date +%Y%m%d%H%M%S)_$$"
health_url="http://localhost:8080/address-lookup-api/healthcheck"
api_pid=""
database_created=false

for command_name in d2 mvn curl; do
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
        elif d2 als.docker.compose exec -T postgres dropdb -U "$db_user" "$db_name"; then
            if remaining_databases="$(
                d2 als.docker.compose exec -T postgres psql -U "$db_user" \
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

    exit "$exit_status"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cd "$repo_root"
if curl --silent --output /dev/null --max-time 2 "$health_url"; then
    printf 'Health endpoint already responds at %s; refusing to run a concurrent smoke test.\n' \
        "$health_url" >&2
    exit 1
fi

d2 als.docker.compose up -d postgres
d2 als.docker.compose exec -T postgres createdb -U "$db_user" "$db_name"
database_created=true
printf 'Created temporary database %s.\n' "$db_name"

(
    cd "$repo_root"
    export DOCKER_COMPOSE_ENABLED=false
    export FLYWAY_ENABLED=true
    export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5433/$db_name"
    export SPRING_DATASOURCE_USERNAME="$db_user"
    export SPRING_DATASOURCE_PASSWORD="$db_password"
    exec mvn -pl address-lookup-api spring-boot:run \
        -Dspring-boot.run.profiles=local
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

    http_status=""
    if http_status="$(
        curl --silent --output /dev/null --write-out '%{http_code}' \
            --max-time 2 "$health_url" 2>/dev/null
    )" && [[ "$http_status" == 200 ]]; then
        migration_count=""
        if ! migration_count="$(
            d2 als.docker.compose exec -T postgres psql -U "$db_user" \
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

printf 'Timed out waiting for healthcheck at %s.\n' "$health_url" >&2
exit 1
