#!/usr/bin/env bash
set -euo pipefail
fail() { printf '%s\n' "$*" >&2; exit 1; }
output="${OUTPUT:-human}"
if (( $# > 0 )); then
    [[ $# == 2 && "$1" == -o ]] || fail 'Usage: concourse-status.sh [-o human|json|yaml]'
    output="$2"
fi
case "$output" in
    human|json|yaml) ;;
    *) fail 'OUTPUT must be human, json, or yaml.' ;;
esac
for tool in curl jq; do
    command -v "$tool" >/dev/null || fail "$tool is required."
done
node="${NODE:-}"
nodes="${NODES:-}"
status="${STATUS:-}"
[[ -z "$node" || -z "$nodes" ]] || fail 'Use NODE or NODES, not both.'
case "$status" in
    ''|green|red|orange) ;;
    *) fail 'STATUS must be green, red, or orange.' ;;
esac
selection='[]'
if [[ -n "$node" ]]; then
    selection="$(jq -n --arg node "$node" '[$node]')"
elif [[ -n "$nodes" ]]; then
    if [[ "$nodes" =~ ^[[:space:]]*\[ ]]; then
        selection="$nodes"
    else
        selection="$(jq -n --arg nodes "$nodes" '$nodes | split(",")')"
    fi
fi
if [[ -n "$node" || -n "$nodes" ]]; then
    selection="$(jq -ce '
    if type != "array" or length == 0 then error("Expected a non-empty node array")
    elif any(.[]; type != "string") then error("Node names must be strings")
    else map(gsub("^\\s+|\\s+$"; ""))
      | if any(.[]; length == 0 or test("[\u0000-\u001f\u007f]"))
        then error("Empty names and control characters are not allowed")
        else unique end
    end' <<<"$selection")" || fail 'Invalid node selection.'
fi
base='https://ci-platform.companieshouse.gov.uk/api/v1/teams/team-development/pipelines/address-lookup-api'
fetch() {
    curl --fail --silent --show-error --proto '=https' \
        --connect-timeout 10 --max-time 30 "$base/$1"
}
jobs="$(fetch jobs)"
resources="$(fetch resources)"
for payload in "$jobs" "$resources"; do
    jq -e 'type == "array" and all(.[];
        type == "object" and (.name | type == "string") and
        (.name | length > 0 and (test("[\u0000-\u001f\u007f]") | not)))' \
        <<<"$payload" >/dev/null || fail 'Invalid Concourse node response.'
done
rows="$(jq -n --argjson jobs "$jobs" --argjson resources "$resources" \
    --argjson selection "$selection" --arg filter "$status" '
    def colour:
      if . == "succeeded" then "green"
      elif . == "failed" or . == "errored" or . == "aborted" then "red"
      elif . == "started" or . == "pending" then "orange"
      else "unknown" end;
    def build_label:
      if . == null then "none"
      else ((.name // "check") | tostring) + " (" + (.status // "unknown") + ")" end;
    def has_error:
      any([.check_error, .check_setup_error][]; . != null and . != "");
    ([$jobs[] |
      (if .next_build != null then .next_build else .finished_build end) as $build |
      {kind:"job", name:.name,
       colour:(if .paused == true then "unknown" else ($build.status | colour) end),
       detail:((if .paused == true then "paused; " else "" end) +
         "latest: " + ($build | build_label) +
         (if .next_build != null then "; previous: " + (.finished_build | build_label) else "" end))}]
    + [$resources[] |
      {kind:"resource", name:.name,
       colour:(if has_error or .failing_to_check == true then "red"
         elif .paused == true then "unknown" else (.build.status | colour) end),
       detail:((if has_error or .failing_to_check == true then "check error; "
         elif .paused == true then "paused; " else "" end) +
         "check: " + (.build | build_label) + "; checked: " +
         (if .last_checked == null then "never" else (.last_checked | todateiso8601) end))}]) as $all |
    ($selection - [$all[].name]) as $missing |
    if ($missing | length) > 0 then error("Unknown nodes: " + ($missing | join(", ")))
    else [$all | sort_by(.kind, .name) | .[] |
      select(($selection | length) == 0 or (.name as $name | $selection | index($name) != null)) |
      select($filter == "" or .colour == $filter) |
      {type:.kind, name, status:.colour, detail}]
    end')" || fail 'Unable to classify Concourse status.'
report="$(jq -n --argjson nodes "$rows" \
    '{pipeline:"team-development/address-lookup-api", nodes:$nodes}')"
case "$output" in
    human)
        printf 'Pipeline: team-development/address-lookup-api\n'
        printf 'TYPE\tNODE\tSTATUS\tDETAIL\n'
        if [[ "$(jq '.nodes | length' <<<"$report")" != 0 ]]; then
            jq -r '.nodes[] | [.type, .name, .status, .detail] | @tsv' <<<"$report"
        else
            printf 'No nodes match the selection and status filter.\n'
        fi
        ;;
    json)
        jq . <<<"$report"
        ;;
    yaml)
        jq -r '
          "pipeline: " + (.pipeline | tojson),
          (if (.nodes | length) == 0 then "nodes: []"
           else "nodes:", (.nodes[] |
             "  - type: " + (.type | tojson),
             "    name: " + (.name | tojson),
             "    status: " + (.status | tojson),
             "    detail: " + (.detail | tojson))
           end)' <<<"$report"
        ;;
esac
