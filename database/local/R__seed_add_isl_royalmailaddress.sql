-- Jira: ALS-83
-- Purpose: Seed API-test ISL Royal Mail v2 addresses.
-- Author/team: Address Lookup Service
-- Date: 2026-10-13
-- UDPRN 10403188 is the approved NULL-country example.
DELETE FROM os_data.add_isl_royalmailaddress_v2
WHERE release_id = 'local';

INSERT INTO os_data.add_isl_royalmailaddress_v2 (
    udprn,
    versiondate,
    versionavailablefromdate,
    changetype,
    theme,
    description,
    subbuildingname,
    buildingname,
    buildingnumber,
    thoroughfare,
    posttown,
    postcode,
    postcodetype,
    postcodearea,
    postcodedistrict,
    postcodesector,
    fulladdress,
    country,
    deliverypointsuffix,
    updatedate,
    entrydate,
    easting,
    northing,
    latitude,
    longitude,
    geometry,
    positionalaccuracy,
    geometryallocationmethod,
    uprn,
    matchtype,
    matchdate,
    matchmethod,
    matchingorganisation,
    release_id
)
VALUES
    (
        3159258, DATE '2025-09-24', TIMESTAMP '2025-09-25 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, NULL, 1,
        'PORTER PARK', 'BELFAST', 'BT10 0BU', 'Small User',
        'BT', 'BT10', 'BT10 0', '1 PORTER PARK, BELFAST, BT10 0BU',
        'Northern Ireland', '1A', DATE '2016-01-18', DATE '2016-01-18',
        142444.63, 525548.75, 54.5594197, -5.9852083,
        ST_SetSRID(ST_MakePoint(-5.9852083, 54.5594197), 4258),
        'Central Internal Position', 'Matched UPRN', 185084635,
        'Direct', DATE '2016-03-16', 'Automated', 'GeoPlace', 'local'
    ),
    (
        3160016, DATE '2025-09-24', TIMESTAMP '2025-09-25 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, NULL, 1,
        'GARRON CRESCENT', 'BELFAST', 'BT10 0EQ', 'Small User',
        'BT', 'BT10', 'BT10 0', '1 GARRON CRESCENT, BELFAST, BT10 0EQ',
        'Northern Ireland', '1A', DATE '2016-01-18', DATE '2016-01-18',
        143022.81, 525278.43, 54.5572901, -5.9760532,
        ST_SetSRID(ST_MakePoint(-5.9760532, 54.5572901), 4258),
        'Central Internal Position', 'Matched UPRN', 185085388,
        'Direct', DATE '2016-03-16', 'Automated', 'GeoPlace', 'local'
    ),
    (
        3160017, DATE '2025-09-24', TIMESTAMP '2025-09-25 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, NULL, 10,
        'GARRON CRESCENT', 'BELFAST', 'BT10 0EQ', 'Small User',
        'BT', 'BT10', 'BT10 0', '10 GARRON CRESCENT, BELFAST, BT10 0EQ',
        'Northern Ireland', '1B', DATE '2016-01-18', DATE '2016-01-18',
        142988.24, 525273.36, 54.5572271, -5.9765819,
        ST_SetSRID(ST_MakePoint(-5.9765819, 54.5572271), 4258),
        'Central Internal Position', 'Matched UPRN', 185085389,
        'Direct', DATE '2016-03-16', 'Automated', 'GeoPlace', 'local'
    ),
    (
        28233637, DATE '2025-09-24', TIMESTAMP '2025-09-25 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, NULL, 15,
        'GRANARY CLOSE', 'COLERAINE', 'BT51 3TU', 'Small User',
        'BT', 'BT51', 'BT51 3', '15 GRANARY CLOSE, COLERAINE, BT51 3TU',
        'United Kingdom', '1W', DATE '2016-01-18', DATE '2016-01-18',
        101049.99, 592724.86, 55.1383498, -6.6927613,
        ST_SetSRID(ST_MakePoint(-6.6927613, 55.1383498), 4258),
        'Central Internal Position', 'Matched UPRN', 187269072,
        'Direct', DATE '2016-03-16', 'Automated', 'GeoPlace', 'local'
    ),
    (
        28233631, DATE '2025-09-24', TIMESTAMP '2025-09-25 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, NULL, 18,
        'GRANARY CLOSE', 'COLERAINE', 'BT51 3TU', 'Small User',
        'BT', 'BT51', 'BT51 3', '18 GRANARY CLOSE, COLERAINE, BT51 3TU',
        'United Kingdom', '1T', DATE '2016-01-18', DATE '2016-01-18',
        101031.94, 592748.51, 55.1385508, -6.6930684,
        ST_SetSRID(ST_MakePoint(-6.6930684, 55.1385508), 4258),
        'Central Internal Position', 'Matched UPRN', 187269054,
        'Direct', DATE '2016-03-16', 'Automated', 'GeoPlace', 'local'
    ),
    (
        10405570, DATE '2025-09-24', TIMESTAMP '2025-09-25 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL,
        'DOMME 7 LES PETITES NOUETTES', NULL, 'LES NOUETTES',
        'GUERNSEY', 'GY8 0EE', 'Small User', 'GY', 'GY8', 'GY8 0',
        'DOMME 7 LES PETITES NOUETTES, LES NOUETTES, GUERNSEY, GY8 0EE',
        'United Kingdom', '2N', DATE '2016-01-18', DATE '2016-01-18',
        NULL, NULL, 49.4313916, -2.6078121,
        ST_SetSRID(ST_MakePoint(-2.6078121, 49.4313916), 4258),
        'General Internal Position', 'Matched UPRN', 10095225334,
        'Direct', DATE '2016-03-16', 'Unknown', 'GeoPlace', 'local'
    ),
    (
        10403183, DATE '2025-09-24', TIMESTAMP '2025-09-25 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 'HERIVEL', NULL,
        'RUE DES HECHES', 'GUERNSEY', 'GY7 9AD', 'Small User',
        'GY', 'GY7', 'GY7 9', 'HERIVEL, RUE DES HECHES, GUERNSEY, GY7 9AD',
        'United Kingdom', '1B', DATE '2016-01-18', DATE '2016-01-18',
        NULL, NULL, 49.4293256, -2.6152540,
        ST_SetSRID(ST_MakePoint(-2.6152540, 49.4293256), 4258),
        'General Internal Position', 'Matched UPRN', 10095217971,
        'Direct', DATE '2016-03-16', 'Automated', 'GeoPlace', 'local'
    ),
    (
        10403188, DATE '2025-09-24', TIMESTAMP '2025-09-25 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 'LES HECHES', NULL,
        'RUE DES HECHES', 'GUERNSEY', 'GY7 9AD', 'Small User',
        'GY', 'GY7', 'GY7 9', 'LES HECHES, RUE DES HECHES, GUERNSEY, GY7 9AD',
        NULL, '1R', DATE '2016-01-18', DATE '2016-01-18',
        NULL, NULL, 49.4294521, -2.6161670,
        ST_SetSRID(ST_MakePoint(-2.6161670, 49.4294521), 4258),
        'General Internal Position', 'Matched UPRN', 10095217966,
        'Direct', DATE '2016-03-16', 'Automated', 'GeoPlace', 'local'
    ),
    (
        11453875, DATE '2025-09-24', TIMESTAMP '2025-09-25 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, NULL, 32,
        'PRINCES STREET', 'ISLE OF MAN', 'IM1 1BD', 'Small User',
        'IM', 'IM1', 'IM1 1', '32 PRINCES STREET, ISLE OF MAN, IM1 1BD',
        'United Kingdom', '1T', DATE '2016-01-18', DATE '2016-01-18',
        237833.00, 475662.00, 54.1511167, -4.4846250,
        ST_SetSRID(ST_MakePoint(-4.4846250, 54.1511167), 4258),
        'Central Internal Position', 'Matched UPRN', 115006022,
        'Direct', DATE '2016-03-16', 'Automated', 'GeoPlace', 'local'
    ),
    (
        11453860, DATE '2025-09-24', TIMESTAMP '2025-09-25 00:00:00',
        'New', 'Address', 'Postcode Address File', 'FLAT 2', NULL, 40,
        'PRINCES STREET', 'ISLE OF MAN', 'IM1 1BD', 'Small User',
        'IM', 'IM1', 'IM1 1', 'FLAT 2, 40 PRINCES STREET, ISLE OF MAN, IM1 1BD',
        'United Kingdom', '2L', DATE '2016-01-18', DATE '2016-01-18',
        237845.24, 475675.59, 54.1512426, -4.4844451,
        ST_SetSRID(ST_MakePoint(-4.4844451, 54.1512426), 4258),
        'Postcode Unit Position', 'Postcode Unit', NULL,
        'Unmatched', NULL, 'Unknown', NULL, 'local'
    ),
    (
        11876824, DATE '2025-09-24', TIMESTAMP '2025-09-25 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 'CASA MIA', NULL,
        'LES GRANDS VAUX', 'JERSEY', 'JE2 7NA', 'Small User',
        'JE', 'JE2', 'JE2 7', 'CASA MIA, LES GRANDS VAUX, JERSEY, JE2 7NA',
        'United Kingdom', '1H', DATE '2016-01-18', DATE '2016-01-18',
        NULL, NULL, 49.1972466, -2.0954130,
        ST_SetSRID(ST_MakePoint(-2.0954130, 49.1972466), 4258),
        'General Internal Position', 'Matched UPRN', 10095024051,
        'Direct', DATE '2016-03-16', 'Automated', 'GeoPlace', 'local'
    ),
    (
        11876852, DATE '2025-09-24', TIMESTAMP '2025-09-25 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, '1 STEEPHILL COTTAGES', NULL,
        'LES GRANDS VAUX', 'JERSEY', 'JE2 7NA', 'Small User',
        'JE', 'JE2', 'JE2 7',
        '1 STEEPHILL COTTAGES, LES GRANDS VAUX, JERSEY, JE2 7NA',
        'United Kingdom', '1E', DATE '2016-01-18', DATE '2016-01-18',
        NULL, NULL, 49.1943666, -2.0983458,
        ST_SetSRID(ST_MakePoint(-2.0983458, 49.1943666), 4258),
        'General Internal Position', 'Matched UPRN', 10095024030,
        'Direct', DATE '2016-03-16', 'Automated', 'GeoPlace', 'local'
    ),
    (
        11876899, DATE '2025-09-24', TIMESTAMP '2025-09-25 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 'GLENAVON', NULL,
        'LANGLEY PARK', 'JERSEY', 'JE2 7ND', 'Small User',
        'JE', 'JE2', 'JE2 7', 'GLENAVON, LANGLEY PARK, JERSEY, JE2 7ND',
        'United Kingdom', '4G', DATE '2016-01-18', DATE '2016-01-18',
        NULL, NULL, 49.1978181, -2.0946098,
        ST_SetSRID(ST_MakePoint(-2.0946098, 49.1978181), 4258),
        'General Internal Position', 'Matched UPRN', 10095030835,
        'Direct', DATE '2016-03-16', 'Automated', 'GeoPlace', 'local'
    );
