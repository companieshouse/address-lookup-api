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
    ),
    -- Owner-approved synthetic fixtures for local testing; only postcode and country are authoritative.
    (
        1001, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 1,
        'SYNTHETIC TEST ROAD', 'LOCAL TEST TOWN', 'DG8 9JA', 'Small User',
        'DG', 'DG8', 'DG8 9', '1 SYNTHETIC TEST ROAD, LOCAL TEST TOWN, DG8 9JA',
        'Scotland', '1A', DATE '2016-01-18', DATE '2016-01-18',
        0.00, 0.00, 0.0000000, 0.0000000,
        ST_SetSRID(ST_MakePoint(0.00, 0.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9000000001,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    ),
    (
        1002, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 2,
        'SYNTHETIC TEST ROAD', 'LOCAL TEST TOWN', 'DG2 7AZ', 'Small User',
        'DG', 'DG2', 'DG2 7', '2 SYNTHETIC TEST ROAD, LOCAL TEST TOWN, DG2 7AZ',
        'Scotland', '1B', DATE '2016-01-18', DATE '2016-01-18',
        0.00, 0.00, 0.0000000, 0.0000000,
        ST_SetSRID(ST_MakePoint(0.00, 0.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9000000002,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    ),
    (
        1003, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 3,
        'SYNTHETIC TEST ROAD', 'LOCAL TEST TOWN', 'NE11 9NU', 'Small User',
        'NE', 'NE11', 'NE11 9', '3 SYNTHETIC TEST ROAD, LOCAL TEST TOWN, NE11 9NU',
        'England', '1C', DATE '2016-01-18', DATE '2016-01-18',
        0.00, 0.00, 0.0000000, 0.0000000,
        ST_SetSRID(ST_MakePoint(0.00, 0.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9000000003,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    ),
    (
        1004, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 4,
        'SYNTHETIC TEST ROAD', 'LOCAL TEST TOWN', 'NE31 1JA', 'Small User',
        'NE', 'NE31', 'NE31 1', '4 SYNTHETIC TEST ROAD, LOCAL TEST TOWN, NE31 1JA',
        'England', '1D', DATE '2016-01-18', DATE '2016-01-18',
        0.00, 0.00, 0.0000000, 0.0000000,
        ST_SetSRID(ST_MakePoint(0.00, 0.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9000000004,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    ),
    (
        1005, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 5,
        'SYNTHETIC TEST ROAD', 'LOCAL TEST TOWN', 'CA1 1AA', 'Small User',
        'CA', 'CA1', 'CA1 1', '5 SYNTHETIC TEST ROAD, LOCAL TEST TOWN, CA1 1AA',
        'England', '1E', DATE '2016-01-18', DATE '2016-01-18',
        0.00, 0.00, 0.0000000, 0.0000000,
        ST_SetSRID(ST_MakePoint(0.00, 0.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9000000005,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    ),
    (
        1006, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 6,
        'SYNTHETIC TEST ROAD', 'LOCAL TEST TOWN', 'CA10 1AT', 'Small User',
        'CA', 'CA10', 'CA10 1', '6 SYNTHETIC TEST ROAD, LOCAL TEST TOWN, CA10 1AT',
        'England', '1F', DATE '2016-01-18', DATE '2016-01-18',
        0.00, 0.00, 0.0000000, 0.0000000,
        ST_SetSRID(ST_MakePoint(0.00, 0.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9000000006,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    ),
    (
        1007, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 7,
        'SYNTHETIC TEST ROAD', 'LOCAL TEST TOWN', 'TD1 2SJ', 'Small User',
        'TD', 'TD1', 'TD1 2', '7 SYNTHETIC TEST ROAD, LOCAL TEST TOWN, TD1 2SJ',
        'Scotland', '1G', DATE '2016-01-18', DATE '2016-01-18',
        0.00, 0.00, 0.0000000, 0.0000000,
        ST_SetSRID(ST_MakePoint(0.00, 0.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9000000007,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    ),
    (
        1008, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 8,
        'SYNTHETIC TEST ROAD', 'LOCAL TEST TOWN', 'TD1 1BE', 'Small User',
        'TD', 'TD1', 'TD1 1', '8 SYNTHETIC TEST ROAD, LOCAL TEST TOWN, TD1 1BE',
        'Scotland', '1H', DATE '2016-01-18', DATE '2016-01-18',
        0.00, 0.00, 0.0000000, 0.0000000,
        ST_SetSRID(ST_MakePoint(0.00, 0.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9000000008,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    ),
    (
        1009, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 9,
        'SYNTHETIC TEST ROAD', 'LOCAL TEST TOWN', 'CH1 1DE', 'Small User',
        'CH', 'CH1', 'CH1 1', '9 SYNTHETIC TEST ROAD, LOCAL TEST TOWN, CH1 1DE',
        'England', '1I', DATE '2016-01-18', DATE '2016-01-18',
        0.00, 0.00, 0.0000000, 0.0000000,
        ST_SetSRID(ST_MakePoint(0.00, 0.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9000000009,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    ),
    (
        1010, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 10,
        'SYNTHETIC TEST ROAD', 'LOCAL TEST TOWN', 'CH1 1DF', 'Small User',
        'CH', 'CH1', 'CH1 1', '10 SYNTHETIC TEST ROAD, LOCAL TEST TOWN, CH1 1DF',
        'England', '1J', DATE '2016-01-18', DATE '2016-01-18',
        0.00, 0.00, 0.0000000, 0.0000000,
        ST_SetSRID(ST_MakePoint(0.00, 0.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9000000010,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    ),
    (
        1011, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 11,
        'SYNTHETIC TEST ROAD', 'LOCAL TEST TOWN', 'SY1 1EB', 'Small User',
        'SY', 'SY1', 'SY1 1', '11 SYNTHETIC TEST ROAD, LOCAL TEST TOWN, SY1 1EB',
        'England', '1K', DATE '2016-01-18', DATE '2016-01-18',
        0.00, 0.00, 0.0000000, 0.0000000,
        ST_SetSRID(ST_MakePoint(0.00, 0.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9000000011,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    ),
    (
        1012, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 12,
        'SYNTHETIC TEST ROAD', 'LOCAL TEST TOWN', 'SY10 0AU', 'Small User',
        'SY', 'SY10', 'SY10 0', '12 SYNTHETIC TEST ROAD, LOCAL TEST TOWN, SY10 0AU',
        'England', '1L', DATE '2016-01-18', DATE '2016-01-18',
        0.00, 0.00, 0.0000000, 0.0000000,
        ST_SetSRID(ST_MakePoint(0.00, 0.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9000000012,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    ),
    (
        1013, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 13,
        'SYNTHETIC TEST ROAD', 'LOCAL TEST TOWN', 'HR1 1AD', 'Small User',
        'HR', 'HR1', 'HR1 1', '13 SYNTHETIC TEST ROAD, LOCAL TEST TOWN, HR1 1AD',
        'England', '1M', DATE '2016-01-18', DATE '2016-01-18',
        0.00, 0.00, 0.0000000, 0.0000000,
        ST_SetSRID(ST_MakePoint(0.00, 0.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9000000013,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    ),
    (
        1014, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 14,
        'SYNTHETIC TEST ROAD', 'LOCAL TEST TOWN', 'HR1 1AE', 'Small User',
        'HR', 'HR1', 'HR1 1', '14 SYNTHETIC TEST ROAD, LOCAL TEST TOWN, HR1 1AE',
        'England', '1N', DATE '2016-01-18', DATE '2016-01-18',
        0.00, 0.00, 0.0000000, 0.0000000,
        ST_SetSRID(ST_MakePoint(0.00, 0.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9000000014,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    ),
    (
        1015, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 15,
        'SYNTHETIC TEST ROAD', 'LOCAL TEST TOWN', 'SY21 7BL', 'Small User',
        'SY', 'SY21', 'SY21 7', '15 SYNTHETIC TEST ROAD, LOCAL TEST TOWN, SY21 7BL',
        'Wales', '1O', DATE '2016-01-18', DATE '2016-01-18',
        0.00, 0.00, 0.0000000, 0.0000000,
        ST_SetSRID(ST_MakePoint(0.00, 0.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9000000015,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    ),
    (
        1016, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 16,
        'SYNTHETIC TEST ROAD', 'LOCAL TEST TOWN', 'LL13 7TD', 'Small User',
        'LL', 'LL13', 'LL13 7', '16 SYNTHETIC TEST ROAD, LOCAL TEST TOWN, LL13 7TD',
        'Wales', '1P', DATE '2016-01-18', DATE '2016-01-18',
        0.00, 0.00, 0.0000000, 0.0000000,
        ST_SetSRID(ST_MakePoint(0.00, 0.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9000000016,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    ),
    (
        1017, DATE '2025-09-23', TIMESTAMP '2025-09-24 00:00:00',
        'New', 'Address', 'Postcode Address File', NULL, 17,
        'SYNTHETIC TEST ROAD', 'LOCAL TEST TOWN', 'NP25 3EQ', 'Small User',
        'NP', 'NP25', 'NP25 3', '17 SYNTHETIC TEST ROAD, LOCAL TEST TOWN, NP25 3EQ',
        'Wales', '1Q', DATE '2016-01-18', DATE '2016-01-18',
        0.00, 0.00, 0.0000000, 0.0000000,
        ST_SetSRID(ST_MakePoint(0.00, 0.00), 27700),
        'Central Internal Position', 'Matched UPRN', 9000000017,
        'Direct', DATE '2012-04-23', 'Automated', 'GeoPlace', 'local'
    );
