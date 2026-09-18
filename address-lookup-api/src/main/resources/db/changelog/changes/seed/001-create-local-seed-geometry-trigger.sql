--liquibase formatted sql

--changeset address-lookup-api:001-create-local-seed-geometry-function splitStatements:false
CREATE OR REPLACE FUNCTION set_royalmail_address_geometry()
RETURNS trigger AS $$
BEGIN
    IF NEW.geometry IS NULL THEN
        NEW.geometry := ST_SetSRID(ST_MakePoint(NEW.easting, NEW.northing), 27700);
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

--changeset address-lookup-api:002-create-local-seed-geometry-trigger
CREATE TRIGGER set_royalmail_address_geometry_before_insert
BEFORE INSERT ON add_gb_royalmailaddress
FOR EACH ROW
EXECUTE FUNCTION set_royalmail_address_geometry();

--changeset address-lookup-api:003-create-local-seed-builtaddress-geometry-trigger
CREATE TRIGGER set_builtaddress_geometry_before_insert
BEFORE INSERT ON add_gb_builtaddress
FOR EACH ROW
EXECUTE FUNCTION set_royalmail_address_geometry();

--changeset address-lookup-api:004-create-local-seed-isl-royalmail-address-geometry-function splitStatements:false
CREATE OR REPLACE FUNCTION set_isl_royalmail_address_geometry()
RETURNS trigger AS $$
BEGIN
    IF NEW.geometry IS NULL THEN
        NEW.geometry := ST_SetSRID(ST_MakePoint(NEW.longitude, NEW.latitude), 4258);
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

--changeset address-lookup-api:005-create-local-seed-isl-royalmail-address-geometry-trigger
CREATE TRIGGER set_isl_royalmail_address_geometry_before_insert
BEFORE INSERT ON add_isl_royalmailaddress
FOR EACH ROW
EXECUTE FUNCTION set_isl_royalmail_address_geometry();

--changeset address-lookup-api:006-create-local-seed-isl-builtaddress-geometry-trigger
CREATE TRIGGER set_isl_builtaddress_geometry_before_insert
BEFORE INSERT ON add_isl_builtaddress
FOR EACH ROW
EXECUTE FUNCTION set_isl_royalmail_address_geometry();
