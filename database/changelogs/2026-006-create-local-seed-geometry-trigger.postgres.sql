--liquibase formatted sql

-- Jira: ALS-13
-- Description: create local seed geometry trigger
-- Author: team-atlas
-- Created: 2026-10-12

--changeset team-atlas:2026-006:01 context:@local splitStatements:false
--comment: create-local-seed-geometry-function
CREATE OR REPLACE FUNCTION set_royalmail_address_geometry()
RETURNS trigger AS $$
BEGIN
    IF NEW.geometry IS NULL THEN
        NEW.geometry := ST_SetSRID(ST_MakePoint(NEW.easting, NEW.northing), 27700);
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

--rollback DROP FUNCTION public.set_royalmail_address_geometry();

--changeset team-atlas:2026-006:02 context:@local
--comment: create-local-seed-geometry-trigger
CREATE TRIGGER set_royalmail_address_geometry_before_insert
BEFORE INSERT ON os_data.add_gb_royalmailaddress_v1
FOR EACH ROW
EXECUTE FUNCTION set_royalmail_address_geometry();

--rollback DROP TRIGGER set_royalmail_address_geometry_before_insert ON os_data.add_gb_royalmailaddress_v1;

--changeset team-atlas:2026-006:03 context:@local
--comment: create-local-seed-builtaddress-geometry-trigger
CREATE TRIGGER set_builtaddress_geometry_before_insert
BEFORE INSERT ON os_data.add_gb_builtaddress_v3
FOR EACH ROW
EXECUTE FUNCTION set_royalmail_address_geometry();

--rollback DROP TRIGGER set_builtaddress_geometry_before_insert ON os_data.add_gb_builtaddress_v3;

--changeset team-atlas:2026-006:04 context:@local splitStatements:false
--comment: create-local-seed-isl-royalmail-address-geometry-function
CREATE OR REPLACE FUNCTION set_isl_royalmail_address_geometry()
RETURNS trigger AS $$
BEGIN
    IF NEW.geometry IS NULL THEN
        NEW.geometry := ST_SetSRID(ST_MakePoint(NEW.longitude, NEW.latitude), 4258);
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

--rollback DROP FUNCTION public.set_isl_royalmail_address_geometry();

--changeset team-atlas:2026-006:05 context:@local
--comment: create-local-seed-isl-royalmail-address-geometry-trigger
CREATE TRIGGER set_isl_royalmail_address_geometry_before_insert
BEFORE INSERT ON os_data.add_isl_royalmailaddress_v1
FOR EACH ROW
EXECUTE FUNCTION set_isl_royalmail_address_geometry();

--rollback DROP TRIGGER set_isl_royalmail_address_geometry_before_insert ON os_data.add_isl_royalmailaddress_v1;

--changeset team-atlas:2026-006:06 context:@local
--comment: create-local-seed-isl-builtaddress-geometry-trigger
CREATE TRIGGER set_isl_builtaddress_geometry_before_insert
BEFORE INSERT ON os_data.add_isl_builtaddress_v3
FOR EACH ROW
EXECUTE FUNCTION set_isl_royalmail_address_geometry();

--rollback DROP TRIGGER set_isl_builtaddress_geometry_before_insert ON os_data.add_isl_builtaddress_v3;
