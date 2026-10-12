-- Jira: ALS-13
-- Description: create address lookup view
-- Author: team-atlas
-- Created: 2026-10-12
-- Source mapping: database/changelogs/2026-005-create-address-lookup-view.postgres.sql (2026-005:01-2026-005:03)

CREATE SCHEMA address_lookup;



CREATE VIEW address_lookup.address_lookup AS
SELECT
    'GB-' || rm.udprn AS id,
    'GB' AS address_source,
    rm.udprn,
    rm.organisationname,
    rm.departmentname,
    rm.subbuildingname,
    rm.buildingname,
    rm.buildingnumber,
    rm.dependentthoroughfare,
    rm.thoroughfare,
    rm.doubledependentlocality,
    rm.dependentlocality,
    rm.posttown,
    rm.postcode,
    rm.deliverypointsuffix,
    rm.uprn,
    rm.latitude,
    rm.longitude,
    ba.country
FROM os_data.add_gb_royalmailaddress_v1 rm
LEFT JOIN os_data.add_gb_builtaddress_v3 ba ON ba.uprn = rm.uprn
UNION ALL
SELECT
    'ISL-' || rm.udprn AS id,
    'ISL' AS address_source,
    rm.udprn,
    rm.organisationname,
    rm.departmentname,
    rm.subbuildingname,
    rm.buildingname,
    rm.buildingnumber,
    rm.dependentthoroughfare,
    rm.thoroughfare,
    rm.doubledependentlocality,
    rm.dependentlocality,
    rm.posttown,
    rm.postcode,
    rm.deliverypointsuffix,
    rm.uprn,
    rm.latitude,
    rm.longitude,
    ba.country
FROM os_data.add_isl_royalmailaddress_v1 rm
LEFT JOIN os_data.add_isl_builtaddress_v3 ba ON ba.uprn = rm.uprn;


CREATE VIEW public.royalmail_address_lookup AS
SELECT * FROM address_lookup.address_lookup;
