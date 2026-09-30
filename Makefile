artifact_name       := address-lookup-api
api_module          := address-lookup-api
version             := "unversioned"

comma               := ,
space               := $(empty) $(empty)
# The Liquibase Lambda, released on its own liquibase-lambda-X.Y.Z tag stream (version file in
# its module) and published as $(liquibase_lambda_artifact)-<version>.zip. Every Lambda gets its
# own artefact name and tag stream so that releases never collide.
liquibase_lambda_module   := lambdas/liquibase-schema-migrator
liquibase_lambda_artifact := address-lookup-liquibase-lambda

# Liquibase changelogs in $(api_module)/src/main/resources/db/changelog, released on the
# db-schema-X.Y.Z tag stream. Only the master changelog and what it includes are
# shipped; seeds and data are local only.
db_schema_artifact  := address-lookup-db-schema
db_schema_root      := $(api_module)/src/main/resources
db_schema_contents  := db/changelog/db.changelog-master.yaml db/changelog/changes/creation

.PHONY: all
all: build

.PHONY: clean
clean:
	mvn clean
	rm -f $(artifact_name)-*.zip
	rm -f $(liquibase_lambda_artifact)-*.zip
	rm -f $(db_schema_artifact)-*.zip
	rm -f $(artifact_name).jar
	rm -rf ./build-*
	rm -f ./build.log

.PHONY: build
build:
	mvn versions:set -DnewVersion=$(version) -DgenerateBackupPoms=false
	mvn -pl $(api_module) -am package -DskipTests=true
	cp ./$(api_module)/target/$(artifact_name)-$(version).jar ./$(artifact_name).jar

.PHONY: test
test: test-integration test-unit
	@# Help: Run all test-* targets (convenience method for developers)

.PHONY: test-unit
test-unit:
	@# Help: Run unit tests
	mvn test -Dskip.integration.tests=true

.PHONY: test-integration
test-integration:
	@# Help: Run integration tests
	mvn -pl $(api_module),$(liquibase_lambda_module) -am integration-test verify -Dskip.unit.tests=true failsafe:verify

.PHONY: test-liquibase
test-liquibase:
	@# Help: Test the Liquibase Lambda and validate the released changelogs (no Docker needed)
	mvn -pl $(liquibase_lambda_module) -am verify

.PHONY: build-container
build-container: build
	docker build .

.PHONY: docker-image
docker-image: clean
	mvn -pl $(api_module) -am package -Dskip.unit.tests=true -Dskip.integration.tests=true jib:dockerBuild

.PHONY: package
package:
ifndef version
	$(error No version given. Aborting)
endif
	$(info Packaging version: $(version))
	mvn versions:set -DnewVersion=$(version) -DgenerateBackupPoms=false
	mvn -pl $(api_module) -am package -DskipTests=true
	$(eval tmpdir:=$(shell mktemp -d build-XXXXXXXXXX))
	cp ./$(api_module)/target/$(artifact_name)-$(version).jar $(tmpdir)/$(artifact_name).jar
	cd $(tmpdir); zip -r ../$(artifact_name)-$(version).zip *
	rm -rf $(tmpdir)

.PHONY: package-liquibase-lambda
package-liquibase-lambda:
	@# Help: Build the Liquibase Lambda as $(liquibase_lambda_artifact)-<version>.zip
ifndef version
	$(error No version given. Aborting)
endif
	$(info Packaging Liquibase Lambda version: $(version))
	mvn versions:set -DnewVersion=$(version) -DgenerateBackupPoms=false
	mvn -pl $(liquibase_lambda_module) -am package -DskipTests=true
	cp ./$(liquibase_lambda_module)/target/$(liquibase_lambda_artifact)-$(version)-lambda.jar ./$(liquibase_lambda_artifact)-$(version).zip

.PHONY: package-db-schema
package-db-schema:
	@# Help: Package the Liquibase changelogs as $(db_schema_artifact)-<version>.zip
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
	mvn install -DskipTests
	dependency-check-runner --repo-name=address-lookup-api

.PHONY: lint
lint: lint/docker-compose sonar
	@# Help: Run all lint/* targets and sonar

.PHONY: lint/docker-compose
lint/docker-compose:
	@# Help: Lint docker file
	docker-compose -f docker-compose.yml config
