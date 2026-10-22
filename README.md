# address-lookup-api

> [!WARNING]
> This repository has been archived by Team Photon due to inactive development

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

ALS-83 moves database migrations and local seed SQL to the repository-root
`database/` directory and removes the API module's Liquibase changelog
resources. API Flyway wiring is deferred to ALS-84, so the existing API `local`
profile is not yet connected to the new database feed. Do not rely on the
previous Liquibase-based API startup instructions until ALS-84 is implemented.
See [`database/README.md`](database/README.md) for the database feed layout and
conventions.

Production database packages are built from `database/migrations/` with
`make package-db-schema version=<version>`. The local-only seed files under
`database/local/` are not included.

Run the database feed layout checks and PostGIS integration test with
`make test-flyway`. Docker is required for the integration test.

Look up addresses by postcode:

```bash
curl "http://localhost:8080/address-lookup-api/addresses?postcode=WF2%207QD"
```

Look up addresses in the legacy response format:

```bash
curl "http://localhost:8080/address-lookup-api/multiple-addresses?postcode=WF2%207QD"
```

The local PostGIS database is exposed on port `5433` to avoid clashing with any
existing Postgres running on the default `5432` port. API database startup and
external datasource configuration remain part of the ALS-84 wiring work.

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
