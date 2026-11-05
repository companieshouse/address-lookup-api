# SPIKE: Indirect database artefact management

## Outcome and scope

PostgreSQL stored procedures can create the required tables, primary keys,
secondary indexes and lookup view. Flyway can install those procedures, and Java
can invoke them sequentially using JDBC `CALL` statements.

The recommended separation is:

- Flyway owns the versioned definitions of the procedures.
- A data-sync worker owns when the procedures run, around the initial FULL load.
- The database enforces primary-key uniqueness and creates the requested artefacts.

This branch retains an experimental local implementation alongside the
investigation; it is not a production implementation. Migration
`V2026.005__manage_royalmailaddress_artefacts.sql`, Java invocation wiring and
local tests are included for experimentation.
Deployment, production-sized load timings and end-to-end AWS execution remain
unverified.

## Concepts

| Term | Meaning in this spike |
|------|-----------------------|
| Table | Stores the address rows. An index-less table still has columns, types and applicable `NOT NULL` constraints. |
| Primary key | Requires every UDPRN to be present and unique within its table. PostgreSQL creates a unique index to enforce it. |
| Secondary index | An additional structure that can speed up filtering, at the cost of storage and write-time maintenance. |
| View | A saved query over other relations. A normal view does not store a separate copy of the address data. |
| Stored procedure | Named database-side instructions, invoked with `CALL`, which can perform DDL such as creating tables and indexes. |
| Flyway migration | A numbered SQL change applied in order and recorded in `flyway_schema_history`. Installing a procedure does not execute its body. |

Building indexes after bulk loading avoids maintaining those indexes for every
inserted row. The benefit and total completion time must be measured with
representative data; the local investigation did not quantify either.

## Existing repository design

| Evidence | Finding |
|----------|---------|
| `database/migrations/V2026.001__create_postgis_and_schemas.sql` | Creates PostGIS and the database schemas, including `os_data`, `os_ingest` and `address_lookup`. |
| `database/migrations/V2026.002__create_dataset_contract.sql` | Registers the two datasets and provisional primary-key/postcode index metadata. No thoroughfare index is registered. |
| `database/migrations/V2026.003__create_royalmailaddress_v2_tables.sql` | Creates both physical `_v2` tables with UDPRN primary keys and normalised-postcode indexes during migration. |
| `database/migrations/V2026.004__create_address_lookup_view.sql` | Creates `address_lookup.address_lookup` using `UNION ALL` over GB and ISL, with source-prefixed IDs. |
| `release-model/src/main/java/uk/gov/companieshouse/release/model/Dataset.java` | Defines separate FULL and COU landing tables and preparation procedure calls. |
| `release-core/src/main/java/uk/gov/companieshouse/addresslookup/releasecore/aurora/AuroraOperations.java` | Already invokes ingestion and promotion procedures using `JdbcTemplate`. |
| `release-core/src/main/java/uk/gov/companieshouse/addresslookup/releasecore/service/PromotionService.java` | Checks release readiness before calling promotion, within a transaction that locks the watermark and release. |
| `database/README.md` | States that the `os_ingest.promote_full` procedure is deferred and not supplied by this migration feed. |

The current feed therefore does not provide an index-less initial-load lifecycle.
Simply adding procedures that skip existing tables would not change that: the
baseline migrations have already created indexed tables.

The ticket calls the datasets `add_gb_royalmailaddress` and
`add_isl_royalmailaddress`; their current physical tables are
`os_data.add_gb_royalmailaddress_v2` and
`os_data.add_isl_royalmailaddress_v2`. Confirm whether the acceptance criteria
require literal unsuffixed table names or refer to those datasets.

The ticket's union description repeats the ISL name. This investigation assumes
it means GB plus ISL, consistent with the final criterion and existing view.

## Proposed procedure contract

The experimental migration installs the interfaces below. Fixed dataset-specific
procedures avoid accepting arbitrary table names or SQL from an invocation.

| Proposed procedure in `os_ingest` | Responsibility |
|----------------------------------|----------------|
| `create_gb_royalmailaddress_table()` | Create an index-less GB table with the agreed v2 columns and PostGIS SRID 27700. |
| `create_isl_royalmailaddress_table()` | Create an index-less ISL table with the agreed v2 columns and PostGIS SRID 4258. |
| `add_gb_royalmailaddress_primary_index()` | Add the GB UDPRN primary-key constraint and backing unique index. |
| `add_isl_royalmailaddress_primary_index()` | Add the ISL UDPRN primary-key constraint and backing unique index. |
| `add_gb_royalmailaddress_secondary_indexes()` | Add GB postcode and thoroughfare indexes. |
| `add_isl_royalmailaddress_secondary_indexes()` | Add ISL postcode and thoroughfare indexes. |
| `create_address_lookup_view()` | Create or replace the agreed phase 1 GB/ISL union view. |

Preserve the current postcode expression,
`upper(replace(postcode, ' ', ''))`, to match the API query. A plain index on
`thoroughfare` meets the stated column requirement, but the intended search
predicate must be agreed before selecting its expression and index type.

Use the existing view's explicit projection, `UNION ALL`, `address_source` and
`GB-`/`ISL-` ID prefixes unless the phase 1 contract says otherwise. `UNION ALL`
retains rows from both datasets without a deduplication pass.

A view depends on its underlying tables and columns, not on their indexes.
Creating it after indexing is a publication/readiness choice, not a PostgreSQL
dependency requirement.

## Recommended execution sequence

1. Deploy the procedure definitions through a new, forward-only Flyway migration.
   Do not modify released migrations or automatically remove live indexes.
2. Create new, isolated index-less load tables for both datasets.
3. Load the initial FULL data and verify completion, expected row counts and
   schema compatibility.
4. Add both UDPRN primary keys. Duplicate or null keys must fail the operation
   and prevent publication.
5. Add postcode and thoroughfare indexes to both datasets and update planner
   statistics with `ANALYZE`.
6. Publish the GB/ISL view, or perform the agreed promotion/switch to those tables,
   only when both datasets and all required indexes are ready.
7. Record durable completion and expose the data to the API.

The new-table naming and promotion mechanism need an agreed design. If FULL data
is loaded into staging tables and those tables are renamed or swapped into place,
indexes must be created on those loaded tables, not merely on the old live tables.
PostgreSQL views remain bound to table objects across renames; a table swap alone
does not make the existing view read the replacement table.

Keep the long-running load/index steps separate from a short atomic publication
transaction where possible. Wrapping them all in the existing promotion
transaction would hold its locks for the duration of index creation.

## Programmatic invocation and worker choice

The existing Java/JDBC pattern can execute a fixed `CALL` for each step. Failures
must propagate to the worker and prevent later steps; successful durable steps
should be resumable after a retry.

ECS is the safer provisional choice for a production-sized initial FULL load
because Lambda has a maximum execution duration of 15 minutes. Lambda remains
suitable for a bounded demonstration or an individual step proven to finish
within that limit. Measure the slowest index build before making the final
runtime decision; an unmeasured timeout increase is not evidence of suitability.

Normal `CREATE INDEX` blocks writes to its table, which is usually acceptable for
an unpublished load table. Concurrent index creation has different transaction
restrictions, cannot simply be substituted into the existing transactional
promotion path, and needs its own execution and recovery design.

Before deployment, verify the worker's actual database connection, VPC access,
credentials and Spring wiring. The presence of an Import Lambda handler and JDBC
methods is not proof of a functioning deployed end-to-end import.

## Reliability and permissions

- Retry safety must verify the definition of existing tables, primary keys and
  indexes. `IF NOT EXISTS` alone can silently accept an incompatible object.
- Serialise overlapping initial-load runs and persist the release/step state.
  Do not rely solely on in-memory execution order.
- Propagate duplicate-key, DDL and connection failures with the failing step and
  release identifier. Never report a partially completed sequence as successful.
- Agree transaction boundaries so failed publication cannot expose only one
  dataset, and interrupted index builds can be detected and resumed.
- Use a dedicated migration owner and a restricted worker role. Invoker-rights
  procedures require the appropriate schema/object privileges; `EXECUTE` alone
  does not grant ownership rights needed to alter a table.
- If owner-rights (`SECURITY DEFINER`) procedures are required, restrict execution,
  fix the search path and schema-qualify objects. This needs explicit review.
- Reconcile the provisional `os_control.index_definition` contract with the
  eventual promotion implementation and add thoroughfare metadata if it remains
  authoritative.

## Experimental implementation and evidence

The experimental FULL promotion path creates any missing live tables before
calling `os_ingest.promote_full`, then adds the indexes and refreshes the view
within the existing promotion transaction. COU promotion is unchanged.

This demonstrates invocation wiring, not a complete initial-load lifecycle.
Baseline migrations still create indexed live tables. The migration does not
drop those tables or indexes; the integration test recreates them only inside
its disposable database to exercise index-less table creation. The externally
managed `promote_full` procedure remains absent from this feed.

The prototype uses existence checks to tolerate retries in the expected schema;
it does not reject all incompatible existing definitions or serialise concurrent
DDL calls. These and the long-held promotion locks remain limitations to resolve
before production use.

The local prototype demonstrated the following against a disposable
PostGIS PostgreSQL 18 database:

- Flyway could install the seven procedures.
- Procedures could recreate both empty v2 tables without indexes, then add UDPRN
  primary keys, postcode/thoroughfare indexes and the union view.
- Repeating the procedure sequence completed without errors in the expected
  schema.
- Mock-based Java tests could verify FULL-only procedure call ordering.

These observations establish basic feasibility, not production readiness.
The prototype did not demonstrate loading full datasets without indexes, building
indexes over representative volumes, invocation from a deployed Lambda/ECS task,
least-privilege execution, recovery from partial failure or the real
`os_ingest.promote_full` implementation.

The baseline `mvn clean install` was also reproduced separately and failed on
existing fixture expectations: address counts, country values and null-country
assumptions no longer match the source-accurate local seeds. The branch retains
test-expectation fixes for those fixtures without changing generated seed data,
so the experiment can be built with `mvn clean install`.

To exercise the experiment locally with Docker running:

```sh
make test-flyway
mvn -pl release-core -am -Dtest=PromotionServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The first command exercises PostgreSQL DDL and the resulting artefacts in a
disposable database; the second verifies Java invocation order using mocks.
Neither invokes a deployed AWS worker or proves the real FULL promotion.

## Follow-on acceptance evidence

A future implementation should be demonstrated in an isolated database:

1. Apply Flyway and show installed procedure signatures and migration history.
2. Invoke the table procedures from the chosen worker and show both tables have
   zero indexes before loading.
3. Load representative GB/ISL rows, including a deliberate duplicate-UDPRN failure
   case, then prove primary-key enforcement after successful index creation.
4. Inspect `pg_constraint`, `pg_indexes` and `pg_index` for the expected valid
   primary keys and secondary-index definitions.
5. Query the union view to prove the agreed projection, both sources, correct
   row counts and source-prefixed IDs.
6. Repeat and interrupt the workflow to demonstrate safe retry, incompatible
   object rejection and no partial publication.
7. Capture index-build timings, resource usage and database locks on representative
   volumes, then confirm the worker runtime and timeout choice.

## Decisions required before implementation

- Confirm dataset names versus physical table names and the GB/ISL union wording.
- Confirm the authoritative phase 1 view contract and thoroughfare query pattern.
- Agree load-table ownership/naming, initial-load detection and publication or
  promotion behaviour without changing existing populated environments.
- Identify the owner and implementation of `os_ingest.promote_full`.
- Confirm runtime, database privileges, transaction boundaries and retry policy.

The spike supports proceeding with Flyway-managed stored procedures and
programmatic JDBC invocation. It does not claim the ticket's deployed-artefact
acceptance criteria have been fulfilled; those require a separately scoped
implementation and environment demonstration.
