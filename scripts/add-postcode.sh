#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
seed_dir="$root/database/local"
for tool in curl jq cksum; do
    command -v "$tool" >/dev/null 2>&1 || {
        printf 'Required command is unavailable: %s\n' "$tool" >&2
        exit 1
    }
done
if [[ -n "${POSTCODE:-}" && -n "${POSTCODES:-}" ]]; then
    printf 'Use POSTCODE or POSTCODES, not both.\n' >&2
    exit 2
fi
input="${POSTCODES:-${POSTCODE:-}}"
if [[ "$input" != *'['* ]]; then
    input="$(jq -cn --arg value "$input" '$value | split(",")')"
fi
postcodes="$(printf '%s' "$input" | jq -ers '
    if length != 1 then error("Expected one array") else .[0] end
    | if type != "array" or length == 0 then error("Expected nonempty array") else . end
    | map(if type != "string" then error("Expected postcode strings")
          else ascii_upcase | gsub(" "; "") end)
    | if all(.[]; test("^[A-Z]{1,2}[0-9][A-Z0-9]?[0-9][A-Z]{2}$"))
      then unique[] else error("Invalid UK postcode") end
')"
lock="$seed_dir/.add-postcode.lock"
if ! mkdir "$lock"; then
    printf 'Postcode generation is locked; inspect %s before removing a stale lock.\n' "$lock" >&2
    exit 1
fi
work=""
cleanup() {
    if [[ -n "$work" ]]; then
        rm -f "$work/records" "$work/body" "$work/new"
        rmdir "$work"
    fi
    rmdir "$lock"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
work="$(mktemp -d "$seed_dir/.add-postcode.XXXXXX")"
printf '[]\n' > "$work/records"
added=0
names=()
while IFS= read -r postcode; do
    spaced="${postcode:0:${#postcode}-3} ${postcode: -3}"
    if grep -Fq "'$spaced'" "$seed_dir"/R__*.sql 2>/dev/null; then
        printf 'Already seeded: %s\n' "$postcode"
        continue
    fi
    historical=false
    status="$(curl --silent --show-error --connect-timeout 5 --max-time 30 \
        --output "$work/body" --write-out '%{http_code}' \
        "https://api.postcodes.io/postcodes/$postcode")"
    if [[ "$status" == 404 ]]; then
        historical=true
        status="$(curl --silent --show-error --connect-timeout 5 --max-time 30 \
            --output "$work/body" --write-out '%{http_code}' \
            "https://api.postcodes.io/terminated_postcodes/$postcode")"
    fi
    if [[ "$status" != 200 ]]; then
        printf 'Metadata lookup failed for %s: HTTP %s; seeds unchanged.\n' "$postcode" "$status" >&2
        exit 1
    fi
    record="$(jq -ce --arg postcode "$spaced" --argjson historical "$historical" '
        .result
        | ($postcode | startswith("BT")) as $island
        | if type != "object"
             or any(.latitude,.longitude; type != "number")
             or any(.latitude,.longitude; isfinite | not)
             or (if $island then false else
                 any(.eastings,.northings; type != "number")
                 or any(.eastings,.northings; isfinite | not)
                 or .eastings < 0 or .eastings >= 1000000
                 or .northings < 0 or .northings >= 10000000 end)
             or .latitude < -90 or .latitude > 90
             or .longitude < -180 or .longitude > 180
          then error("Missing postcode metadata") else . end
        | if (.country | type != "string" or length == 0)
          then if $historical then .country = null
               else error("Missing country metadata") end
          else . end
        | if (.admin_district | type != "string" or length == 0)
          then if $historical then .admin_district = "SYNTHETIC LOCALITY"
               else error("Missing district metadata") end
          else . end
        | .fixture_postcode = $postcode | .historical = $historical
        | .fixture_island = $island
        | if $island then .eastings = null | .northings = null else . end
    ' "$work/body")"
    checksum="$(printf '%s' "$postcode" | cksum)"
    identifier=$(( -(${checksum%% *} % 2147483647 + 1) ))
    if grep -Eq -- "(^|[^0-9])${identifier}([^0-9]|$)" "$seed_dir"/R__*.sql 2>/dev/null ||
        jq -e --argjson id "$identifier" 'any(.[]; .fixture_id == $id)' "$work/records" >/dev/null; then
        printf 'Synthetic identifier collision for %s; no seed file created.\n' "$postcode" >&2
        exit 1
    fi
    record="$(printf '%s' "$record" | jq -c --argjson id "$identifier" '.fixture_id = $id')"
    [[ "$historical" == false ]] || printf 'Historical postcode: %s; using terminated metadata.\n' "$postcode"
    records="$(jq -c --argjson record "$record" '. + [$record]' "$work/records")"
    printf '%s\n' "$records" > "$work/records"
    added=$((added + 1))
    names+=("$postcode")
done <<< "$postcodes"
if (( added == 0 )); then exit 0; fi
name="$(IFS=,; printf '%s' "${names[*]}")"
seed="$seed_dir/R__seed_${name}.sql"
if (( ${#name} > 243 )); then
    printf 'Postcode selection is too large for one seed filename; split it into smaller commands.\n' >&2
    exit 2
fi
release="local-generated-$name"
generated_date="$(date -u +%F)"
printf '%s\n' "-- Generated postcode fixtures: $(jq -c . "$work/records")" > "$work/new"
cat >> "$work/new" <<'SQL'
-- Synthetic addresses; metadata from https://api.postcodes.io.
-- District labels are not authoritative postal towns; UPRNs remain NULL.
SQL
printf "DELETE FROM os_data.add_gb_royalmailaddress_v2 WHERE release_id = '%s';\n" "$release" >> "$work/new"
printf "DELETE FROM os_data.add_isl_royalmailaddress_v2 WHERE release_id = '%s';\n" "$release" >> "$work/new"
jq -r --arg release "$release" --arg date "$generated_date" '
    def sql: if . == null then "NULL"
             else "'"'"'" + (tostring | gsub("'"'"'"; "'"'"''"'"'")) + "'"'"'" end;
    .[]
    | .fixture_postcode as $pc
    | "INSERT INTO os_data.add_\(if .fixture_island then "isl" else "gb" end)_royalmailaddress_v2 (
    udprn, versiondate, versionavailablefromdate, changetype, theme, description,
    buildingname, buildingnumber, thoroughfare, posttown, postcode, postcodetype,
    postcodearea, postcodedistrict, postcodesector, fulladdress, country,
    deliverypointsuffix, updatedate, entrydate, easting, northing, latitude,
    longitude, geometry, positionalaccuracy, geometryallocationmethod, matchtype, release_id
) VALUES (
    \(.fixture_id), DATE \($date | sql), TIMESTAMP \(($date + " 00:00:00") | sql),
    '"'"'New'"'"', '"'"'Address'"'"', '"'"'Synthetic local fixture'"'"', '"'"'SYNTHETIC TEST ADDRESS'"'"',
    1, '"'"'TEST FIXTURE ROAD'"'"', \(.admin_district[:30] | sql), \($pc | sql), '"'"'Small User'"'"',
    \($pc | capture("^(?<area>[A-Z]+)").area | sql), \($pc | split(" ")[0] | sql),
    \($pc[:-2] | sql), \(("SYNTHETIC TEST ADDRESS, 1 TEST FIXTURE ROAD, " + .admin_district + ", " + $pc) | sql),
    \(.country[:25] | sql), '"'"'1A'"'"', DATE \($date | sql), DATE \($date | sql),
    \(if .fixture_island then "NULL, NULL" else "\(.eastings), \(.northings)" end), \(.latitude), \(.longitude),
    \(if .fixture_island then "ST_SetSRID(ST_MakePoint(\(.longitude), \(.latitude)), 4258)"
      else "ST_SetSRID(ST_MakePoint(\(.eastings), \(.northings)), 27700)" end),
    '"'"'Postcode centroid'"'"', '"'"'Synthetic fixture'"'"', '"'"'Unmatched'"'"', \($release | sql)
);"
' "$work/records" >> "$work/new"
chmod 644 "$work/new"
if ! ln "$work/new" "$seed"; then
    printf 'Could not create %s; existing files were not overwritten.\n' "$seed" >&2
    exit 1
fi
printf 'Created %s with %s synthetic fixture(s). Run make test-postcode to apply and query them.\n' \
    "${seed#"$root/"}" "$added"
