--liquibase formatted sql

--changeset address-lookup-api:007-create-add-isl-royalmailaddress-postcode-index
CREATE INDEX idx_isl_royalmailaddress_postcode_normalized
ON add_isl_royalmailaddress (upper(replace(postcode, ' ', '')));

--changeset address-lookup-api:008-create-royalmail-address-lookup-view
CREATE VIEW royalmail_address_lookup AS
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
FROM add_gb_royalmailaddress rm
LEFT JOIN add_gb_builtaddress ba ON ba.uprn = rm.uprn
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
FROM add_isl_royalmailaddress rm
LEFT JOIN add_isl_builtaddress ba ON ba.uprn = rm.uprn;
