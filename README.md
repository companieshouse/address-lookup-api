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

The changelogs live in [`db-schema/`](db-schema/README.md). Without the `local`
profile the service does not run Liquibase at all: Aurora is migrated only by
the schema migrator Lambda (`lambdas/schema-migrator`) from a released
`db-schema` artefact. Provide `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` when connecting to
an externally hosted database.

## Environment variables

| Variable              | Description                                                                | Example (from docker-chs-development) |
|-----------------------|----------------------------------------------------------------------------|---------------------------------------|
| PORT                  | The port at which the service is hosted in ECS.                            | 8080                                  |
| LOGLEVEL              | The level of log messages output to the logs.                              | debug                                 |
| HUMAN_LOG             | A boolean value to enable more readable log messages.                      | 1                                     |
| ADDRESS_LOOKUP_URL    | The URL of the elasticsearch 6 cluster.                                    | http://elasticsearch:4001             |
| DOCKER_COMPOSE_ENABLED | Whether Spring Boot should start local Docker Compose services.             | false                                 |
| SPRING_DATASOURCE_URL | JDBC URL for the address lookup Postgres database.                          | jdbc:postgresql://localhost:5433/address_lookup | <!-- trufflehog:ignore -->
| SPRING_DATASOURCE_USERNAME | Username for the address lookup Postgres database.                    | postgres                              |
| SPRING_DATASOURCE_PASSWORD | Password for the address lookup Postgres database.                    | postgres                              |

## Building the docker image

```bash
make docker-image
```
