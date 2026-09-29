--liquibase formatted sql

-- PostGIS is pinned to the version Aurora PostgreSQL 18.3 ships (see
-- postgis.version in db.changelog-master.yaml). CREATE EXTENSION on RDS needs
-- rds_superuser, which is why the schema migrator Lambda, and not the service,
-- applies this changelog.
--
-- The precondition makes this a no-op wherever PostGIS is already installed,
-- whatever its version, so it is safe on databases that ran 001 before this
-- file existed. Moving to a different PostGIS version is a deliberate
-- ALTER EXTENSION ... UPDATE in a new file, never an edit to this one.
--
-- Excluded from the local context because the postgis/postgis images only
-- carry the install script for their latest patch release; 001 installs the
-- default version there instead.

--changeset address-lookup-api:000-create-postgis-extension contextFilter:!local
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_catalog.pg_extension WHERE extname = 'postgis'
CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public VERSION '${postgis.version}';
