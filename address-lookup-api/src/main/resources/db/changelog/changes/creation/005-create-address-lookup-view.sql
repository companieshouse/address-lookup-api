--liquibase formatted sql

--changeset address-lookup-api:"001 Create address_lookup schema"
CREATE SCHEMA address_lookup;

--rollback DROP SCHEMA address_lookup;


--changeset address-lookup-api:"002 Create address-lookup view for os_data"
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
