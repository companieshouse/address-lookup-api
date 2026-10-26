-- Jira: ALS-83
-- Purpose: Expose the Royal Mail v2 GB and ISL tables through one lookup view.
-- Author/team: Address Lookup Service
-- Date: 2026-10-13
CREATE VIEW address_lookup.address_lookup AS
SELECT
    'GB-' || gb.udprn::text AS id,
    'GB'::text AS address_source,
    gb.udprn,
    gb.organisationname,
    gb.departmentname,
    gb.subbuildingname,
    gb.buildingname,
    gb.buildingnumber,
    gb.dependentthoroughfare,
    gb.thoroughfare,
    gb.doubledependentlocality,
    gb.dependentlocality,
    gb.posttown,
    gb.postcode,
    gb.postcodetype,
    gb.deliverypointsuffix,
    gb.uprn,
    gb.latitude,
    gb.longitude,
    gb.country
FROM os_data.add_gb_royalmailaddress_v2 gb
UNION ALL
SELECT
    'ISL-' || isl.udprn::text AS id,
    'ISL'::text AS address_source,
    isl.udprn,
    isl.organisationname,
    isl.departmentname,
    isl.subbuildingname,
    isl.buildingname,
    isl.buildingnumber,
    isl.dependentthoroughfare,
    isl.thoroughfare,
    isl.doubledependentlocality,
    isl.dependentlocality,
    isl.posttown,
    isl.postcode,
    isl.postcodetype,
    isl.deliverypointsuffix,
    isl.uprn,
    isl.latitude,
    isl.longitude,
    isl.country
FROM os_data.add_isl_royalmailaddress_v2 isl;
