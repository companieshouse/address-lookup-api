#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
readme="$repo_root/database/README.md"

test -f "$readme"
test -d "$repo_root/database/migrations"
test -d "$repo_root/database/local"
test ! -e "$repo_root/database/provisional"
test ! -e "$repo_root/database/tests"
database_subdirectories="$(
    find "$repo_root/database" -mindepth 1 -maxdepth 1 -type d \
        -exec basename {} \; | sort | tr '\n' ' '
)"
test "$database_subdirectories" = "local migrations "
test -f "$repo_root/address-lookup-api/src/test/java/uk/gov/companieshouse/addresslookup/DatabaseFeedIT.java"
test ! -e "$repo_root/address-lookup-api/src/main/resources/db/changelog"
grep -Fq 'migrations/' "$readme"
grep -Fq 'local/' "$readme"
grep -Fq 'contains only these two subdirectories' "$readme"
grep -Fq 'V<version>__<description>.sql' "$readme"
grep -Fq 'R__<description>.sql' "$readme"
grep -Fq 'Never include this directory in a production' "$readme"
grep -Fq 'Never edit a migration after it has been released' "$readme"
grep -Fq 'db_schema_root      := database' "$repo_root/Makefile"
grep -Fq 'db_schema_contents  := migrations' "$repo_root/Makefile"
grep -Fq 'zip -X -r $(CURDIR)/$(db_schema_artifact)-$(version).zip $(db_schema_contents)' "$repo_root/Makefile"
grep -Fq '.PHONY: test-flyway' "$repo_root/Makefile"
grep -Fq 'flyway-database-postgresql' "$repo_root/address-lookup-api/pom.xml"
grep -Fq 'migratesAndSeedsFreshPostgisDatabaseRepeatably' "$repo_root/address-lookup-api/src/test/java/uk/gov/companieshouse/addresslookup/DatabaseFeedIT.java"
