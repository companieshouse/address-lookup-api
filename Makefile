artifact_name       := address-lookup-api
api_module          := address-lookup-api
version             := "unversioned"

db_schema_artifact := address-lookup-db-schema

.PHONY: all
all: build

.PHONY: clean
clean:
	mvn clean
	rm -f $(artifact_name)-*.zip
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
	mvn -pl $(api_module) -am verify -Dskip.unit.tests=true

.PHONY: test-flyway
test-flyway:
	python3 database/tools/generate_local_seed_sql.py --check
	mvn -pl $(api_module) -am test -Dtest=FlywayFeedTest -Dsurefire.failIfNoSpecifiedTests=false
	mvn -pl $(api_module) -am verify -Dit.test=FlywayFeedIT -Dskip.unit.tests=true

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

.PHONY: package-db-schema
package-db-schema:
	@# Help: Package the Flyway schema feed as $(db_schema_artifact)-<version>.zip
ifndef version
	$(error No version given. Aborting)
endif
	$(info Packaging Flyway db-schema version: $(version))
	rm -f $(CURDIR)/$(db_schema_artifact)-$(version).zip
	python3 database/tools/package_db_schema.py --version $(version) --output $(CURDIR)/$(db_schema_artifact)-$(version).zip

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
