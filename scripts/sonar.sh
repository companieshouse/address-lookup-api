#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
action="${1:-}"
[[ $# == 1 ]] || { printf 'Expected one Sonar action.\n' >&2; exit 2; }
case "$action" in
    sonar|sonar-pr|sonar-report) ;;
    *) printf 'Unknown Sonar action.\n' >&2; exit 2 ;;
esac
[[ -n "${SONAR_HOST_URL:-}" ]] ||
    { printf 'SONAR_HOST_URL is missing; use the Make target or export the server URL.\n' >&2; exit 2; }
[[ -n "${SONAR_PROJECT_KEY:-}" ]] ||
    { printf 'SONAR_PROJECT_KEY is missing; use the Make target or export the project key.\n' >&2; exit 2; }
[[ -n "${SONAR_TOKEN:-}" ]] ||
    { printf 'SONAR_TOKEN is missing; set it securely in the environment, not in Make arguments.\n' >&2; exit 2; }
url="${SONAR_HOST_URL%/}"
[[ "$url" =~ ^https://[a-zA-Z0-9.-]+(:[0-9]+)?(/[a-zA-Z0-9._~/-]*)?$ ]] ||
    { printf 'SONAR_HOST_URL must be HTTPS without credentials, query, or fragment.\n' >&2; exit 2; }
[[ "$SONAR_PROJECT_KEY" =~ ^[a-zA-Z0-9_.:-]+$ ]] ||
    { printf 'Invalid SONAR_PROJECT_KEY.\n' >&2; exit 2; }
[[ "$SONAR_TOKEN" != *$'\n'* && "$SONAR_TOKEN" != *$'\r'* ]] ||
    { printf 'Invalid SONAR_TOKEN.\n' >&2; exit 2; }
pr="${PR:-}"
[[ -z "$pr" || "$pr" =~ ^[1-9][0-9]*$ ]] ||
    { printf 'PR must be a positive number without #.\n' >&2; exit 2; }

if [[ "$action" == sonar-report ]]; then
    command -v jq >/dev/null || { printf 'jq is required.\n' >&2; exit 1; }
    args=(--get --data-urlencode "component=$SONAR_PROJECT_KEY"
        --data-urlencode 'metricKeys=coverage,line_coverage,branch_coverage,lines_to_cover,uncovered_lines')
    [[ -z "$pr" ]] || args+=(--data-urlencode "pullRequest=$pr")
    # Header input via stdin keeps the token out of the process argument list.
    response="$(
    printf 'Authorization: Bearer %s\n' "$SONAR_TOKEN" |
        curl --silent --show-error --fail --max-time 30 --proto '=https' \
            --header @- "${args[@]}" "$url/api/measures/component"
    )"
    jq -e '.component.measures | type == "array"' <<<"$response" >/dev/null ||
        { printf 'SonarQube returned an invalid measures response.\n' >&2; exit 1; }
    jq -e 'any(.component.measures[]; .metric == "coverage")' <<<"$response" >/dev/null ||
        { printf 'No coverage measures returned; analyze this project/PR first.\n' >&2; exit 1; }
    jq . <<<"$response"
    exit
fi

properties=("-Dsonar.host.url=$url" "-Dsonar.projectKey=$SONAR_PROJECT_KEY"
    -Dsonar.qualitygate.wait=true)
if [[ "$action" == sonar-pr ]]; then
    [[ -n "$pr" ]] || { printf 'PR is required for sonar-pr.\n' >&2; exit 2; }
    [[ "${PR_BRANCH:-}" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_./-]*$ &&
       "${PR_BASE:-}" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_./-]*$ ]] ||
        { printf 'PR_BRANCH and PR_BASE must identify the PR source/base branch.\n' >&2; exit 2; }
    [[ "$(git branch --show-current)" == "$PR_BRANCH" ]] ||
        { printf 'Check out PR_BRANCH before submitting analysis.\n' >&2; exit 2; }
    properties+=("-Dsonar.pullrequest.key=$pr" "-Dsonar.pullrequest.branch=$PR_BRANCH"
        "-Dsonar.pullrequest.base=$PR_BASE")
elif [[ -n "$pr" ]]; then
    printf 'Use sonar-pr to submit a PR analysis.\n' >&2
    exit 2
fi
bash "$root/scripts/maven.sh" clean verify -Pcoverage -Dskip.integration.tests=true \
    org.sonarsource.scanner.maven:sonar-maven-plugin:5.0.0.4389:sonar "${properties[@]}"
