artifact_name       := address-lookup-api
api_module          := address-lookup-api
version             := "unversioned"

comma               := ,
space               := $(empty) $(empty)

# Released independently and invoked by terraform/groups/aurora before migrations.
db_bootstrap_lambda_module   := lambdas/db-bootstrap
db_bootstrap_lambda_artifact := address-lookup-db-bootstrap

# Released independently on the db-migrate-lambda-X.Y.Z tag stream and invoked by the DB pipeline
# to apply a pinned Flyway snapshot. The module's artifactId follows the address-lookup-lambda-* naming.
db_migrate_lambda_module   := lambdas/db-migrate
db_migrate_lambda_jar      := address-lookup-lambda-db-migrate
db_migrate_lambda_artifact := address-lookup-db-migrate

# Flyway migrations in database/migrations, released on the db-schema-X.Y.Z tag
# stream. Local repeatable seeds in database/local are never packaged.
db_schema_artifact  := address-lookup-db-schema
db_schema_root      := database
db_schema_contents  := migrations

LOCAL_API_PORT ?= 8080
LOCAL_API_TIMEOUT ?= 180
CONFIRM ?=
SQL ?=
FILE ?=
POSTCODE ?=
POSTCODES ?=
NODE ?=
NODES ?=
STATUS ?=
MIN_COVERAGE ?= 0.80
PR ?=
PR_BRANCH ?=
PR_BASE ?=
ifeq ($(strip $(SONAR_HOST_URL)),)
SONAR_HOST_URL = https://sonarqube.companieshouse.gov.uk
endif
ifeq ($(strip $(SONAR_PROJECT_KEY)),)
SONAR_PROJECT_KEY = uk.gov.companieshouse:address-lookup-api
endif
export LOCAL_API_PORT LOCAL_API_TIMEOUT CONFIRM MIN_COVERAGE PR PR_BRANCH PR_BASE
export SONAR_HOST_URL SONAR_PROJECT_KEY
export SQL FILE POSTCODE POSTCODES
export NODE NODES STATUS
export MAKE_JACOCO_AUTO := true
MVN := bash scripts/maven.sh

.PHONY: all
all: build

.PHONY: clean
clean:
	$(MVN) clean
	rm -f $(artifact_name)-*.zip
	rm -f $(db_bootstrap_lambda_artifact)-*.zip
	rm -f $(db_migrate_lambda_artifact)-*.zip
	rm -f $(db_schema_artifact)-*.zip
	rm -f $(artifact_name).jar
	rm -rf ./build-*
	rm -f ./build.log

.PHONY: build
build:
	$(MVN) versions:set -DnewVersion=$(version) -DgenerateBackupPoms=false
	$(MVN) -pl $(api_module) -am package -DskipTests=true
	cp ./$(api_module)/target/$(artifact_name)-$(version).jar ./$(artifact_name).jar

.PHONY: test
test: test-integration test-unit
	@# Help: Run all test-* targets (convenience method for developers)

.PHONY: test-unit
test-unit:
	@# Help: Run unit tests
	$(MVN) test -Dskip.integration.tests=true

.PHONY: tidy spotless-check
tidy:
	@bash scripts/spotless.sh tidy

spotless-check:
	@bash scripts/spotless.sh check

.PHONY: api-start api-stop api-restart db-start db-stop start stop db-reset
api-start api-stop api-restart db-start db-stop start stop db-reset:
	@bash scripts/local-services.sh $@

.PHONY: db
db:
	@docker compose -f compose.yaml exec postgres psql -X -U postgres -d address_lookup

.PHONY: db-exec
db-exec:
	@bash scripts/db-exec.sh

.PHONY: test-commands test-smoke coverage coverage-check sonar sonar-pr sonar-report
test-commands:
	bash tests/test_commands.sh
	bash tests/test_local_api_smoke.sh
	bash tests/test_postcodes.sh
	bash tests/test_add_postcode.sh
	bash tests/test_maven.sh
	bash tests/test_concourse.sh

.PHONY: install-fly concourse-status consource-status
install-fly:
	@bash scripts/install-fly.sh

concourse-status consource-status:
	@bash scripts/concourse-status.sh

test-smoke:
	bash scripts/local-api-smoke.sh

.PHONY: test-postcodes
test-postcodes:
	@$(MAKE) db-start
	@$(MAKE) api-restart
	@bash scripts/test-postcodes.sh

.PHONY: test-postcode
.PHONY: add-postcode
add-postcode:
	@bash scripts/add-postcode.sh

test-postcode:
	@bash scripts/test-postcodes.sh validate-single
	@$(MAKE) db-start
	@$(MAKE) api-restart
	@bash scripts/test-postcodes.sh single

coverage:
	$(MVN) clean verify -Pcoverage -Dskip.integration.tests=true

coverage-check:
	$(MVN) clean verify -Pcoverage -Dskip.integration.tests=true -Dcoverage.check.skip=false "-Dcoverage.minimum=$${MIN_COVERAGE}"

sonar sonar-pr sonar-report:
	@bash scripts/sonar.sh $@

.PHONY: test-integration
test-integration:
	@# Help: Run integration tests
	$(MVN) -pl $(api_module) -am integration-test verify -Dskip.unit.tests=true failsafe:verify


.PHONY: m2-check
m2-check:
	@# Help: Make sure ~/.m2/settings.xml is there and working with JFrog
	perl tests/m2-check.pl

.PHONY: test-flyway
test-flyway:
	@# Help: Test the Flyway database feed against fresh PostGIS
	bash tests/test_database_layout.sh
	$(MVN) -pl $(api_module) -am -Dit.test=DatabaseFeedIT -Dfailsafe.failIfNoSpecifiedTests=false -Dskip.unit.tests=true test-compile failsafe:integration-test failsafe:verify

.PHONY: test-db-bootstrap
test-db-bootstrap:
	@# Help: Run DB bootstrap unit tests (no Docker required)
	$(MVN) -pl $(db_bootstrap_lambda_module) -am test -Dskip.integration.tests=true

.PHONY: test-db-bootstrap-integration
test-db-bootstrap-integration:
	@# Help: Test bootstrap and first Flyway run as the non-master owner (requires Docker)
	$(MVN) -pl $(db_bootstrap_lambda_module) -am -Dit.test=DatabaseBootstrapIT -Dfailsafe.failIfNoSpecifiedTests=false verify

.PHONY: build-container
build-container: build
	docker build .

.PHONY: docker-image
docker-image: clean
	$(MVN) -pl $(api_module) -am package -Dskip.unit.tests=true -Dskip.integration.tests=true jib:dockerBuild

.PHONY: package
package:
ifndef version
	$(error No version given. Aborting)
endif
	$(info Packaging version: $(version))
	$(MVN) versions:set -DnewVersion=$(version) -DgenerateBackupPoms=false
	$(MVN) -pl $(api_module) -am package -DskipTests=true
	$(eval tmpdir:=$(shell mktemp -d build-XXXXXXXXXX))
	cp ./$(api_module)/target/$(artifact_name)-$(version).jar $(tmpdir)/$(artifact_name).jar
	cd $(tmpdir); zip -r ../$(artifact_name)-$(version).zip *
	rm -rf $(tmpdir)

.PHONY: package-db-bootstrap-lambda
package-db-bootstrap-lambda:
	@# Help: Build the DB bootstrap Lambda release
ifndef version
	$(error No version given. Aborting)
endif
	$(info Packaging DB bootstrap Lambda version: $(version))
	$(MVN) versions:set -DnewVersion=$(version) -DgenerateBackupPoms=false
	$(MVN) -pl $(db_bootstrap_lambda_module) -am package -DskipTests=true
	cp ./$(db_bootstrap_lambda_module)/target/$(db_bootstrap_lambda_artifact)-$(version)-lambda.jar ./$(db_bootstrap_lambda_artifact)-$(version).zip

.PHONY: package-db-migrate-lambda
package-db-migrate-lambda:
	@# Help: Build the DB migrate Lambda release as $(db_migrate_lambda_artifact)-<version>.zip
ifndef version
	$(error No version given. Aborting)
endif
	$(info Packaging DB migrate Lambda version: $(version))
	$(MVN) versions:set -DnewVersion=$(version) -DgenerateBackupPoms=false
	$(MVN) -pl $(db_migrate_lambda_module) -am package -DskipTests=true
	cp ./$(db_migrate_lambda_module)/target/$(db_migrate_lambda_jar)-$(version)-lambda.jar ./$(db_migrate_lambda_artifact)-$(version).zip

.PHONY: package-db-schema
package-db-schema:
	@# Help: Package production Flyway migrations as $(db_schema_artifact)-<version>.zip
ifndef version
	$(error No version given. Aborting)
endif
	$(info Packaging db-schema version: $(version))
	rm -f $(CURDIR)/$(db_schema_artifact)-$(version).zip
	cd $(db_schema_root) && zip -X -r $(CURDIR)/$(db_schema_artifact)-$(version).zip $(db_schema_contents)

.PHONY: deps
deps:
	@# Help: Install dependencies
	brew install kafka

.PHONY: dependency-check
dependency-check: build package
	$(MVN) install -DskipTests -Dskip.integration.tests=true
	dependency-check-runner --repo-name=address-lookup-api

.PHONY: lint
lint: lint/docker-compose
	@# Sonar analysis must be explicitly requested with make sonar or make sonar-pr.

.PHONY: lint/docker-compose
lint/docker-compose:
	@# Help: Lint docker file
	docker compose -f compose.yaml config --quiet
