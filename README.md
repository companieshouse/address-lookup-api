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

Local postcode seeds contain all 266 real OS records for 13 Cardiff postcodes
and `KT13 8GL`, from v2 package `24833`, FULL version `150450`.
`CF10 1PX` is absent from that source and is tested as a zero-result case.
The legacy filename `database/local/R__seed_synthetic_cardiff_addresses.sql`
and its release marker are retained so Flyway replaces previously applied
synthetic rows; its contents now preserve actual source fields, IDs and geometry.
Simon's existing corrected GB/ISL seeds remain unchanged. All local fixtures
remain excluded from the production migration package.

```bash
make download-os-addresses OS_FULL_VERSION=150450
make add-postcode POSTCODE=KT138GL
make refresh-seed-data OS_SOURCE_DIR=~/os-data/24833/150450
```

Downloads require `OS_API_KEY` in the environment. The helper selects FULL,
not daily COU, checks archive MD5/length and primary CSV CRC, and caches
sources outside the repository. Generation requires Java 21+, Maven, curl
and jq, uses a CSV-safe Java helper behind Bash, and never invents missing
addresses. Source CSVs are large (GB about 13.3 GB); allow temporary disk space.
See [COMMANDS.md](COMMANDS.md#download-and-seed-real-os-address-records) for
cache/source inputs, refresh behavior, locking and safe local application.

The API uses Flyway migrations from `classpath:db/migrations` when
`FLYWAY_ENABLED=true`; the base configuration defaults to disabled when the
variable is unset. The `local` profile explicitly enables Flyway and adds local
seed files under `classpath:db/local`. Non-local startup with the variable
unset does not create `flyway_schema_history`; environments that use a separate
migration runner should leave the API flag unset. The API package includes the
SQL feed from `database/` without copying or modifying those files.

The existing GB/ISL regional lookup fixtures use source-accurate OS records,
retaining all addresses for their selected postcodes.

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

Integration tests (classes named `*IT`, e.g. `MultipleAddressesControllerIT`) are
run by Failsafe, not Surefire. `mvn test` does **not** run them, so a green
`mvn test` can hide failing ITs; the pipeline runs them with `mvn verify`. Run
`mvn clean verify` (with local PostGIS running) before pushing; `clean` avoids
stale compiled classes hiding failures.

Run the API unit and controller integration tests with:

```bash
mvn clean verify
mvn -pl address-lookup-api -Dtest=AddressLookupControllerTest test
mvn -pl address-lookup-api -am -Dit.test=AddressesControllerIT -Dskip.unit.tests=true -Dfailsafe.failIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false verify
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

### Optional OS Places fallback

The API normally remains database-only. To look up postcodes missing from
the CSV-backed database, explicitly enable fallback and provide `OS_API_KEY`
in the environment:

```bash
make api-restart OS_PLACES_FALLBACK_ENABLED=true
```

For initial startup of PostGIS and the API together, use
`make start OS_PLACES_FALLBACK_ENABLED=true`.

Database results always take precedence and are never supplemented or
overwritten by OS results. Only an empty successful database lookup invokes
Places, using English `DPA` delivery addresses, not `LPI` property records.
Every page is read and duplicate UDPRNs are deduplicated. Existing
`/addresses`, `/multiple-addresses` and `/postcode` formats remain unchanged;
raw OS payloads are never returned or saved as FULL CSV rows.
`CF14 3AT` retains its seven CSV records. `CF10 1PX` currently has no English
DPA result either, so it remains empty (404 for `/postcode`).

Fallback defaults to disabled in every profile, including `local`. Enabling
it adds a live OS dependency for missing postcodes and requires valid
credentials at startup. Upstream errors, incomplete pages and invalid data
return a sanitized 502 rather than a misleading successful empty response.
Requests use HTTPS with no redirects, a 3-second connect timeout and a
5-second per-page read timeout; responses above 10,000 entries are rejected.

The managed lifecycle script already builds the local-profile JAR and
checks readiness. On macOS it uses the trusted system keychain for the API
when fallback is enabled, unless `JAVA_TOOL_OPTIONS` or `JDK_JAVA_OPTIONS`
already configures a Java trust store. It never disables TLS verification.
Other platforms keep their normal approved Java trust store.

```bash
make db-reset CONFIRM=address_lookup OS_PLACES_FALLBACK_ENABLED=true
```

Use that destructive reset only when intentionally dropping and reseeding
the local database. A normal API restart is sufficient to enable fallback
and apply updated local repeatables. Fallback itself never modifies the DB.

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
| OS_PLACES_FALLBACK_ENABLED | Enables English DPA lookup only after an empty database lookup; defaults to false. | false |
| OS_API_KEY | OS API credential, required when fallback is enabled; never commit or log it. | Provided securely in the environment |

## Building the docker image

```bash
make docker-image
```
