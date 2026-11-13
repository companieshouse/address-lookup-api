#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
action="${1:-}"
case "$action" in
    api-start|api-stop|api-restart|db-start|db-stop|start|stop|db-reset) ;;
    *) printf 'Unknown local lifecycle action: %s\n' "$action" >&2; exit 2 ;;
esac
[[ $# == 1 ]] || { printf 'Expected one action.\n' >&2; exit 2; }
port="${LOCAL_API_PORT:-8080}"
timeout="${LOCAL_API_TIMEOUT:-180}"
[[ "$port" =~ ^[1-9][0-9]{3,4}$ ]] && (( port >= 1024 && port <= 65535 )) ||
    { printf 'LOCAL_API_PORT must be 1024-65535.\n' >&2; exit 2; }
[[ "$timeout" =~ ^[1-9][0-9]*$ ]] ||
    { printf 'LOCAL_API_TIMEOUT must be a positive integer.\n' >&2; exit 2; }
for tool in jq curl ps lsof; do
    command -v "$tool" >/dev/null || { printf 'Required command unavailable: %s\n' "$tool" >&2; exit 1; }
done
state_dir="$root/.local-run"
umask 077
mkdir -p "$state_dir"
lock="$state_dir/services.lock"
mkdir "$lock" 2>/dev/null ||
    { printf 'Lifecycle command locked; inspect %s before removing a stale lock.\n' "$lock" >&2; exit 1; }
trap 'rmdir "$lock"' EXIT
state="$state_dir/api.json"
compose=(docker compose -f "$root/compose.yaml")
pid=""
fingerprint=""
managed_port=""

identity() {
    LC_ALL=C ps -p "$1" -o lstart= | sed 's/[[:space:]]*$//'
}

read_state() {
    [[ -e "$state" ]] || return 1
    pid="$(jq -er '.pid | select(type == "number" and . > 1 and . == floor)' "$state")" ||
        { printf 'Invalid API PID state.\n' >&2; exit 1; }
    fingerprint="$(jq -er '.identity | select(type == "string" and length > 0)' "$state")" ||
        { printf 'Invalid API identity state.\n' >&2; exit 1; }
    managed_port="$(jq -er '.port | select(type == "number")' "$state")" ||
        { printf 'Invalid API port state.\n' >&2; exit 1; }
    if ! kill -0 "$pid" 2>/dev/null; then
        rm "$state" || { printf 'Cannot remove stale API state.\n' >&2; exit 1; }
        return 1
    fi
    [[ "$(identity "$pid")" == "$fingerprint" ]] ||
        { printf 'PID identity mismatch; refusing to signal an unrelated process.\n' >&2; exit 1; }
    local command_line
    command_line="$(ps -ww -p "$pid" -o args=)"
    [[ "$command_line" == *"$state_dir/api.jar"* ||
        "$command_line" == *"$root/address-lookup-api/target/address-lookup-api-"* ]] ||
        { printf 'PID is not this checkout API; refusing to signal it.\n' >&2; exit 1; }
}

api_stop() {
    if ! read_state; then
        printf 'No managed API is running.\n'
        return
    fi
    kill -TERM "$pid"
    for ((attempt=0; attempt<100; attempt++)); do
        if ! kill -0 "$pid" 2>/dev/null || [[ "$(ps -p "$pid" -o stat=)" == *Z* ]]; then
            rm "$state"
            printf 'Stopped managed API.\n'
            return
        fi
        sleep 0.2
    done
    # A JVM busy inside a long database statement (for example a seed insert) ignores SIGTERM.
    printf 'API PID %s ignored SIGTERM for 20 seconds; sending SIGKILL.\n' "$pid" >&2
    kill -KILL "$pid"
    for ((attempt=0; attempt<25; attempt++)); do
        if ! kill -0 "$pid" 2>/dev/null || [[ "$(ps -p "$pid" -o stat=)" == *Z* ]]; then
            rm "$state"
            printf 'Stopped managed API.\n'
            return
        fi
        sleep 0.2
    done
    printf 'API PID %s did not stop after SIGKILL; state retained.\n' "$pid" >&2
    return 1
}

port_free() {
    local listeners status
    status=0
    listeners="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t)" || status=$?
    if [[ -n "$listeners" ]]; then
        printf 'Port %s is occupied; refusing to replace its owner.\n' "$port" >&2
        return 1
    fi
    if (( status > 1 )); then
        printf 'Could not inspect port %s with lsof.\n' "$port" >&2
        return 1
    fi
}

health_ok() {
    local status
    status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
        --max-time 2 "http://127.0.0.1:$port/address-lookup-api/healthcheck")" || return 1
    [[ "$status" == 200 ]]
}

api_start() {
    if read_state; then
        [[ "$managed_port" == "$port" ]] ||
            { printf 'API already running on port %s; stop it before changing ports.\n' "$managed_port" >&2; return 1; }
        health_ok || { printf 'Managed API is unhealthy; inspect .local-run/api.log.\n' >&2; return 1; }
        printf 'Managed API already healthy on port %s.\n' "$port"
        return
    fi
    port_free
    bash "$root/scripts/maven.sh" -pl address-lookup-api -am package -DskipTests=true \
        -Dskip.unit.tests=true -Dskip.integration.tests=true
    local jars=()
    shopt -s nullglob
    jars=("$root"/address-lookup-api/target/address-lookup-api-*.jar)
    [[ ${#jars[@]} == 1 ]] ||
        { printf 'Expected exactly one API JAR; clean stale build outputs first.\n' >&2; return 1; }
    port_free
    # Run a private copy so rebuilding the project while the API seeds cannot corrupt the running JAR.
    cp "${jars[0]}" "$state_dir/api.jar"
    local log_start
    log_start="$(wc -c <"$state_dir/api.log" 2>/dev/null || echo 0)"
    LOCAL_SEED_DIR="${LOCAL_SEED_DIR:-$root/database/local/data}" \
    nohup java -jar "$state_dir/api.jar" --spring.profiles.active=local \
        "--server.port=$port" --spring.docker.compose.enabled=false \
        --spring.datasource.url=jdbc:postgresql://localhost:5433/address_lookup \
        --spring.flyway.enabled=true \
        --spring.flyway.locations=classpath:db/migrations,classpath:db/local,classpath:db/local/java \
        </dev/null >>"$state_dir/api.log" 2>&1 &
    pid=$!
    if ! fingerprint="$(identity "$pid")" || [[ -z "$fingerprint" ]]; then
        wait "$pid" || true
        printf 'API exited at startup; inspect .local-run/api.log.\n' >&2
        return 1
    fi
    if ! jq -n --argjson pid "$pid" --arg identity "$fingerprint" --argjson port "$port" \
        '{pid:$pid,identity:$identity,port:$port}' >"$state.tmp" ||
        ! mv "$state.tmp" "$state"; then
        kill -TERM "$pid"
        wait "$pid" || true
        printf 'Failed to save API state; stopped launched process.\n' >&2
        return 1
    fi
    # A reset has no deadline: the seed can run for a long time, so show its progress
    # and wait for as long as the API process is alive.
    local deadline=$((SECONDS + timeout)) shown=$log_start
    while [[ -n "${wait_forever:-}" ]] || (( SECONDS < deadline )); do
        kill -0 "$pid" 2>/dev/null || break
        if [[ -n "${wait_forever:-}" ]]; then
            local size
            size="$(wc -c <"$state_dir/api.log")"
            if (( size > shown )); then
                local chunk
                chunk="$(tail -c +"$((shown + 1))" "$state_dir/api.log")"
                grep -a 'Seed \|Staged \|Seeded ' <<<"$chunk" || true
                shown=$size
                # A failed startup can leave the JVM alive, so stop waiting as soon as it is logged.
                if grep -aq 'Exception encountered during context initialization\|APPLICATION FAILED TO START' <<<"$chunk"; then
                    api_stop
                    printf 'API startup failed; inspect .local-run/api.log.\n' >&2
                    return 1
                fi
            fi
        fi
        if health_ok; then
            printf 'API healthy on port %s; log: .local-run/api.log\n' "$port"
            return
        fi
        sleep 2
    done
    api_stop
    printf 'API failed readiness; inspect .local-run/api.log.\n' >&2
    return 1
}

db_start() { "${compose[@]}" up -d --wait postgres; }
db_stop() { "${compose[@]}" stop postgres; }

case "$action" in
    api-start) api_start ;;
    api-stop) api_stop ;;
    api-restart) api_stop; api_start ;;
    db-start) db_start ;;
    db-stop) db_stop ;;
    start) db_start; api_start ;;
    stop) api_stop; db_stop ;;
    db-reset)
        [[ "${CONFIRM:-}" == address_lookup ]] ||
            { printf 'Destructive reset requires CONFIRM=address_lookup.\n' >&2; exit 2; }
        api_stop
        port_free
        db_start
        # A reset always starts from zero: discard any cached CSVs and download again.
        # Download first so a failed download never costs the developer their database.
        rm -f "${LOCAL_SEED_DIR:-$root/database/local/data}"/add_{gb,isl}_royalmailaddress.csv{,.version,.part,.zip.part}
        bash "$root/scripts/fetch-os-csv.sh" ||
            { printf 'Seed CSV download failed; the database was not changed.\n' >&2; exit 1; }
        "${compose[@]}" exec -T postgres dropdb -U postgres --if-exists --force address_lookup
        "${compose[@]}" exec -T postgres createdb -U postgres address_lookup
        wait_forever=1
        api_start
        count="$("${compose[@]}" exec -T postgres psql -U postgres -d address_lookup \
            -v ON_ERROR_STOP=1 -qAt -c 'SELECT count(*) FROM address_lookup.address_lookup')"
        [[ "$count" =~ ^[1-9][0-9]*$ ]] ||
            { printf 'Reset produced no seeded addresses.\n' >&2; exit 1; }
        printf 'Local database reset and seed verification complete (%s rows).\n' "$count"
        ;;
esac
