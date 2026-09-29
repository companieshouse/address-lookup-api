# Database changes

The Liquibase changelogs for the `addressdb` Aurora PostgreSQL database live in
[`address-lookup-api/src/main/resources/db/changelog`](../address-lookup-api/src/main/resources/db/changelog).
A merge to `main` that changes them is the only route by which the Aurora
schema changes.

```
 developer / data architect
          |  pull request, review, merge to main
          v
       GitHub  ----------------------------------------------+
          |                                                  |
          v                                                  |
      Concourse (address-lookup-api pipeline)                |
          | 1. read: db-schema-release checks out db/changelog,
          |    zips it and tags db-schema-X.Y.Z
          | 2. write: s3 put of address-lookup-db-schema-X.Y.Z.zip
          |    to the release bucket (development account)
          | 3. invoke: VALIDATE -> UPDATE -> STATUS
          v                                                  |
  address-lookup-api-liquibase-<env> (Lambda)                |
          | 4. read DB credentials from Parameter Store (fed from Vault)
          | 5. read the released changelog from S3, verify its SHA-256
          | 6. Liquibase update
          v
   Aurora PostgreSQL  (DATABASECHANGELOG is the history)
```

Nobody connects to the database to change it. The service itself runs with
`spring.liquibase.enabled=false`; only `lambdas/liquibase-schema-migrator` applies these
changelogs to Aurora, and only when the pipeline invokes it with a released
version.

## Layout

All paths are under `address-lookup-api/src/main/resources/db/changelog/`.

| Path | Shipped to Aurora | Triggers a release | Purpose |
| ---- | ----------------- | ------------------ | ------- |
| `db.changelog-master.yaml` | yes | yes | The changelog the Lambda runs |
| `changes/creation/` | yes | yes | Schema changesets included by the master changelog |
| `version` | – | yes | Major.minor for the `db-schema-X.Y.Z` tag stream (`db-schema-1.0`) |
| `db.changelog-local.yaml` | no | no | Master plus local seed data, for `local` runs and the service's tests |
| `changes/seed/`, `data/` | no | no | Local seed data |

The service loads the same files from its classpath for the `local` profile
and its tests, at the same `db/changelog/...` paths that Liquibase records in
`DATABASECHANGELOG.FILENAME`. Keep those paths stable: moving a file makes
Liquibase treat every changeset in it as new.

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
   packaged changelog to PostGIS with the real Lambda code, including the
   PostGIS-present and PostGIS-absent paths. Concourse runs it too, in
   `build-test-integration`.
5. Bump `version` for a breaking change, otherwise leave it: the pipeline
   calculates the patch number.

Merging to `main` releases and applies the change to cidev.

## Pipeline

In `companieshouse/ci-pipelines`, `pipelines/ssplatform/team-development/address-lookup-api`:

| Job | Trigger | Does |
| --- | ------- | ---- |
| `db-schema-release` | Merge to `main` touching the shipped files above | Calculates `db-schema-X.Y.Z`, runs `make package-db-schema`, writes the zip to the release bucket (`s3` resource `put`) and creates the GitHub release |
| `cidev-db-schema-migrate` | Each new `db-schema` release, or by hand | Invokes the Lambda with `VALIDATE` (the pending SQL is printed in the build log), then `UPDATE` (repeated while it returns `PARTIAL`), then `STATUS`, which must be `UP_TO_DATE` |
| `cidev-db-schema-release-locks` | By hand only | Break-glass. Clears a Liquibase lock left by an invocation that was killed |

To run a migration by hand, trigger `cidev-db-schema-migrate` from the Concourse
UI or with `fly -t <target> trigger-job -j address-lookup-api/cidev-db-schema-migrate`.
It migrates the latest release; pin an older `s3-db-schema-release` version in
the UI to re-run that one. Nobody needs database or AWS console access.

The Lambda itself is released on the `lambda-X.Y.Z` tag stream
(`lambda-release`) and deployed by `cidev-lambda-plan/apply`; see
[`terraform/groups/liquibase-lambda`](../terraform/groups/liquibase-lambda/README.md).

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
