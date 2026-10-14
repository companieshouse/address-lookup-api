# address-lookup-api
Provides address lookup and validation to be used by CHIPS and frontend CH services. Addresses to be validated using a live API provided by Ordinance Survey, with a cache in a Postgres database and a backup solution of data stored in the Postgres db.

## Building and Running Locally using Docker

1. Clone [Docker CHS Development](https://github.com/companieshouse/docker-chs-development) and follow the steps in the README.
2. Enable the service using the command `chs-dev services enable address-lookup-api`
3. Boot up the services' containers on docker using tilt `chs-dev up`.

## Building the docker image
```
mvn -pl address-lookup-api -am package -Dskip.unit.tests=true -Dskip.integration.tests=true jib:dockerBuild
```

## Running locally

The application uses Spring Boot Docker Compose support to start a local PostGIS
Postgres database from `compose.yaml`. The `local` profile uses
`db.changelog-local.yaml`, which creates and seeds the `add_gb_royalmailaddress`
table from `gb_royalmailaddress.csv`.

```bash
mvn -pl address-lookup-api spring-boot:run -Dspring-boot.run.profiles=local
```

Look up addresses by postcode:

```bash
curl "http://localhost:8080/address-lookup-api/addresses?postcode=WF2%207QD"
```

Look up addresses in the legacy response format:

```bash
curl "http://localhost:8080/address-lookup-api/multiple-addresses?postcode=WF2%207QD"
```

The local database is exposed on port `5433` to avoid clashing with any existing
Postgres running on the default `5432` port.

Without the `local` profile, Liquibase uses `db.changelog-master.yaml`, which
creates the schema without loading seed data. Provide `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` when connecting to
an externally hosted database.

Postcode lookups ignore spaces and letter case, so `WF2 7QD`, `WF27QD` and
`wf27qd` return the same addresses. The `/addresses` response echoes the postcode
exactly as it was requested.

## Database changes

- The four OS tables (`add_gb_royalmailaddress`, `add_gb_builtaddress`,
  `add_isl_royalmailaddress`, `add_isl_builtaddress`) live in the `os_data` schema.
  Lookups read the `address_lookup.address_lookup` view.
- Liquibase does not run at API startup unless `spring.liquibase.enabled=true`.
  The `local` profile and the integration tests enable it; the default is `false`
  because production schema changes use the dedicated migrator.
- Released changesets are never edited. Production changes are forward-only
  changesets under `db/changelog/changes/creation/`, included in
  `db.changelog-master.yaml`. Local seed data stays in the local changelog.
- `006-reconcile-os-data-tables.sql` creates `os_data` if needed and moves each
  address table from `public` into `os_data` only when the destination table does
  not already exist. Data is preserved and a rerun changes nothing.

## Testing

| Command                 | What it runs                                                                  |
|-------------------------|-------------------------------------------------------------------------------|
| `make test-unit`        | Unit tests                                                                    |
| `make test-integration` | All integration tests on PostGIS (Docker required) plus the row-count report  |
| `make test-liquibase`   | Liquibase validation: migration ITs that apply the real changelogs (Docker)   |
| `make test`             | All of the above                                                              |

`d2 als.integration.test [TestClass ...]` runs the integration tests, or only the
named classes, for example `d2 als.integration.test AddressesControllerIT`.

## Environment variables

| Variable              | Description                                                                | Example (from docker-chs-development) |
|-----------------------|----------------------------------------------------------------------------|---------------------------------------|
| PORT                  | The port at which the service is hosted in ECS.                            | 8080                                  |
| LOGLEVEL              | The level of log messages output to the logs.                              | debug                                 |
| HUMAN_LOG             | A boolean value to enable more readable log messages.                      | 1                                     |
| ADDRESS_LOOKUP_URL    | The URL of the elasticsearch 6 cluster.                                    | http://elasticsearch:4001             |
| DOCKER_COMPOSE_ENABLED | Whether Spring Boot should start local Docker Compose services.             | false                                 |
| SPRING_DATASOURCE_URL | JDBC URL for the address lookup Postgres database.                          | jdbc:postgresql://localhost:5433/address_lookup |
| SPRING_DATASOURCE_USERNAME | Username for the address lookup Postgres database.                    | postgres                              |
| SPRING_DATASOURCE_PASSWORD | Password for the address lookup Postgres database.                    | postgres                              |

## Building the docker image

```bash
make docker-image
```
