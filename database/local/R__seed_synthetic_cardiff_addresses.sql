-- Synthetic local fixtures, not OS or Royal Mail address records.
-- One invented address per owner-supplied postcode; identifiers are test-only.
-- Postcode centroid metadata: https://api.postcodes.io, retrieved 2026-11-06.
-- CF10 1PX uses terminated-postcode metadata (terminated January 2023).
-- Also includes KT13 8GL in Weybridge, England.
-- Centroids are not building coordinates. UPRNs are deliberately left NULL.
DELETE FROM os_data.add_gb_royalmailaddress_v2
WHERE release_id = 'local-synthetic-cardiff';

INSERT INTO os_data.add_gb_royalmailaddress_v2 (
    udprn, versiondate, versionavailablefromdate, changetype, theme, description,
    buildingname, buildingnumber, thoroughfare, posttown, postcode,
    postcodetype, postcodearea, postcodedistrict, postcodesector, fulladdress,
    country, deliverypointsuffix, updatedate, entrydate,
    easting, northing, latitude, longitude, geometry,
    positionalaccuracy, geometryallocationmethod, matchtype, release_id
)
SELECT
    fixture.udprn, DATE '2026-11-06', TIMESTAMP '2026-11-06 00:00:00',
    'New', 'Address', 'Synthetic local fixture',
    'SYNTHETIC TEST ADDRESS', 1, 'TEST FIXTURE ROAD',
    CASE WHEN fixture.postcode = 'KT13 8GL' THEN 'WEYBRIDGE' ELSE 'CARDIFF' END, fixture.postcode,
    'Small User', CASE WHEN fixture.postcode = 'KT13 8GL' THEN 'KT' ELSE 'CF' END,
    split_part(fixture.postcode, ' ', 1),
    split_part(fixture.postcode, ' ', 1) || ' ' || left(split_part(fixture.postcode, ' ', 2), 1),
    'SYNTHETIC TEST ADDRESS, 1 TEST FIXTURE ROAD, '
        || CASE WHEN fixture.postcode = 'KT13 8GL' THEN 'WEYBRIDGE' ELSE 'CARDIFF' END
        || ', ' || fixture.postcode,
    CASE WHEN fixture.postcode = 'KT13 8GL' THEN 'England' ELSE 'Wales' END,
    '1A', DATE '2026-11-06', DATE '2026-11-06',
    fixture.easting, fixture.northing, fixture.latitude, fixture.longitude,
    ST_SetSRID(ST_MakePoint(fixture.easting, fixture.northing), 27700),
    'Postcode centroid', 'Synthetic fixture', 'Unmatched', 'local-synthetic-cardiff'
FROM (VALUES
    (2000000001, 'CF14 3AT', 316537, 178526, 51.499488, -3.203788),
    (2000000002, 'CF14 6RP', 315884, 181868, 51.529434, -3.213993),
    (2000000003, 'CF23 9XN', 320385, 178018, 51.495477, -3.148250),
    (2000000004, 'CF24 3DJ', 319186, 176922, 51.485453, -3.165266),
    (2000000005, 'CF10 2BY', 318350, 176466, 51.481234, -3.177198),
    (2000000006, 'CF24 5HB', 320338, 175904, 51.476466, -3.148450),
    (2000000007, 'CF10 1FG', 318382, 176117, 51.478101, -3.176657),
    (2000000008, 'CF10 2EF', 318568, 176261, 51.479422, -3.174012),
    (2000000009, 'CF10 1GA', 318459, 176189, 51.478759, -3.175565),
    (2000000010, 'CF10 2HQ', 318686, 176613, 51.482604, -3.172395),
    (2000000011, 'CF10 1PX', 318138, 176452, 51.481077, -3.180247),
    (2000000012, 'CF10 2DP', 318433, 176403, 51.480679, -3.175989),
    (2000000013, 'CF11 8TA', 317180, 174761, 51.465736, -3.193643),
    (2000000014, 'CF14 5XW', 317237, 181036, 51.522155, -3.194296),
    (2000000015, 'KT13 8GL', 507249, 163647, 51.361775, -0.460936)
) AS fixture(udprn, postcode, easting, northing, latitude, longitude);
