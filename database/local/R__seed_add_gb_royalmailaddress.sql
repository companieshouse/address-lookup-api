-- Jira: ALS-83
-- Purpose: Seed API-test GB Royal Mail v2 addresses.
-- Author/team: Address Lookup Service
-- Date: 2026-10-13
-- UDPRN 19 is the approved NULL-country example.
DELETE FROM os_data.add_gb_royalmailaddress_v2
WHERE release_id = 'local';

INSERT INTO os_data.add_gb_royalmailaddress_v2 (
    udprn,
    versiondate,
    versionavailablefromdate,
    changetype,
    theme,
    description,
    subbuildingname,
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
        26394069, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 57,
        'WOODMOOR ROAD', 'WAKEFIELD', 'WF2 7QD', 'Small User',
        'WF', 'WF2', 'WF2 7', '57 WOODMOOR ROAD, WAKEFIELD, WF2 7QD',
        'England', '1Q', DATE '2016-01-18', DATE '2016-01-18',
        432541.02, 416166.86, 53.6410272, -1.5092809,
        ST_SetSRID(ST_MakePoint(432541.02, 416166.86), 27700),
        'Central Internal Position', 'Matched UPRN', 63129266,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    ),
    (
        16, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 14,
        'NETHERKIRKGATE', 'ABERDEEN', 'AB10 1AU', 'Small User',
        'AB', 'AB10', 'AB10 1', '14 NETHERKIRKGATE, ABERDEEN, AB10 1AU',
        'Scotland', '3F', DATE '2016-01-18', DATE '2016-01-18',
        394290.00, 806333.00, 57.1478301, -2.0960095,
        ST_SetSRID(ST_MakePoint(394290.00, 806333.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9051081497,
        'Direct', DATE '2014-03-04', 'Automated', 'GeoPlace', 'local'
    ),
    (
        18, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', 'FLAT 1', 16,
        'NETHERKIRKGATE', 'ABERDEEN', 'AB10 1AU', 'Small User',
        'AB', 'AB10', 'AB10 1', 'FLAT 1, 16 NETHERKIRKGATE, ABERDEEN, AB10 1AU',
        'Scotland', '1N', DATE '2016-01-18', DATE '2016-01-18',
        394280.00, 806334.00, 57.1478389, -2.0961748,
        ST_SetSRID(ST_MakePoint(394280.00, 806334.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9051081931,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    ),
    (
        19, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', 'FLAT 10', 16,
        'NETHERKIRKGATE', 'ABERDEEN', 'AB10 1AU', 'Small User',
        'AB', 'AB10', 'AB10 1', 'FLAT 10, 16 NETHERKIRKGATE, ABERDEEN, AB10 1AU',
        NULL, '2B', DATE '2016-01-18', DATE '2016-01-18',
        394280.00, 806334.00, 57.1478389, -2.0961748,
        ST_SetSRID(ST_MakePoint(394280.00, 806334.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9051082090,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    );
