#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
psql=(docker compose -f "$root/compose.yaml" exec -T postgres
    psql -X -U postgres -d address_lookup -v ON_ERROR_STOP=1)

if [[ -n "${SQL:-}" ]]; then
    exec "${psql[@]}" --command "$SQL"
fi
if [[ -t 0 ]]; then
    printf 'Supply SQL with make db-exec SQL="SELECT ..." or redirect a SQL file to stdin.\n' >&2
    exit 2
fi
exec "${psql[@]}" --file -
