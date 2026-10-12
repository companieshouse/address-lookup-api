-- Jira: ALS-13
-- Description: create local seed geometry trigger
-- Author: team-atlas
-- Created: 2026-10-12
-- Source mapping: database/changelogs/2026-006-create-local-seed-geometry-trigger.postgres.sql (2026-006:01-2026-006:06)

CREATE OR REPLACE FUNCTION set_royalmail_address_geometry()
RETURNS trigger AS $$
BEGIN
    IF NEW.geometry IS NULL THEN
        NEW.geometry := ST_SetSRID(ST_MakePoint(NEW.easting, NEW.northing), 27700);
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;


CREATE TRIGGER set_royalmail_address_geometry_before_insert
BEFORE INSERT ON os_data.add_gb_royalmailaddress_v1
FOR EACH ROW
EXECUTE FUNCTION set_royalmail_address_geometry();


CREATE TRIGGER set_builtaddress_geometry_before_insert
BEFORE INSERT ON os_data.add_gb_builtaddress_v3
FOR EACH ROW
EXECUTE FUNCTION set_royalmail_address_geometry();


CREATE OR REPLACE FUNCTION set_isl_royalmail_address_geometry()
RETURNS trigger AS $$
BEGIN
    IF NEW.geometry IS NULL THEN
        NEW.geometry := ST_SetSRID(ST_MakePoint(NEW.longitude, NEW.latitude), 4258);
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;


CREATE TRIGGER set_isl_royalmail_address_geometry_before_insert
BEFORE INSERT ON os_data.add_isl_royalmailaddress_v1
FOR EACH ROW
EXECUTE FUNCTION set_isl_royalmail_address_geometry();


CREATE TRIGGER set_isl_builtaddress_geometry_before_insert
BEFORE INSERT ON os_data.add_isl_builtaddress_v3
FOR EACH ROW
EXECUTE FUNCTION set_isl_royalmail_address_geometry();
