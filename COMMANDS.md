# Makefile commands

Run commands from the repository root, containing `Makefile` and `pom.xml`.
In the agent workspace this directory is `CODE/`; a cloned repository does
not require an additional `CODE/` directory. This reference describes the
current Makefile and its maintained scripts.

## Requested intents

| Intent | Command | Result |
|---|---|---|
| Start API | `make api-start` | Builds the API and required modules without rewriting POM versions, launches the JAR with the local profile, and waits for HTTP 200 from its health endpoint. Requires the local DB to be running. |
| Stop API | `make api-stop` | Stops only the API recorded by this checkout's helper, after validating its PID start-time identity. |
| Restart API | `make api-restart` | Stops, rebuilds, starts, and checks the managed API. Does not restart Postgres. |
| Start DB through Compose | `make db-start` | `docker compose -f compose.yaml up -d --wait postgres`. |
| Stop DB through Compose | `make db-stop` | `docker compose -f compose.yaml stop postgres`; preserves the database/container. |
| Open interactive psql | `make db` | Opens a terminal session in the local Compose `address_lookup` DB as `postgres`. Exit with `\q`. |
| Execute local SQL | `make db-exec SQL="SELECT count(*) FROM address_lookup.address_lookup;"` | Executes SQL through Compose `psql` against the local `address_lookup` DB; errors fail the command. |
| Start API and DB | `make start` | Starts/waits for Postgres, then starts/waits for the API, sequentially. |
| Stop everything local | `make stop` | Stops this helper's API, then this repository's Compose Postgres. Does not stop shared ingress, other containers, or processes started by other runners. |
| Reset and reseed DB | `make db-reset CONFIRM=address_lookup` | Stops the managed API, verifies its port is free, starts Postgres, drops/recreates only the local Compose `address_lookup` DB, deletes any cached CSVs, downloads the newest FULL Royal Mail CSVs from the OS API (`OS_API_KEY` from the environment or `../.env`) into the git-ignored `database/local/data/`, starts the API with Flyway and the local-only CSV seed migrations (full GB and ISL data, about 33 million rows, ten or more minutes), and verifies the lookup relation contains rows. Leaves both services running. |
| Unit tests | `make test-unit` | Maven reactor Java unit tests with integration tests skipped and automatic Make JDK selection. Does not require local-service tools such as `jq`. |
| Command-helper regressions | `make test-commands` | Runs lifecycle/Sonar, smoke, postcode lookup/generation, Maven-selector and Concourse helper checks using stubs; requires `jq` and `ps`. |
| Format Java | `make tidy FILE=path/File.java` | Formats one file in place. Use `FILE=.` for all Java files, or pipe Java source to `make tidy` for formatted stdout. |
| Check Java formatting | `make spotless-check` | Read-only scan of all Java files; prints only unformatted paths and fails, or exits zero silently when clean. |
| Smoke test | `make test-smoke` | Creates a temporary database, starts a separate API on an OS-assigned port, checks health and applied migrations, then stops its API and drops only its temporary database. |
| Test selected postcodes | `make test-postcode POSTCODE=CF143AT` | Validates selected postcodes, starts the DB, rebuilds/restarts the managed API to apply local seeds, then prints and executes each curl command. |
| Test database postcodes | `make test-postcodes` | Starts the DB, rebuilds/restarts the managed API, reads distinct postcodes from the lookup view, then prints and executes each curl command. Stops at the first failure. |
| Add local postcode fixtures | `make add-postcode POSTCODE=KT138GL` | Fetches public postcode metadata and creates a new local repeatable SQL file containing labelled synthetic addresses. Accepts comma-separated or JSON selections; does not apply SQL. |
| Generate coverage | `make coverage` | Fresh Maven reactor `clean verify` under the `coverage` profile; integration tests skipped. Produces per-module HTML/XML reports without a percentage gate. |
| Check coverage | `make coverage-check MIN_COVERAGE=0.80` | Fresh unit coverage run, enforcing at least 80% LINE coverage for each JaCoCo module bundle with execution data. |
| Get SonarQube coverage | `make sonar-report` | Prints latest server-computed coverage measures as JSON to stdout; does not save files or run analysis. |
| Analyze a PR | `make sonar-pr PR=123 PR_BRANCH=feature/example PR_BASE=main` | Builds fresh unit coverage for the checked-out source branch, submits SonarQube PR analysis, and waits for the quality gate. |
| Install Concourse CLI | `make install-fly` | Downloads the server-matched darwin/arm64 CLI and installs it at `/usr/local/bin/fly` using `sudo`. Apple Silicon macOS only. |
| Show Concourse nodes | `make concourse-status` | Read-only status of every job and resource in the Address Lookup API pipeline; supports node and status filters. |

Complex local control and Sonar logic is in `scripts/local-services.sh` and
`scripts/sonar.sh`. `make test-commands` exercises both with isolated stub
commands, without starting real services or contacting a server. Concourse
helpers are tested with isolated network and installer stubs too.

## Concourse

```bash
make install-fly
make concourse-status
make concourse-status NODE=build-test-analyse
make concourse-status NODES='["build-test-analyse", "source-code"]'
make concourse-status NODES=build-test-analyse,source-code
make concourse-status STATUS=red
make concourse-status NODES=build-test-analyse,source-code STATUS=orange
make concourse-status OUTPUT=human
make concourse-status STATUS=red OUTPUT=json
make concourse-status NODE=build-test-analyse OUTPUT=yaml
```

`install-fly` downloads from
`https://ci-platform.companieshouse.gov.uk/api/v1/cli?arch=arm64&platform=darwin`,
makes the temporary binary executable, checks its version, then runs
`sudo install` to `/usr/local/bin/fly`. It replaces any existing binary there
and may prompt for your administrator password. Downloads and installation
errors fail explicitly; the temporary download is cleaned up. It does not
configure targets or log in, and is supported only on Apple Silicon macOS.

`concourse-status` requires Bash, curl, jq and network access, but not fly,
Docker, Maven, login, or any personal command wrapper. It reads the publicly
accessible jobs and resources endpoints for
[team-development/address-lookup-api](https://ci-platform.companieshouse.gov.uk/teams/team-development/pipelines/address-lookup-api).
It never triggers builds, resource checks or deployments. HTTP, JSON and
unknown-node errors fail rather than reporting success-shaped status.

`NODE` selects one exact name; `NODES` accepts a non-empty JSON string array or
comma-separated names. Do not set both. Names are trimmed and deduplicated;
a name shared by a job and resource selects both. `STATUS` optionally filters
either all nodes or the selected nodes:

| Status | Meaning |
|---|---|
| green | Latest job build or resource check succeeded. |
| red | Failed, errored or aborted build/check, or a reported resource check/setup error. |
| orange | Build/check is started or pending. An active job build takes precedence over its previous result. |
| unknown | No build/check result, an unrecognised state, or a paused node. Not included in the three colour filters. |

Output uses ASCII status names, with job build numbers and resource
last-check UTC timestamps. Green reflects the reported result, not a live
health probe: an old successful resource check may be stale. Previously
finished job results remain visible while a new build runs. Empty filter
results are reported explicitly. Use `STATUS=green`, `STATUS=red` or
`STATUS=orange`, not literal braces. `make consource-status` is also accepted
as an alias for the spelling in the original request.

`OUTPUT=human` (default) prints the table. `OUTPUT=json` and `OUTPUT=yaml`
print only a machine-readable document with `pipeline` and `nodes` fields.
Each node has `type`, `name`, `status` and `detail`; an empty selection result
is an empty `nodes` array. All node/status filters apply to every format.
YAML strings are quoted and escaped using jq, without an extra YAML dependency.
GNU Make reserves `-o` for its own "old file" option, so use `OUTPUT=...`
with Make, not `make concourse-status -o json`. Direct script calls support
`bash scripts/concourse-status.sh -o json` (or `human`/`yaml`).

Run `bash scripts/tests/test_concourse.sh` for isolated helper regressions.

## Requirements

Use Java 21 or a compatible later runtime, Maven, Make, and Bash. Maven
dependencies must be available through your configured repositories.
Compose and PostGIS integration tests need a running Docker engine.
The lifecycle script additionally requires `jq`, `curl`, `lsof`, `ps`, and
standard Unix utilities. Compose must support `up --wait`.

`make test-commands` runs Bash helper regressions separately from Java unit
tests. Images running this target need `jq` and `ps` (`procps` on Linux),
even though service/network commands
are stubbed. Missing prerequisites fail explicitly before the helper checks.
Assertion failures print the last captured command output for diagnosis.

Sonar report retrieval requires `curl` and `jq`; analysis requires Maven,
server access, project permissions, and a valid token. PR analysis requires
a SonarQube edition supporting it. Packaging needs `zip`; `package` also
uses `mktemp`. `m2-check` requires Perl; `deps` requires Homebrew;
`dependency-check` requires the external `dependency-check-runner`.

## Local API and database

### Add synthetic local postcode fixtures

```bash
make add-postcode POSTCODE=KT138GL
make add-postcode POSTCODES='["CF14 3AT", "KT13 8GL"]'
make add-postcode POSTCODES=CF143AT,KT138GL
```

Requires Bash, curl, jq and internet access to Postcodes.io. This fetches postcode
centroids and district/country metadata, not actual postal address records.
It generates one labelled synthetic address with a negative test UDPRN and
no UPRN per new postcode. District names are not authoritative postal towns.
Terminated postcodes use historical metadata with a printed warning.
Historical metadata without a district uses the explicit synthetic town
`SYNTHETIC LOCALITY`, not an inferred real postal town; an unavailable country
is left NULL. Invalid or out-of-range
coordinates fail before file creation.
Northern Ireland (`BT`) fixtures use the ISL table and longitude/latitude
geometry with SRID 4258; GB fixtures use British National Grid SRID 27700.

The command creates a new file such as `database/local/R__seed_KT138GL.sql`
or `database/local/R__seed_CF143AT,KT138GL.sql`. Names use sorted, uppercase
postcodes without spaces. Already seeded postcodes are skipped by inspecting
all local repeatable SQL files, and only new postcodes appear in the filename.
If all are already seeded, no file is created. Existing files are never
overwritten. Each file has its own release marker so rerunning it does not
delete another file's fixtures. The command validates
the whole input and fetches all metadata before writing; failures leave the
seed file unchanged. Review and commit the generated diff yourself.
Generation holds a shared `database/local/.add-postcode.lock` directory lock.
Concurrent invocations fail explicitly rather than creating overlapping seeds.
Normal exits release it; after an abrupt termination, verify no generator is
running before removing a stale lock with `rmdir database/local/.add-postcode.lock`.
It does not start services or apply SQL. Run `make test-postcode` with the same
selection afterward to rebuild/restart and query the new fixtures.
After seeds are applied, `make test-postcodes` discovers them automatically
from the database. Do not supply both POSTCODE and POSTCODES.
Dates in generated fixtures use the UTC generation date. Large selections
whose filenames exceed the filesystem limit must be split into smaller
commands; the helper reports this without creating a file.

Run `bash scripts/tests/test_add_postcode.sh` for isolated generator regressions.

Run `make test-postcodes` to start/wait for the local DB, rebuild/restart the API and exercise
every distinct postcode in `address_lookup.address_lookup`, one request at a time.
The list is read through local Compose `psql`, normalized to uppercase without
spaces, deduplicated and sorted. An empty database or database query error fails
the command before any HTTP lookup. Newly added seeds are discovered without
editing the script. Use
`make test-postcodes LOCAL_API_PORT=8081` if your API runs on port 8081.
It leaves both services running and does not reset existing data. Normal
local API startup applies pending Flyway migrations and local seeds.
It requires `curl` and
`jq`, prints each curl command and GET URL before requesting, then prints
response headers and pretty-printed JSON. It stops immediately on a curl
error, non-200 HTTP status, invalid JSON, mismatched echoed postcode, empty
address list, or result-count mismatch. Each request has a 5-second connect
timeout and a 30-second total timeout.

For selected lookups:

```bash
make test-postcode POSTCODE=CF143AT
make test-postcode POSTCODE=CF143AT,CF146RP,CF239XN
make test-postcode POSTCODES='["CF14 3AT", "CF14 6RP"]'
make test-postcode POSTCODES=CF143AT,CF146RP
```

Do not repeat `POSTCODE=` on the same command line: GNU Make keeps only the
last assignment, so `POSTCODE=CF143AT POSTCODE=CF146RP` queries only `CF146RP`.
Use the comma-separated value or JSON array to query both. Do not combine
`POSTCODE` and `POSTCODES`. Empty lists, non-string JSON entries and invalid
postcodes fail before services start or any lookup runs.

Spaced and
lowercase inputs are accepted, for example `make test-postcode POSTCODE="cf14 3at"`;
each query uses the uppercase postcode without spaces. Missing or malformed
input fails before services start. The command runs `make db-start` followed
by `make api-restart`, then prints
each curl command, executes it and prints headers and JSON using the same
checks and timeouts as `test-postcodes`. Use `LOCAL_API_PORT=8081` to select
another port. Both commands leave services running.

Both commands rebuild and restart the managed API on every invocation, so
new or changed local seed files are packaged and applied by Flyway before
lookups. They do not reset the database. Startup or build failures prevent
the queries. Unlike `make start`, they do not reuse an already healthy API.

Local Royal Mail data is loaded from the OS CSV files, which are never
committed. `make db-reset` runs `scripts/fetch-os-csv.sh` and the local-only
Java Flyway migrations in `db.local.java` bulk-load them with `release_id`
`local`. Integration tests use small fixture CSVs through `LOCAL_SEED_DIR`.
Against a full load, `make test-postcodes` checks a deterministic sample of
postcodes (override with `POSTCODE_SAMPLE_MOD`, a smaller value checks more).
`CF10 1PX` remains in the requested test selection
but currently has no source records; Java integration tests check its empty
result. `make test-postcode POSTCODE=CF101PX` fails its non-empty-result
check rather than inventing an address.

```bash
make start
make api-restart LOCAL_API_PORT=8081
make api-stop
make db-stop
make db-reset CONFIRM=address_lookup LOCAL_API_PORT=8081
make stop
```

`LOCAL_API_PORT` defaults to `8080`, accepts 1024-65535, and is used only by
the lifecycle helper and postcode commands. `LOCAL_API_TIMEOUT` defaults to `180` seconds for API
readiness, excluding build time. An occupied port is an error, not permission
to stop its owner. A healthy managed `api-start` is idempotent. To change
ports, stop the managed API first or use `api-restart`.

Make's lifecycle helper builds through the automatic JDK selector:

```bash
MAKE_JACOCO_AUTO=true bash scripts/maven.sh -pl address-lookup-api -am package -DskipTests=true \
  -Dskip.unit.tests=true -Dskip.integration.tests=true
```

It expects exactly one matching API JAR under `address-lookup-api/target/`.
Clean stale outputs if there are multiple. It runs that JAR with the `local`
profile, enables Flyway production migrations and local fixtures, disables
Spring's automatic Compose lifecycle, and fixes the datasource URL to
`jdbc:postgresql://localhost:5433/address_lookup`.
Existing `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`
environment variables may override the local profile's default credentials.
Do not pass production credentials to local commands.

One managed API per checkout is recorded in `.local-run/api.json`, with
logs appended to `.local-run/api.log`. These paths are ignored by Git.
Stop validates the PID and start-time fingerprint before TERM, waits up to
20 seconds, and retains state if it cannot stop the process. It never uses
name-based process killing or kills an arbitrary listener. APIs started
direct Maven invocation, or a different checkout are not managed.
The helper serializes lifecycle operations using `.local-run/services.lock`.
If interrupted abnormally, inspect the lock and running commands before
removing a stale lock directory.

**Reset is destructive:** it deletes all data and terminates active
connections in the fixed local Compose `address_lookup` database.
Only `CONFIRM=address_lookup` authorizes it. It uses the Compose administrative
role `postgres`, never accepts a remote database URL or an alternate database
name, and never removes Docker volumes or other databases. If reseeding fails,
the command fails visibly; inspect the log rather than assuming success.
It refuses an occupied configured API port after stopping its own API.

`db-stop` does not stop the API; use `stop` for both. `stop` is intentionally
not a machine-wide Docker shutdown. If API startup fails, Postgres remains
running for diagnosis.

### Execute SQL

Start the local Compose database first, then use:

```bash
make db
make db-exec SQL="SELECT count(*) FROM address_lookup.address_lookup;"
make db-exec < query.sql
printf '%s\n' 'SELECT current_database();' | make db-exec
```

`make db` opens the interactive `psql` terminal with Compose's TTY enabled.
It requires a real terminal and the database already running. Enter SQL there
and use `\q` to exit; it does not start or reset services.

Use `SQL=`, not `make db-exec "SELECT ..."`, because Make treats positional
arguments as additional build targets. `SQL` defaults to empty; nonempty SQL
takes precedence over stdin. For dollar signs, procedural SQL, or complicated
quoting, prefer a file or pipe so Make and the shell cannot expand the SQL.
In Make variable values a literal dollar sign must be doubled (`$$`).

The command uses `docker compose exec -T postgres psql -X -U postgres
-d address_lookup -v ON_ERROR_STOP=1`, printing normal query output and
propagating connection or SQL failures. It does not start services, migrate,
or reset the DB. SQL can modify/delete local data; multiple statements are
not automatically wrapped in a transaction. No remote database override is
provided. The helper does not save the supplied SQL to a file.

### Smoke test behavior

`make test-smoke` wraps `scripts/local-api-smoke.sh`. This
helper requires Java, Maven, Docker Compose v2, and `curl`; its temporary database
workflow is separate from the portable lifecycle helper. Spring Boot requests
an available port from the OS; the helper discovers it from the startup log.
An existing development API can stay running, including on port 8080.
The helper leaves that API and the Compose Postgres service running after
cleanup, removing only its own API, temporary log, and temporary database.
It packages the API and required modules with unit/integration tests skipped,
then launches `java -jar` with the local profile. The readiness timeout starts
after packaging. Direct Java execution avoids Maven reporting an expected
API termination during cleanup as `BUILD FAILURE`. Actual build/startup
failures still fail the smoke command.

Optional environment settings:

```bash
LOCAL_API_STARTUP_TIMEOUT_SECONDS=120 \
LOCAL_API_HEALTH_POLL_INTERVAL_SECONDS=2 make test-smoke
```

The smoke helper's settings are distinct from `LOCAL_API_TIMEOUT`.
`make test-commands` uses stubs;
they are not live smoke tests.

## Java formatting tools

The project uses the [Spotless Maven plugin](https://github.com/diffplug/spotless/tree/main/plugin-maven),
`com.diffplug.spotless:spotless-maven-plugin:3.11.0`, with pinned
`google-java-format:2.0.0` in GOOGLE style (two-space indentation). Configuration
lives in the root `pom.xml`; formatting does not run implicitly during ordinary
Maven builds. Imports use Google ordering and unused imports are removed.
Javadoc formatting, long-string reflow and optional syntax cleanups are disabled.

```bash
make tidy FILE=address-lookup-api/src/main/java/uk/gov/companieshouse/addresslookup/Application.java
make tidy FILE=.
cat foobar.java | make tidy > foobar-formatted.java
make spotless-check
```

`FILE` names one Java file, including paths with spaces when quoted, and is
formatted in place. `FILE=.` formats all Java files across the checkout.
Without `FILE`, stdin is Java source content, not a list of filenames; only
formatted Java is written to stdout. Empty input fails with usage guidance.
Do not redirect piped output to the input file: the shell would truncate it.
Use `FILE=...` for safe in-place formatting. A nonempty `FILE` takes precedence
over stdin. Run these commands from the repository root.

`scripts/spotless.sh check` scans the whole Java codebase, including tests and
untracked Java files. Generated/build/runtime directories named `target`,
`build-*`, `.git` and `.local-run` are excluded. Other languages, SQL migrations,
POMs and documentation are not reformatted by these commands.
The helper formats a temporary snapshot using the same POM configuration and
compares every Java file. This avoids Spotless's truncated standard violation
list and never modifies source files during checks. Temporary files are cleaned
on exit; formatter or dependency errors are reported explicitly without
overwriting inputs.
Before tidy writes any results, it checks every destination against the original
snapshot. If a file changed while Maven was running, tidy aborts without writing
back any files, preserving the intervening edits. Avoid editing files during
the final write-back; this check is not a filesystem-wide locking mechanism.

On a clean tree, the helper and `make spotless-check` return 0 without listing
files. On formatting violations, the helper prints every offending path
relative to the repository root and exits **255**. Standard GNU Make converts
any recipe failure to its own exit **2**, while showing `Error 255`; this is
Make's behavior, not a different formatting result. For the exact script status:

```bash
bash scripts/spotless.sh check
```

Maven, parser or other operational failures are errors, not clean results.
The command-helper suite does not invoke Spotless. `make test-commands` also
requires `zip`, `unzip` and Perl for the CSV-download and Maven-settings checks.
Pipeline formatting enforcement is
deferred, and no new workflow is added. Existing Java sources
are intentionally unchanged by this tools-only introduction, so the opt-in
scan may report existing differences. Before committing Java changes, tidy
the files you intend to change and review the diff. `FILE=.` is available
when a deliberate whole-codebase formatting pass is wanted; it is not run
automatically.

Run `bash scripts/tests/test_spotless.sh` manually for isolated, Maven-stub-based
helper regressions. This suite is not connected to `make test-commands` or
the pipeline, needs no Maven downloads, and does not format project sources.

Direct Maven equivalents from the root are `mvn -N spotless:apply` and
`mvn -N spotless:check`. The latter uses Maven's usual failure status and
truncated diagnostics, not the helper's complete path list or 255 contract.

### Editor integration

See [EDITOR.md](EDITOR.md) for IntelliJ setup and usage.
All editor actions use the existing Spotless helper and root `pom.xml`;
there is no separate formatting configuration or automatic format-on-save.

### Optional pre-commit hook

`scripts/git-hooks/pre-commit` runs `make tidy FILE=.` before a commit. It is opt-in:
adding this script does not install it or change existing hooks or pipelines.
To enable it for this checkout, first check your hook configuration:

```bash
git config --get core.hooksPath
```

If you already use hooks, integrate `bash scripts/git-hooks/pre-commit` into your
existing pre-commit hook rather than replacing it. Otherwise, enable the
tracked script directly for this checkout:

```bash
git config --local core.hooksPath scripts/git-hooks
```

This formats all Java files, not only staged files. If any tracked Java file
has unstaged changes afterwards, the hook blocks the commit so you can review
and stage the intended changes before retrying. It never stages files for you.
Formatter failures also block the commit. With partially staged Java files,
review the remaining unstaged changes carefully rather than staging blindly.

## Coverage

```bash
make coverage
make coverage-check
make coverage-check MIN_COVERAGE=0.85
```

`MIN_COVERAGE` is a ratio, not a percent: `0.85` means at least 85%.
The default is `0.80`. JaCoCo checks LINE/COVEREDRATIO at module BUNDLE level
at Maven `verify`. Modules without execution data can be skipped by JaCoCo;
this is not proof that untested code has full coverage. Existing shortfalls
are reported, not hidden by lowering a threshold or excluding code.

Direct Maven builds, including Jenkins on Java 21, default to the
Java-21-compatible JaCoCo 0.8.13 without extra variables or flags.
Make's Maven commands and build helpers use `scripts/maven.sh`, which detects
the JDK running Maven (not a potentially different `java` on PATH).
On Java 21 it preserves that default. On newer JDKs, including Java 25,
it resolves the latest released JaCoCo plugin through Maven's configured
repositories and supplies that version automatically. No Java-25-specific
version is pinned. Resolution requires repository access and fails visibly
without starting the build if it cannot determine a release.
The selected version is printed; it can change when a new release is published.
Future JDK compatibility still depends on support in that release.
This switch is specific to Make; direct Maven on newer JDKs does not switch.
Verified on Java 21 and Java 25: `make coverage` passed all 301 unit tests
on each JDK. Java 21 used 0.8.13; Java 25 dynamically resolved 0.8.16 during
verification. Direct Maven unit tests on Java 21 also passed without a
JaCoCo override. These are execution results, not a pin for newer JDKs.
The profile adds late-bound agent arguments alongside Mockito's agent.
These commands produce unit coverage,
not integration coverage or a combined cross-module report.
The default means **at least** 80%, not the PR template's wording of greater
than 80%; choose an appropriate stricter ratio if required by review policy.

Outputs for each Java module with coverage data:

```text
address-lookup-api/target/site/jacoco/index.html
address-lookup-api/target/site/jacoco/jacoco.xml
lambdas/db-bootstrap/target/site/jacoco/index.html
```

`coverage` and `coverage-check` use the automatic JDK selector to run
`clean verify -Pcoverage
-Dskip.integration.tests=true`; the check additionally passes
`-Dcoverage.check.skip=false -Dcoverage.minimum=<ratio>`.
They do not change project versions or start the normal local DB.

## SonarQube

Make exports these overridable defaults:

```text
SONAR_HOST_URL=https://sonarqube.companieshouse.gov.uk
SONAR_PROJECT_KEY=uk.gov.companieshouse:address-lookup-api
```

Supply only the token through your secure environment for this project:

```bash
# Supply SONAR_TOKEN using your approved secret manager/environment.
# Never put a real token in source, a Make argument, or shared logs.

make sonar
make sonar-report
make sonar-pr PR=123 PR_BRANCH=feature/example PR_BASE=main
make sonar-report PR=123
```

Override the server/project through environment variables or Make arguments
when needed. PR numbers remain explicit; for example, `make sonar-report PR=83`.
Unset or empty inherited server/project environment values use the defaults.
Nonempty environment values and explicit Make overrides are preserved.
No token is stored in the Makefile. Direct invocation of `scripts/sonar.sh`
still requires the server/project environment variables as well as the token.
HTTPS certificate verification is required; report requests
do not follow redirects. The token stays out of command-line arguments and
is sent as an HTTP header from stdin for retrieval. Use only an authorized
server: analysis transmits project/source analysis information to it.

`make sonar` submits non-PR project analysis; run it only in the intended
main/default branch context. Both analysis commands run fresh
`clean verify -Pcoverage -Dskip.integration.tests=true` followed by the
pinned Maven scanner `5.0.0.4389` in the same invocation, and wait for the
quality gate. Build, scanner, authentication, and quality-gate errors fail
the command.

`sonar-pr` requires a positive PR number without `#`, plus `PR_BRANCH` and
`PR_BASE`. It verifies the current Git branch equals `PR_BRANCH`.
Check out/update the intended source and make base history available first.
It does not fetch a PR, modify it, trigger GitHub/Concourse jobs, or configure
PR decoration; those are owned by the CI/ALM integration.

`sonar-report` fetches coverage, line coverage, branch coverage, lines to
cover, and uncovered lines from the latest completed server analysis.
It is a JSON metric report, not a PDF/dashboard export or a new analysis.
A queued newer analysis may not yet be represented. Both project and PR
reports print formatted JSON to stdout without saving report or temporary
files. HTTP errors and absent coverage data fail rather than printing an
empty success report.

## Existing build, release and test targets

All targets are phony. `make` defaults to `all`, which depends on `build`.
Command-line `name=value` overrides Make variables, not Maven properties.

| Target / example | Behavior and outputs |
|---|---|
| `make all version=1.2.3` | Same as `build`. |
| `make build version=1.2.3` | `versions:set` without backup POMs, API/required-module package with tests skipped, copy API JAR to root `address-lookup-api.jar`. Changes POM versions. |
| `make clean` | Maven clean, remove generated API/DB-bootstrap/DB-migrate/schema ZIPs, root API JAR, `./build-*`, and `./build.log`. Does not stop services or remove `.local-run/`. |
| `make test` | Runs `test-integration` then `test-unit` in a serial invocation; not every `test-*` target. |
| `make test-integration` | Selects API plus dependencies, requests `integration-test verify`, `-Dskip.unit.tests=true`, and `failsafe:verify`. Docker required for Testcontainers. |
| `make test-flyway` | Feed-layout shell checks then API/required-module `DatabaseFeedIT` via Failsafe. Requires Docker/PostGIS Testcontainers. |
| `make test-db-bootstrap` | DB-bootstrap/required-module unit tests, integration tests skipped. |
| `make test-db-bootstrap-integration` | DB-bootstrap/required-module `verify` selecting `DatabaseBootstrapIT`; also runs unit tests. Docker required. |
| `make m2-check` | Runs Perl helper; may prompt for a JFrog token/create `~/.m2/settings.xml`, sets permissions to `0600`, validates Git identity, then runs the API dependency-tree goal. Not read-only. |
| `make build-container version=1.2.3` | Runs `build`, then `docker build .`; currently lacks a root Dockerfile. No explicit image tag. |
| `make docker-image` | Cleans first, then Maven package/Jib `dockerBuild` with test-skip properties. Uses configured private ECR base image and local daemon output tag `address-lookup-api:latest` under its configured registry name. Does not push/deploy or set POM versions. |
| `make package version=1.2.3` | Sets POM versions, builds API, ZIPs `address-lookup-api.jar` at archive root into `address-lookup-api-1.2.3.zip`; removes temporary build directory. |
| `make package-db-bootstrap-lambda version=1.2.3` | Sets POM versions, packages DB-bootstrap and dependencies, copies `-lambda.jar` to `address-lookup-db-bootstrap-1.2.3.zip`. |
| `make package-db-migrate-lambda version=1.2.3` | Sets POM versions, packages DB-migrate and dependencies, copies `-lambda.jar` to `address-lookup-db-migrate-1.2.3.zip`. |
| `make package-db-schema version=1.2.3` | Replaces same-version `address-lookup-db-schema-1.2.3.zip` with production `migrations/` only, using `zip -X`; no Java build, SQL version rewriting, migration application, or deployment. Excludes local fixtures. |
| `make deps` | `brew install kafka` only; not a full development setup. |
| `make dependency-check version=1.2.3` | Runs `build` and `package`, Maven install with tests/integration tests skipped, then external dependency checker. Changes versions/local Maven repository. |
| `make lint` | Compose validation only; Sonar must be explicitly requested. |
| `make lint/docker-compose` | `docker compose -f compose.yaml config --quiet`; validates Compose without printing resolved environment, not Dockerfile linting. |

### Existing variable overrides and combinations

| Variable | Default | Used by |
|---|---|---|
| `version` | `"unversioned"` | Build and package targets, including their dependents; not Jib image tagging. |
| `api_module` | `address-lookup-api` | API Maven selections and artifact copy paths, not reactor-wide `test-unit`. |
| `artifact_name` | `address-lookup-api` | API artifact paths and cleanup; override must match Maven filenames. |
| `db_bootstrap_lambda_module` | `lambdas/db-bootstrap` | DB-bootstrap tests and packaging. |
| `db_bootstrap_lambda_artifact` | `address-lookup-db-bootstrap` | Bootstrap artifact paths/cleanup. |
| `db_migrate_lambda_module` | `lambdas/db-migrate` | DB-migrate packaging. |
| `db_migrate_lambda_jar` | `address-lookup-lambda-db-migrate` | DB-migrate Maven JAR name. |
| `db_migrate_lambda_artifact` | `address-lookup-db-migrate` | DB-migrate artifact paths/cleanup. |
| `db_schema_artifact` | `address-lookup-db-schema` | Schema archive name/cleanup. |
| `db_schema_root` | `database` | Schema packaging source directory. |
| `db_schema_contents` | `migrations` | Paths inside schema archive. Keep production default; adding `local` would ship fixtures. |

`comma` and `space` are defined but unused. `tmpdir` is internal to API
packaging; do not override it. Use simple version/path values without shell
metacharacters because legacy packaging recipes interpolate unquoted values.
All four packaging targets have a missing-version guard, but the nonempty
default bypasses it if `version` is omitted; `version=` triggers the guard.
Build/package commands can modify POMs without backups: inspect the diff.

```bash
make clean && make package version=1.2.3
make package package-db-schema version=1.2.3
make test-unit test-flyway
make test-flyway && make package-db-schema version=1.2.3
make package-db-schema version=1.2.3 db_schema_artifact=custom-db-schema
```

Do not use `make -j` for combinations that share builds, tests, cleanup,
version updates, or lifecycle state. Use `&&` for explicitly ordered work.
There is no general `ARGS`, `MAVEN_ARGS`, test-name, profile, or image-tag
passthrough: `make test-unit test=SomeTest` does not select a test.
`make -n package` is not fully side-effect-free: its `$(shell mktemp ...)`
can execute during recipe expansion.

For targeted tests/builds on Java 21, direct Maven needs no JaCoCo flags.
These commands bypass Make and do not automatically switch JaCoCo on Java 25:

```bash
mvn -pl address-lookup-api -am package \
  -Dskip.unit.tests=true -Dskip.integration.tests=true
mvn -pl address-lookup-api -Dtest=AddressLookupControllerTest test
mvn -pl address-lookup-api -am -Dit.test=AddressesControllerIT \
  -Dskip.unit.tests=true test-compile failsafe:integration-test failsafe:verify
```

For the same targeted operations on Java 25, use Make's selector explicitly
instead of raw Maven, for example:

```bash
MAKE_JACOCO_AUTO=true bash scripts/maven.sh \
  -pl address-lookup-api -Dtest=AddressLookupControllerTest test
```

For normal developer workflows, prefer `make test-unit`, `make coverage`,
`make test-flyway`, and the lifecycle targets; they enable selection themselves.

The parent POM wires `skip.unit.tests` to Surefire; the API wires
`skip.integration.tests` to Failsafe. No `help`, `run`, or `setup-db` target
is defined; use the documented lifecycle commands.

## Validation and external dependencies

The helper regressions exercise local lifecycle, port/PID protection, reset
confirmation/seed checks, failure cleanup, and Sonar request construction
using stubs. Effective Maven profile inspection verifies JaCoCo/Mockito agent
configuration for all reactor modules. No production service or remote
SonarQube analysis is needed to run those checks.

The initial real coverage attempt hit a pre-existing mirror availability
issue: inherited `maven-dependency-plugin:3.11.0` could not resolve
`domtrip-core` and `domtrip-maven` version `1.5.1` from the configured mirror.
If encountered locally, resolve repository access with its owner; do not
disable TLS verification or change project credentials.
Never infer a passing coverage percentage from command availability alone.

Effective Maven profile inspection verified agent configuration in all 11
reactor projects. An initial full coverage run used session-only public Maven
resolution for the missing public artifacts, without changing project or user
Maven settings. Subsequent coverage runs used the normal configured mirror.
`make coverage-check MIN_COVERAGE=0` previously passed to verify
configurability only; the default policy remains 80%.

### Live command verification

The following results record actual command execution on 2026-11-04, not just
dry runs or stub tests. The environment used macOS, Java 25, Maven 3.10,
Make 3.81, and Docker Compose with a working Docker engine. Results depend on
the checked-out source and available external tooling.

Version-changing builds, packaging, cleanup, and the dependency checker ran
in an isolated copy containing the current working changes, with
`version=0.0.0-command-validation`. The original checkout's POMs were not
rewritten. Lifecycle checks used API port 18080. Shutdown checks used a
separate Compose project with its Postgres host port changed to 15433 in the
test copy; combined `stop` terminated a running managed API and that disposable
Postgres. The original Postgres and unrelated containers remained running.
The disposable container/network and smoke-test database were removed.

| Target(s) | Observed result |
|---|---|
| `all`, `build` | Passed; built and copied the API JAR. |
| `clean` | Passed in the isolated copy. |
| `package` | Passed; produced the API ZIP. |
| `package-db-schema` | Passed; produced the production migration ZIP. |
| `package-db-bootstrap-lambda` | Passed; produced the bootstrap Lambda ZIP. |
| `api-start`, `api-restart`, `api-stop` | Passed against a real API; health returned HTTP 200, repeated start was idempotent, and stop removed its listener. |
| `db-start`, `start` | Passed; Compose readiness and API readiness succeeded. A live postcode lookup also succeeded. |
| `db-stop`, `stop` | Passed using disposable Postgres; confirmed its container stopped while the original database remained running. |
| `test-commands` | Passed 53 stub-based lifecycle/Sonar regression checks. These remain stub tests, despite invoking the real Make target. |
| `test-unit` | Passed helper regressions and 301 Java unit tests, with no failures, errors, or skips. |
| `test-smoke` | Passed live; health returned HTTP 200, Flyway applied six migrations, and the temporary database was removed. |
| `test-db-bootstrap` | Passed 49 unit tests. |
| `test-db-bootstrap-integration` | Passed 49 unit tests and two real PostGIS integration tests. |
| `coverage` | Passed the full unit-coverage build. |
| `coverage-check` | Failed as expected at the default threshold: `release-model` has 74.66% line coverage, below 80%. Later reactor modules were not gated in that failing run. |
| `sonar-report` | Passed authenticated live retrieval for the default project and PR 83. The server returned overall coverage of 70.8% and 0.0%, respectively. Neither request submitted analysis. |
| `m2-check` | Passed with existing Maven settings; no interactive credential creation was exercised. |
| `lint`, `lint/docker-compose` | Passed actual Compose configuration validation. |
| `deps` | Passed; Homebrew installed Kafka 4.4.0 and installed/upgraded its dependencies. The Kafka service was not started. |
| `test-flyway` | Layout checks passed, but `DatabaseFeedIT` failed at `assertNullCountryFixture` (line 486): expected `null`, received `Scotland`. This is a real test failure, not a Docker availability error. |
| `build-container` | API build passed; Docker build failed because the root `Dockerfile` is absent. |
| `docker-image` | Failed before image construction: Maven could not resolve the `jib` plugin prefix in the selected reactor/configured repositories. Private ECR access was therefore not exercised. |
| `dependency-check` | Build, package, and Maven install passed; the scan failed because `dependency-check-runner` is unavailable. No vulnerability-scan result was produced. |
| `db-reset` | Confirmation refusal verified against real tooling. The destructive confirmed path remains stub-tested only; the existing local database was not dropped. |
| `sonar-pr` | Real branch-identity guard rejected PR 83's different source branch before submission. No open PR for the commands branch was found; successful remote PR analysis remains unverified. |
| `sonar` | Not submitted: feature-branch analysis could replace the project's default-branch results. |

These results do not establish that every target works. In particular, a
confirmed destructive reset and successful remote Sonar submissions still need
an explicitly disposable database and the correct approved branch/PR context.
