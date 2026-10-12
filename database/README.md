# Liquibase database feed

ALS-13 makes the database a first-class component. The canonical layout is:

```text
database/
  changelog-master.xml
  version
  production.properties
  staging.properties
  cidev.properties
  local.properties
  changelogs/
    2026-001-create-address-tables.postgres.sql
    2026-002-create-os-control-schema.postgres.sql
    2026-003-create-os-data-schema-and-tables.postgres.sql
    2026-004-create-os-stage-schema-and-tables.postgres.sql
    2026-005-create-address-lookup-view.postgres.sql
    2026-006-create-local-seed-geometry-trigger.postgres.sql
    2026-007-seed-gb-royalmailaddress.yaml
    2026-008-seed-gb-builtaddress.yaml
    2026-009-seed-isl-royalmailaddress.yaml
    2026-010-seed-isl-builtaddress.yaml
  test-data/
    gb_royalmailaddress.csv
    gb_builtaddress.csv
    isl_royalmailaddress.csv
    isl_builtaddress.csv
```

The enclosing paths are implementation choices from the exported Jira
requirements; the linked Confluence design has not been independently verified.
Flyway is deferred and is not part of this implementation.

## Fresh-database contract

`version` is `2.0.0`. The paths, authors and IDs intentionally differ from the
original feed: changeset IDs are `2026-NNN:NN`, authored by `team-atlas`.
Liquibase treats these as new identities. **Do not apply this feed to an
existing database or rewrite its DATABASECHANGELOG history.** It is for
fresh, disposable databases, as confirmed for ALS-13.

The source under `address-lookup-api/src/main/resources/db/changelog/` is the
immutable original snapshot associated with `db-schema-1.0.0`, including its
fixture copies. It remains unchanged rather than deleting or rewriting
released source. Maven excludes that directory from the active API resources.
New work uses `database/`; do not edit the historical snapshot.

The XML master discovers the flat changelogs in lexical order using relative
`includeAll`. Groups 001-005 contain 32 schema changesets. Group 005 also
provides `public.royalmail_address_lookup` as an alias of
`address_lookup.address_lookup` for the existing native API query. Groups
006-010 contain ten local-only changesets.

All changelogs have Jira, description, author and creation-date headers.
Every changeset has a comment and an explicit rollback. Existing groups are
retained for traceability; optional squashing of early schema churn is not
performed. Once this new feed is released, preserve its paths and identities
and make subsequent schema changes forward-only.

## Spring configuration and local data

`address-lookup-api/src/main/resources/application.yaml` explicitly imports
the relocated properties. Default/production, staging and cidev disable
Liquibase in the API: deployment applies schema separately. Their context
filter is `!local`. Local enables Liquibase and selects `local`.

Groups 006-010 use `@local`, which requires explicit selection even when a
Liquibase caller supplies no context filter. Fixture paths are relative to
the seed changelog, not to the shell working directory. The four data loads
require empty target tables and fail rather than silently skipping existing
data.

| Table in os_data | Fixture rows | Geometry SRID |
| --- | ---: | ---: |
| add_gb_royalmailaddress_v1 | 99 | 27700 |
| add_gb_builtaddress_v3 | 794 | 27700 |
| add_isl_royalmailaddress_v1 | 126 | 4258 |
| add_isl_builtaddress_v3 | 111 | 4258 |

The lookup view contains 225 Royal Mail rows. Local insert triggers calculate
geometry from GB eastings/northings and ISL longitude/latitude.

From the repository root, with Docker available:

```sh
mvn -pl address-lookup-api spring-boot:run -Dspring-boot.run.profiles=local
curl "http://localhost:8080/address-lookup-api/addresses?postcode=WF2%207QD"
```

Docker Compose support supplies local PostGIS on port 5433. Use a fresh local
database for this feed; a volume initialized with the old identities is not
compatible. Do not delete or reset a shared database as part of this refactor.

## Validation and rollback

```sh
make test-liquibase
mvn -pl address-lookup-api test -Dtest=LiquibaseFeedTest
mvn -pl address-lookup-api verify -Dskip.unit.tests=true -Dit.test=LiquibaseFeedIT
mvn -pl address-lookup-api verify -Dskip.unit.tests=true -Dit.test=AddressesControllerIT
```

The unit suite verifies ordered discovery, metadata, rollback declarations,
fixture resources and actual Spring Config Data loading for every profile.
The PostGIS suite validates and applies all 42 local changesets, checks every
fixture count and geometry SRID, proves repeat application is a no-op, rolls
back the full feed and reapplies the seed-free schema. It also invokes the
real Makefile packaging target and applies/rolls back the ZIP directly.
An isolated packaging fixture adds a synthetic production group 011 and
verifies that the archive discovers it without reintroducing local groups.
Inspect `address-lookup-api/target/failsafe-reports/` for nonzero executed test
counts; a successful Maven command without IT reports is not integration
evidence.

Rollback is destructive and is validated only on disposable containers.
Seed rollback deletes all rows in the four local fixture tables, not just
their original row IDs. Never use it after importing real address data.
Schema rollback drops feed-owned tables, views, functions and schemas.
PostGIS is deliberately retained because the extension may predate the feed
and may be shared. The full test proves no feed-owned objects remain.

## Production artifact and DevOps handoff

```sh
make package-db-schema version=2.0.0
```

The resulting `address-lookup-db-schema-2.0.0.zip` contains ten entries:
the master, version, three nonlocal properties files and SQL groups 001-005.
It does not contain local properties, group 006, seed YAMLs or CSVs. The
same relative `database/changelog-master.xml` entry point works directly
inside the archive. Even an explicit `local` context cannot load fixtures
from that artifact.

The Makefile discovers production SQL/YAML/XML changelogs rather than limiting
the archive to groups 001-005. The current local groups 006-010 are explicitly
excluded. Future production files are included automatically; any new
local-only changelog must also be added to the artifact's exclusion list.

Concourse/deployment changes are delegated externally for ALS-13. The handoff
paths are `database/changelog-master.xml`, `database/{production,staging,cidev}.properties`
and `database/version`; ZIP entries retain the `database/` prefix. Keep the
API's nonlocal migration switch disabled. These properties are Spring
configuration, not a Liquibase CLI defaults file: the pipeline must configure
its own JDBC connection and resource accessor/master path.
The Concourse pipeline and schema migrator are not present as runnable
modules in this checkout; their deployment has not been verified here.
