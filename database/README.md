# Database migrations

This directory holds the database SQL feed separately from the API module.

## Directory layout

- `migrations/` contains versioned schema migrations required in every
  environment.
- `local/` contains repeatable fixture migrations for local development and
  API integration tests only. Never include this directory in a production
  database package.

The `database/` directory contains only these two subdirectories.

## Migration conventions

- Name versioned migrations `V<version>__<description>.sql` and local repeatable
  migrations `R__<description>.sql`.
- Put the Jira key and migration purpose in each versioned migration header
  before merge. The header is part of the migration checksum.
- Schema-qualify database objects and keep each versioned migration focused on
  one purpose.
- Put `CREATE INDEX CONCURRENTLY` in its own migration.
- Never edit a migration after it has been released. Make corrections in a new,
  forward-only migration.

## Local fixture conventions

- Keep local fixtures separate from the production migration location.
- Give local fixture rows a local release identifier and only replace rows
  belonging to that local identifier when a repeatable seed is reapplied.
- Generate geometry using the coordinate reference system and coordinate
  columns defined by the corresponding source dataset.

The API module Liquibase changelog has been removed for ALS-83. Spring/Maven
Flyway wiring is deferred to ALS-84. The production migration runner is managed
separately from this feed. Do not treat the presence of SQL here as proof that
the API is configured or that migrations have been deployed.

The two local repeatable seeds cover all ten current happy-path API postcode
fixtures. They deliberately retain `country = NULL` for GB UDPRN 19 and ISL
UDPRN 10403188, the two examples approved for this feed.

The `os_ingest.promote_full` procedure is not part of this initial feed phase.
Ian Mclaughlin clarified on 2026-10-13 that it is intended to promote a FULL
load to live in one transaction, but its design has not been finalized. The
procedure and any related view-copy changes are deferred to future import
Lambda work. `migrations/V2026.004__create_address_lookup_view.sql` creates
the feed's lookup view only; it does not implement release promotion. No local
view-copy is included because it duplicated the migration view without
implementing promotion. Revisit the authoritative procedure source, owner and
view synchronization when the import Lambda scope is defined.

`os_control.index_definition` currently records `dataset`, `table_name`,
`index_name`, `index_type`, and the key/expression definition. This is a
provisional contract authorized for the baseline; it has not been verified
against the future `promote_full` consumer. Reconcile it with that procedure
when import Lambda work is scoped; it is not a blocker for this initial feed.

Run the layout checks with `bash tests/test_database_layout.sh`.
Run the fresh-PostGIS Flyway integration test with `make test-flyway` from the
repository root; Docker is required.
