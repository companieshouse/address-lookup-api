# db-schema

The Liquibase changelogs for the `addressdb` Aurora PostgreSQL database, and
the only route by which its schema changes.

```
 developer / data architect
          |  pull request, review, merge to main
          v
       GitHub  ----------------------------------------------+
          |                                                  |
          v                                                  |
      Concourse (address-lookup-api pipeline)                |
          | 1. db-schema-release: zip changelogs, tag db-schema-X.Y.Z
          | 2. put address-lookup-db-schema-X.Y.Z.zip in the release bucket
          | 3. invoke the schema migrator: VALIDATE -> UPDATE -> STATUS
          v                                                  |
  address-lookup-schema-migrator-<env> (Lambda)              |
          | 4. read DB credentials from Parameter Store (fed from Vault)
          | 5. read the released changelog from S3, verify its SHA-256
          | 6. Liquibase update
          v
   Aurora PostgreSQL  (DATABASECHANGELOG is the history)
```

Nobody connects to the database to change it. The service itself runs with
`spring.liquibase.enabled=false`; only `lambdas/schema-migrator` applies these
changelogs to Aurora, and only when the pipeline invokes it with a released
version.

## Layout

| Path | Shipped to Aurora | Purpose |
| ---- | ----------------- | ------- |
| `src/main/resources/db/changelog/db.changelog-master.yaml` | yes | The changelog the migrator runs |
| `src/main/resources/db/changelog/changes/creation/` | yes | Schema changesets included by the master changelog |
| `src/main/resources/db/changelog/db.changelog-local.yaml` | no | Master plus local seed data, for `local` runs and the service's tests |
| `src/main/resources/db/changelog/changes/seed/`, `data/` | no | Local seed data |
| `version` | – | Major.minor for the `db-schema-X.Y.Z` tag stream (`db-schema-1.0`) |

The Maven module packages the same files as a jar so that the service's local
profile and tests load them from the classpath, at the same `db/changelog/...`
paths that Liquibase records in `DATABASECHANGELOG.FILENAME`. Keep those paths
stable: moving a file makes Liquibase treat every changeset in it as new.

`make package-db-schema version=X.Y.Z` builds `address-lookup-db-schema-X.Y.Z.zip`
containing only the master changelog and `changes/creation/`.

## PostGIS

`changes/creation/000-create-postgis-extension.sql` creates PostGIS
`${postgis.version}` (3.6.1, set in the master changelog: the version Aurora
PostgreSQL 18.3 ships) **only if no PostGIS is installed**. Its precondition
marks it `MARK_RAN` on any database that already has the extension, so it is
safe on databases created before it existed, and it never silently installs a
different version: if 3.6.1 is unavailable the migration fails.

It is excluded from the `local` context, because the `postgis/postgis:18-3.6`
image can only install its latest patch release. Locally, `001` creates the
image's default version.

## Making a schema change

1. Add a new file under `changes/creation/` (next number) and include it from
   `db.changelog-master.yaml`. Use formatted SQL with an explicit
   `--changeset address-lookup-api:<id>`, and a `--rollback` where one is safe.
2. **Never edit a changeset that has been released.** Liquibase stores a
   checksum per changeset, and the migrator's `VALIDATE` fails on a mismatch.
   Fix forward with a new changeset. The migrator deliberately has no
   `clearCheckSums` mode.
3. Do not start comment lines with `-- changeset`, `-- precondition` or
   `-- rollback`: Liquibase parses them as directives.
4. Run `make test-integration` (needs Docker). `SchemaMigratorIT` applies the
   packaged changelog to PostGIS with the real migrator, including the
   PostGIS-present and PostGIS-absent paths.
5. Bump `version` for a breaking change, otherwise leave it: the pipeline
   calculates the patch number.

Merging to `main` releases and applies the change to cidev.

## Pipeline

In `companieshouse/ci-pipelines`, `pipelines/ssplatform/team-development/address-lookup-api`:

| Job | Does |
| --- | ---- |
| `db-schema-release` | Triggered by `db-schema/*` on `main`. Calculates `db-schema-X.Y.Z`, runs `make package-db-schema`, puts the zip in the release bucket and creates the GitHub release |
| `cidev-db-schema-migrate` | Triggered by a new release. Invokes the migrator with `VALIDATE` (the pending SQL is printed in the build log), then `UPDATE` (repeated while it returns `PARTIAL`), then `STATUS`, which must be `UP_TO_DATE` |
| `cidev-db-schema-release-locks` | Manual, break-glass. Clears a Liquibase lock left by an invocation that was killed |

The migrator function itself is released on the `lambda-X.Y.Z` tag stream
(`lambda-release`) and deployed by `cidev-lambda-plan/apply`; see
[`terraform/groups/lambda`](../terraform/groups/lambda/README.md).

## Invoking the migrator

```json
{"mode": "VALIDATE", "version": "1.0.3", "sha256": "<sha256 of address-lookup-db-schema-1.0.3.zip>"}
```

| Mode | Result `status` | Notes |
| ---- | --------------- | ----- |
| `VALIDATE` | `VALIDATED` | Checks checksums, lists `pendingChangeSets`, returns the SQL in `sql`. Changes nothing except creating the Liquibase tables on an empty database |
| `UPDATE` | `COMPLETE` / `PARTIAL` | Applies changesets one at a time; stops with `PARTIAL` when under `MIN_REMAINING_MILLIS` remain. Tags the database `db-schema-X.Y.Z` when it applied anything |
| `STATUS` | `UP_TO_DATE` / `PENDING` | Verification after `UPDATE` |
| `RELEASE_LOCKS` | `LOCKS_RELEASED` | Break-glass only |

Every result includes `postgisVersion`. Any failure is raised as a Lambda
`FunctionError`, which fails the pipeline task.

## Runbook

* **`VALIDATE` fails with a checksum error**: a released changeset was edited.
  Revert the edit and add a new changeset instead.
* **`UPDATE` fails with "Could not acquire change log lock"**: a previous
  invocation was killed. Check the function's logs to confirm nothing is
  running, then trigger `cidev-db-schema-release-locks` and re-run the migrate
  job.
* **A changeset fails half way**: PostgreSQL DDL is transactional, so the
  failed changeset has been rolled back and is still pending. Fix forward with
  a new release.
