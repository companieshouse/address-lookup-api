#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
cleanup() {
    result=$?
    if (( result != 0 )) && [[ -f "$work/output" ]]; then
        cat "$work/output" >&2
    fi
    rm -f "$work/bin/curl" "$work/bin/uname" "$work/bin/sudo" \
        "$work/output" "$work/calls" "$work/installed"
    rmdir "$work/bin" "$work"
}
trap cleanup EXIT
mkdir "$work/bin"
cat > "$work/bin/curl" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$CALLS"
[[ "${NETWORK_FAIL:-0}" != 1 ]] || exit 22
if [[ "$*" == *'/cli?'* ]]; then
    while (( $# )); do
        if [[ "$1" == --output ]]; then
            printf '#!/usr/bin/env bash\nexit %s\n' "${BINARY_FAIL:-0}" > "$2"
            exit
        fi
        shift
    done
    exit 1
fi
if [[ "${BAD_JSON:-0}" == 1 ]]; then printf '<html>Login</html>'; exit; fi
if [[ "${BAD_SHAPE:-0}" == 1 ]]; then printf '{"error":"denied"}'; exit; fi
if [[ "$*" == */jobs ]]; then
    printf '%s\n' '[
      {"name":"healthy","finished_build":{"name":"1","status":"succeeded"}},
      {"name":"failed","finished_build":{"name":"2","status":"failed"}},
      {"name":"running","finished_build":{"name":"3","status":"failed"},"next_build":{"name":"4","status":"started"}},
      {"name":"waiting","next_build":{"name":"5","status":"pending"}},
      {"name":"aborted","finished_build":{"name":"6","status":"aborted"}},
      {"name":"never"},
      {"name":"paused","paused":true,"finished_build":{"name":"7","status":"succeeded"}}
    ]'
else
    printf '%s\n' '[
      {"name":"source","build":{"name":"check","status":"succeeded"},"last_checked":0},
      {"name":"broken","check_error":"error","build":{"status":"succeeded"}},
      {"name":"checking","build":{"status":"started"}},
      {"name":"check-failed","build":{"status":"errored"}},
      {"name":"unseen"}
    ]'
fi
STUB
cat > "$work/bin/uname" <<'STUB'
#!/usr/bin/env bash
if [[ "$1" == -s ]]; then printf '%s\n' "${TEST_OS:-Darwin}"
else printf 'arm64\n'; fi
STUB
cat > "$work/bin/sudo" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == install && "$3" == /usr/local/bin/fly && -x "$2" ]]
[[ "${INSTALL_FAIL:-0}" != 1 ]] || exit 1
printf '%s\n' "$2" > "$INSTALLED"
STUB
chmod +x "$work/bin/"*
export PATH="$work/bin:$PATH" CALLS="$work/calls" INSTALLED="$work/installed"
export NODE='' NODES='' STATUS=''
run() {
    make --no-print-directory -s -C "$root" "$@" > "$work/output" 2>&1
}
expect() {
    grep -Fq "$1" "$work/output" || { cat "$work/output" >&2; exit 1; }
}
reject() {
    if run "$@"; then
        printf 'Expected failure: %s\n' "$*" >&2; cat "$work/output" >&2; exit 1
    fi
}
run concourse-status
expect $'job\thealthy\tgreen'
expect $'job\trunning\torange'
expect 'previous: 3 (failed)'
expect $'job\tnever\tunknown'
expect $'job\tpaused\tunknown'
expect $'resource\tbroken\tred'
expect $'resource\tchecking\torange'
expect '1970-01-01T00:00:00Z'
for selection in 'NODES=["healthy","source"]' 'NODES=healthy,source'; do
    run concourse-status "$selection"
    expect $'job\thealthy\tgreen'
    expect $'resource\tsource\tgreen'
    [[ "$(grep -c $'\t' "$work/output")" == 3 ]]
done
run concourse-status NODE=failed
expect $'job\tfailed\tred'
run concourse-status 'NODES= healthy ,healthy, source '
[[ "$(grep -c $'\t' "$work/output")" == 3 ]]
for colour in green red orange; do
    run concourse-status "STATUS=$colour"
    awk -F '\t' -v colour="$colour" 'NR > 2 && $3 != colour {exit 1}' "$work/output"
done
run consource-status STATUS=red
expect $'job\taborted\tred'
run concourse-status NODE=healthy STATUS=red
expect 'No nodes match'
for input in 'NODES=[]' 'NODES=["healthy",3]' 'NODES=["healthy"' \
    'NODES=["healthy\nbad"]' 'NODES=healthy,' 'STATUS=blue'; do
    : > "$CALLS"
    reject concourse-status "$input"
    [[ ! -s "$CALLS" ]]
done
reject concourse-status NODE=healthy NODES=source
reject concourse-status NODE=missing
expect 'Unknown nodes: missing'
BAD_JSON=1 reject concourse-status
BAD_SHAPE=1 reject concourse-status
NETWORK_FAIL=1 reject concourse-status
run install-fly
expect 'Installed fly'
[[ ! -e "$(cat "$INSTALLED")" ]]
rm "$INSTALLED"
NETWORK_FAIL=1 reject install-fly
[[ ! -e "$INSTALLED" ]]
BINARY_FAIL=1 reject install-fly
[[ ! -e "$INSTALLED" ]]
INSTALL_FAIL=1 reject install-fly
[[ ! -e "$INSTALLED" ]]
TEST_OS=Linux reject install-fly
expect 'Apple Silicon macOS'
printf 'Concourse helper regressions passed.\n'
