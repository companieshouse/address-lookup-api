comma               := ,
space               := $(empty) $(empty)
artifact_name       := address-lookup-api
api_module          := address-lookup-api
version             := "unversioned"

# Deployable Lambda modules under lambdas/, released together on the lambda-X.Y.Z tag stream
lambda_modules      := discovery download scan unzip import schema-migrator
lambda_prefix       := address-lookup-lambda

# Liquibase changelogs, released on the db-schema-X.Y.Z tag stream. Only the
# master changelog and what it includes are shipped; seeds and data are local only.
db_schema_artifact  := address-lookup-db-schema
db_schema_root      := db-schema/src/main/resources
db_schema_contents  := db/changelog/db.changelog-master.yaml db/changelog/changes/creation

.PHONY: all
all: build

.PHONY: clean
clean:
	mvn clean
	rm -f $(artifact_name)-*.zip
	rm -f $(lambda_prefix)-*.zip
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
	mvn -pl $(api_module),lambdas/schema-migrator -am integration-test verify -Dskip.unit.tests=true failsafe:verify

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

.PHONY: package-lambdas
package-lambdas:
	@# Help: Build every deployable Lambda as $(lambda_prefix)-<module>-<version>.zip
ifndef version
	$(error No version given. Aborting)
endif
	$(info Packaging Lambdas version: $(version))
	mvn versions:set -DnewVersion=$(version) -DgenerateBackupPoms=false
	mvn -pl $(subst $(space),$(comma),$(addprefix lambdas/,$(lambda_modules))) -am package -DskipTests=true
	$(foreach module,$(lambda_modules),cp ./lambdas/$(module)/target/$(lambda_prefix)-$(module)-$(version)-lambda.jar ./$(lambda_prefix)-$(module)-$(version).zip;)

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

.PHONY: lint
lint: lint/docker-compose sonar
	@# Help: Run all lint/* targets and sonar

.PHONY: lint/docker-compose
lint/docker-compose:
	@# Help: Lint docker file
	docker-compose -f docker-compose.yml config
