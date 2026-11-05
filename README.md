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

## Java formatting tools

Use Spotless to tidy your changed Java files before committing. From the
repository root:

```bash
make tidy FILE=path/to/YourFile.java
make spotless-check
```

You can also pipe Java source with `cat foobar.java | make tidy` to print the
formatted source without editing the original. Java 21+ and Maven are required.
See [COMMANDS.md](COMMANDS.md#java-formatting-tools) for details.

The Spotless configuration in `pom.xml` is the single source of truth for
Java formatting. Editor integrations should invoke `make tidy FILE=...`
from the repository root rather than duplicate formatting rules in
EditorConfig or editor-specific formatter settings. This does not configure
automatic formatting on save. Shared editor actions invoke that same helper:
VS Code's **Spotless: format current Java file** task, an IntelliJ
**Spotless** External Tool, and Vim's
`:Spotless` command. See [EDITOR.md](EDITOR.md)
for activation and usage.

`make spotless-check` is an opt-in, read-only scan of all Java sources. It is
silent when clean and otherwise lists offending files. Existing Java sources
have not been reformatted as part of introducing these tools, so the scan may
report existing formatting differences. Review any formatting diff before
committing. Pipeline formatting enforcement is deferred; no PR-wide formatting
scan or workflow is added.

## Running locally

The API uses Flyway migrations from `classpath:db/migrations` when
`FLYWAY_ENABLED=true`; the base configuration defaults to disabled when the
variable is unset. The `local` profile explicitly enables Flyway and adds local
seed files under `classpath:db/local`. Non-local startup with the variable
unset does not create `flyway_schema_history`; environments that use a separate
migration runner should leave the API flag unset. The API package includes the
SQL feed from `database/` without copying or modifying those files.

The local GB Royal Mail seed includes 17 synthetic postcode fixtures for
regional lookup integration tests. Their postcode and country values are
owner-approved; all other address attributes are synthetic local test data.

From `CODE/`, start PostGIS and then the API with:

```bash
docker compose -f compose.yaml up -d postgres
mvn -pl address-lookup-api spring-boot:run -Dspring-boot.run.profiles=local
```

For an isolated startup and health check, run:

```bash
./scripts/local-api-smoke.sh
```

The helper requires Java, Maven, `curl`, and Docker Compose v2. It starts PostGIS
through `docker compose -f compose.yaml up -d --wait postgres`, creates a
uniquely named temporary database, builds the API with tests skipped, starts
the executable JAR directly with the local profile,
and waits up to 90 seconds for HTTP 200 from
`/address-lookup-api/healthcheck`. It also verifies that Flyway recorded at
least one migration in the temporary database. It then stops the API process
it started, drops only that temporary database, and verifies its removal. The
helper starts its API on an OS-assigned port and checks that specific endpoint,
so an existing development API can remain running on port 8080. The default
`address_lookup` database and `~/.pgpass` are not used or modified. Set
`SPRING_DATASOURCE_USERNAME` or `SPRING_DATASOURCE_PASSWORD` only when the
local Compose credentials have been changed. The health-check timeout and poll
interval can be set with `LOCAL_API_STARTUP_TIMEOUT_SECONDS` and
`LOCAL_API_HEALTH_POLL_INTERVAL_SECONDS`.

Production database packages are built from `database/migrations/` with
`make package-db-schema version=<version>`. The local-only seed files under
`database/local/` are not included.

Run the database feed layout checks and fresh-PostGIS integration test with
`make test-flyway`. Docker is required. API integration tests use Flyway with
both `classpath:db/migrations` and `classpath:db/local`.

Run the API unit and controller integration tests with:

```bash
mvn -pl address-lookup-api -Dtest=AddressLookupControllerTest test
mvn -pl address-lookup-api -am -Dit.test=AddressesControllerIT -Dskip.unit.tests=true test-compile failsafe:integration-test failsafe:verify
```

Look up addresses by postcode:

Postcode query values use uppercase letters and omit spaces; response values
retain their display formatting.

```bash
curl "http://localhost:8080/address-lookup-api/addresses?postcode=WF27QD"
```

Look up addresses in the legacy response format:

```bash
curl "http://localhost:8080/address-lookup-api/multiple-addresses?postcode=WF27QD"
```

The local PostGIS database is exposed on port `5433` to avoid clashing with any
existing Postgres running on the default `5432` port. Every environment must
provide a PostgreSQL datasource. Credentials must allow Flyway to apply
pending migrations wherever API startup is configured to run them. Local seed
files are not part of the production package.

## Sample HTTP requests

`rest-api.http` holds sample requests for the API and the Ordnance Survey download API. See
[docs/rest-api-http.md](docs/rest-api-http.md) for how to run it in IntelliJ with your own OS API key.

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
| FLYWAY_ENABLED | Enables Flyway in the base profile; unset defaults to false. The local profile enables it explicitly. | false |

## Building the docker image

```bash
make docker-image
```
