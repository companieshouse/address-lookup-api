# address-lookup-api

The Address Lookup API supplies Companies House services, including CHIPS
and frontend services, with address data looked up by postcode. The Spring
Boot service reads Royal Mail address records from PostgreSQL/PostGIS and
returns either structured address details or the existing legacy format.

Ordnance Survey is the upstream data source. Separate acquisition and import
modules handle downloading, validating and loading that data into the
database. Postcode requests query PostgreSQL directly; they do not call OS
live or use PostgreSQL as a fallback cache.

The API exposes three lookup endpoints under `/address-lookup-api`:

- `GET /addresses?postcode=...`: structured address records, including
  identifiers and coordinates, with the requested postcode and result count.
- `GET /multiple-addresses?postcode=...`: a list of legacy addresses with
  premise, address lines, post town and country.
- `GET /postcode?postcode=...`: one legacy address without premise, or HTTP
  404 when no address is found.

Local development uses the repository's PostGIS container and checked-in
real OS seed data. No OS API key is needed to start the API or load these
local fixtures.

## Running locally

Clone this repository and run Make commands from its root. A separate
`docker-chs-development` checkout, `chs-dev` and Tilt are not required.

Install Java 21+, Maven, Make, Bash, `jq`, `curl`, `lsof` and `ps`, and ensure
Maven can access the project's dependency repositories. Docker must be
running with Docker Compose v2 available.

```bash
make start
```

On the first start, this starts PostGIS on port `5433`, builds the API,
applies Flyway migrations and local seeds, and waits for the API health
check. The API runs in the background on port `8080` by default; use
`make start LOCAL_API_PORT=8081` if another service owns that port.

Use `make api-restart` to rebuild and restart the API after source changes,
and `make stop` when finished to stop the managed API and PostGIS.
See [COMMANDS.md](COMMANDS.md) for the available Make operations.

All local seed rows are real OS CSV records, grouped by source dataset:
`database/local/R__seed_add_gb_royalmailaddress.sql` contains 664 GB records,
and `R__seed_add_isl_royalmailaddress.sql` contains 273 ISL records. The GB
seed includes all 266 source addresses for 13 supplied Cardiff postcodes and
`KT13 8GL`, from package `24833`, FULL version `150450`. `CF10 1PX` has no
source record. Both regional seeds use only the `local` release marker and
replace those rows when reapplied, preserving other releases. All local
fixtures are excluded from the production migration package; the existing
`make db-reset` uses this hardcoded SQL without downloading data or requiring
an OS API key.

For an existing checkout using the former separate Cardiff seed, see
[the local fixture transition notes](database/README.md#local-fixture-conventions)
before rebuilding or resetting its database.

The API uses Flyway migrations from `classpath:db/migrations` when
`FLYWAY_ENABLED=true`; the base configuration defaults to disabled when the
variable is unset. The `local` profile explicitly enables Flyway and adds local
seed files under `classpath:db/local`. Non-local startup with the variable
unset does not create `flyway_schema_history`; environments that use a separate
migration runner should leave the API flag unset. The API package includes the
SQL feed from `database/` without copying or modifying those files.

For an isolated startup and health check, run:

```bash
make test-smoke
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
run by Failsafe, not Surefire. `make test-unit` does **not** run them, so a
passing unit suite does not establish that the integration tests pass.
Integration tests require Docker and use disposable PostGIS containers;
the shared local PostGIS database does not need to be running.

Before pushing, run the unit and API integration suites from a clean build
to avoid stale compiled classes hiding failures:

```bash
make clean test
```

To run either suite separately:

```bash
make test-unit
make test-integration
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

## Top 5 commands to operate the api

Run these commands from the repository root:

| Command | Purpose |
| --- | --- |
| `make start` | Start PostGIS and the API with local seeds. |
| `make stop` | Stop the managed API and PostGIS. |
| `make api-restart` | Rebuild and restart the API after source changes. |
| `make test` | Run unit and API integration tests; Docker is required. |
| `make tidy FILE=path/to/YourFile.java` | Format a changed Java file using Spotless. |

See [COMMANDS.md](COMMANDS.md) for the full command reference and options,
and [EDITOR.md](EDITOR.md) for editor setup and integrations.

## Sample HTTP requests

`rest-api.http` holds sample requests for the API and the Ordnance Survey download API. See
[docs/rest-api-http.md](docs/rest-api-http.md) for how to run it in IntelliJ with your own OS API key.

## Environment variables

Configure only the settings for the component you run. The database-backed
API and `make db-reset` do not require `OS_API_KEY`: local fixtures are
hardcoded SQL, not live OS downloads. Acquisition and importer settings
below apply to the separate upstream pipeline.

Supply secrets through the approved environment or secret store. Do not put
real API keys, passwords or tokens in this README or commit them to Git.

### API runtime

These settings are declared in `address-lookup-api/src/main/resources/application.properties`
and `application-local.properties`.

| Variable | Purpose | Default or requirement |
| --- | --- | --- |
| `SERVER_PORT` | Spring Boot HTTP port. | `8080`; Make-managed startup uses `LOCAL_API_PORT` instead. |
| `API_LOCAL_URL` | Configures `api.api-local-url`. | `localhost` |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL. | Local profile: `jdbc:postgresql://localhost:5433/address_lookup`; otherwise provide datasource configuration. |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL username. | Local profile: `postgres`; otherwise provide datasource configuration. |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL password. | Local profile: `postgres`; otherwise supply securely. |
| `FLYWAY_ENABLED` | Enables API startup migrations in the base profile. | `false`; the local profile explicitly enables Flyway. |
| `DOCKER_COMPOSE_ENABLED` | Enables Spring Boot's Docker Compose integration. | Base profile: `false`; local profile: `true`. Make-managed startup explicitly disables it and manages PostGIS itself. |

### OS acquisition

These settings are declared in `lambdas/shared/src/main/resources/acquisition.properties`.
Configure the bucket, event bus and package settings for the acquisition
handlers that use them; they are not API startup requirements.

| Variable | Purpose | Default or requirement |
| --- | --- | --- |
| `OS_API_KEY` | Authenticates the acquisition client's OS requests. | No default; required when the OS client is used. |
| `OS_PACKAGES` | Declared acquisition package-configuration field. | Unset; not directly consumed by the checked-in handlers. |
| `SOURCE_BUCKET` | Source acquisition storage. | Unset; configure for handlers using source storage. |
| `SCANNED_BUCKET` | Scanned destination storage, also used by the importer. | Unset; configure for handlers using scanned storage. |
| `INGEST_BUCKET` | Ingest bucket used by the scan-results handler. | No default; required by that handler. |
| `EVENT_BUS` | EventBridge bus for acquisition commands. | No default; required when publishing commands. |
| `MAX_ZIP_BYTES` | Maximum ZIP size in bytes. | `21474836480` (20 GiB) |
| `MAX_EXTRACTED_BYTES` | Maximum extracted size in bytes. | `1073741824` (1 GiB) |
| `ACQUISITION_SWEEP_LIMIT` | Acquisition reconciliation sweep limit. | `20` |
| `ROYAL_MAIL_SCHEMA` | Configured Royal Mail dataset schema version. | `1.0`; must agree with the pinned release contract. |

### Import and release settings

The importer reads `lambdas/import/src/main/resources/importer.properties`
and requires `SCANNED_BUCKET` from the acquisition settings above.
`MAX_PRIMARY_CSV_BYTES` belongs to the release-core local CSV reader.
These settings do not control Flyway's hardcoded local SQL fixtures.

| Variable | Purpose | Default or requirement |
| --- | --- | --- |
| `AWS_REGION` | AWS region used by the importer and AWS clients. | Required by the importer; normally supplied by Lambda. |
| `JDBC_URL` | Importer's PostgreSQL JDBC URL. | Required for the importer. |
| `DB_USER` | Importer's database username. | Required for the importer. |
| `DB_PASSWORD` | Importer's database password. | Required for the importer; supply securely. |
| `IMPORTER_SWEEP_LIMIT` | Import reconciliation sweep limit. | `20` |
| `MAX_PRIMARY_CSV_BYTES` | Release-core local primary CSV size limit, in bytes. | `536870912` (512 MiB) |

### Database bootstrap Lambda

These settings are read by `BootstrapConfiguration`, separately from the API.

| Variable | Purpose | Default or requirement |
| --- | --- | --- |
| `MASTER_CREDENTIALS_PARAMETER` | Parameter Store name for master credentials. | Required for bootstrap. |
| `SCHEMA_OWNER_CREDENTIALS_PARAMETER` | Parameter Store name for schema-owner credentials. | Required for bootstrap. |
| `VERIFY_TLS` | Enables database TLS protocol verification. | `true` |
| `CONNECT_TIMEOUT_SECONDS` | Connection and TLS probe timeout, in seconds. | `10` |

### Database migrator Lambda

These settings are read by `MigrationConfig`, separately from API startup
Flyway configuration.

| Variable | Purpose | Default or requirement |
| --- | --- | --- |
| `SNAPSHOT_BUCKET` | Bucket containing migration snapshots. | Required for the migrator. |
| `DB_CREDENTIALS_PARAMETER` | Parameter Store path for database credentials. | Required for the migrator. |
| `ALLOW_REPAIR` | Explicitly permits migration repair. | `false`; opt in only when repair is intended. |
| `LOCK_TIMEOUT` | Database lock wait timeout. | `10s`; accepts a positive integer followed by `ms` or `s`. |
| `MAX_BUNDLE_BYTES` | Maximum migration bundle size, in bytes. | `52428800` (50 MiB) |
| `MAX_BUNDLE_ENTRIES` | Maximum migration bundle entry count. | `1000` |

### Local Make and smoke-test helpers

| Variable | Purpose | Default |
| --- | --- | --- |
| `LOCAL_API_PORT` | Port for the Make-managed API and postcode checks. | `8080` |
| `LOCAL_API_TIMEOUT` | Managed API startup health-check timeout, in seconds. | `180` |
| `LOCAL_API_STARTUP_TIMEOUT_SECONDS` | Isolated smoke-test startup timeout, in seconds. | `90` |
| `LOCAL_API_HEALTH_POLL_INTERVAL_SECONDS` | Isolated smoke-test health poll interval, in seconds. | `2` |

`make start` selects the local profile and passes its port explicitly, so use
`make start LOCAL_API_PORT=8081` to choose another managed port. The isolated
smoke helper uses its own temporary database and OS-assigned port.

Make command inputs such as `POSTCODE`, `POSTCODES`, `CONFIRM`, `SQL`, `FILE`,
`MIN_COVERAGE`, `PR`, `PR_BRANCH` and `PR_BASE` are documented in
[COMMANDS.md](COMMANDS.md); they are not mandatory API environment settings.
Make sets `MAKE_JACOCO_AUTO=true` internally.

### SonarQube tooling

These settings are used only by the Sonar Make targets.

| Variable | Purpose | Default or requirement |
| --- | --- | --- |
| `SONAR_HOST_URL` | SonarQube server URL. | Make default: `https://sonarqube.companieshouse.gov.uk` |
| `SONAR_PROJECT_KEY` | SonarQube project identifier. | Make default: `uk.gov.companieshouse:address-lookup-api` |
| `SONAR_TOKEN` | SonarQube authentication token. | Required for Sonar targets; supply through the environment, not Make arguments. |

### Deployment and legacy names

The checked-in ECS configuration injects `PORT` and `LOGLEVEL`. The legacy
`ecs-image-build/docker_start.sh` sets `PORT=8080` itself and passes
`-Dserver.port` to Java; it does not honor an environment override for that
variable as written. The Spring configuration above uses `SERVER_PORT`.

`HUMAN_LOG` was previously listed as a deployment logging flag but is not
referenced by the checked-in API configuration; confirm the deployment's
logging integration before relying on it.

The legacy Elasticsearch setting `ADDRESS_LOOKUP_URL` is not used by the
current PostgreSQL-backed API.
