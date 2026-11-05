-- Jira: ALS-95
-- Purpose: Experiment with on-demand Royal Mail v2 database artefact creation.
-- Author/team: Address Lookup Service
-- Date: 2026-11-03
CREATE OR REPLACE PROCEDURE os_ingest.create_gb_royalmailaddress_table()
LANGUAGE plpgsql
AS $$
BEGIN
    CREATE TABLE IF NOT EXISTS os_data.add_gb_royalmailaddress_v2 (
        udprn integer NOT NULL,
        versiondate date NOT NULL,
        versionavailablefromdate timestamp NOT NULL,
        versionavailabletodate timestamp,
        changetype varchar(50) NOT NULL,
        theme varchar(40) NOT NULL,
        description varchar(25) NOT NULL,
        organisationname varchar(60),
        departmentname varchar(60),
        subbuildingname varchar(30),
        buildingname varchar(50),
        buildingnumber integer,
        dependentthoroughfare varchar(80),
        thoroughfare varchar(80),
        doubledependentlocality varchar(35),
        dependentlocality varchar(35),
        posttown varchar(30) NOT NULL,
        postcode varchar(8) NOT NULL,
        postcodetype varchar(10) NOT NULL,
        postcodearea varchar(2) NOT NULL,
        postcodedistrict varchar(4) NOT NULL,
        postcodesector varchar(6) NOT NULL,
        pifchecksumdigit varchar(1),
        pifdisplaytext varchar(50),
        fulladdress varchar(1000) NOT NULL,
        country varchar(25),
        deliverypointsuffix varchar(2) NOT NULL,
        welshdependentthoroughfare varchar(80),
        welshthoroughfare varchar(80),
        welshdoubledependentlocality varchar(35),
        welshdependentlocality varchar(35),
        welshposttown varchar(30),
        poboxnumber varchar(13),
        updatedate date NOT NULL,
        entrydate date NOT NULL,
        easting numeric(8,2) NOT NULL,
        northing numeric(9,2) NOT NULL,
        latitude numeric(9,7) NOT NULL,
        longitude numeric(9,7) NOT NULL,
        geometry geometry(Point, 27700) NOT NULL,
        positionalaccuracy varchar(25) NOT NULL,
        geometryallocationmethod varchar(25) NOT NULL,
        unmatchedreason varchar(100),
        unmatchedreasondate date,
        uprn bigint,
        matchedaddressfeaturetype varchar(25),
        primaryuprnmatch varchar(5),
        matchtype varchar(20) NOT NULL,
        matchdate date,
        matchmethod varchar(10),
        matchingorganisation varchar(25),
        release_id text NOT NULL
    );
END;
$$;

CREATE OR REPLACE PROCEDURE os_ingest.create_isl_royalmailaddress_table()
LANGUAGE plpgsql
AS $$
BEGIN
    CREATE TABLE IF NOT EXISTS os_data.add_isl_royalmailaddress_v2 (
        udprn integer NOT NULL,
        versiondate date NOT NULL,
        versionavailablefromdate timestamp NOT NULL,
        versionavailabletodate timestamp,
        changetype varchar(50) NOT NULL,
        theme varchar(40) NOT NULL,
        description varchar(25) NOT NULL,
        organisationname varchar(60),
        departmentname varchar(60),
        subbuildingname varchar(30),
        buildingname varchar(50),
        buildingnumber integer,
        dependentthoroughfare varchar(80),
        thoroughfare varchar(80),
        doubledependentlocality varchar(35),
        dependentlocality varchar(35),
        posttown varchar(30) NOT NULL,
        postcode varchar(8) NOT NULL,
        postcodetype varchar(10) NOT NULL,
        postcodearea varchar(2) NOT NULL,
        postcodedistrict varchar(4) NOT NULL,
        postcodesector varchar(6) NOT NULL,
        pifchecksumdigit varchar(1),
        pifdisplaytext varchar(50),
        fulladdress varchar(1000) NOT NULL,
        country varchar(25),
        deliverypointsuffix varchar(2) NOT NULL,
        welshdependentthoroughfare varchar(80),
        welshthoroughfare varchar(80),
        welshdoubledependentlocality varchar(35),
        welshdependentlocality varchar(35),
        welshposttown varchar(30),
        poboxnumber varchar(13),
        updatedate date NOT NULL,
        entrydate date NOT NULL,
        easting numeric(8,2),
        northing numeric(9,2),
        latitude numeric(9,7) NOT NULL,
        longitude numeric(9,7) NOT NULL,
        geometry geometry(Point, 4258) NOT NULL,
        positionalaccuracy varchar(25) NOT NULL,
        geometryallocationmethod varchar(25) NOT NULL,
        unmatchedreason varchar(100),
        unmatchedreasondate date,
        uprn bigint,
        matchedaddressfeaturetype varchar(25),
        primaryuprnmatch varchar(5),
        matchtype varchar(20) NOT NULL,
        matchdate date,
        matchmethod varchar(10),
        matchingorganisation varchar(25),
        release_id text NOT NULL
    );
END;
$$;

CREATE OR REPLACE PROCEDURE os_ingest.add_gb_royalmailaddress_primary_index()
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'os_data.add_gb_royalmailaddress_v2'::regclass
          AND contype = 'p'
    ) THEN
        ALTER TABLE os_data.add_gb_royalmailaddress_v2
            ADD CONSTRAINT add_gb_royalmailaddress_v2_pkey PRIMARY KEY (udprn);
    END IF;
END;
$$;

CREATE OR REPLACE PROCEDURE os_ingest.add_isl_royalmailaddress_primary_index()
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'os_data.add_isl_royalmailaddress_v2'::regclass
          AND contype = 'p'
    ) THEN
        ALTER TABLE os_data.add_isl_royalmailaddress_v2
            ADD CONSTRAINT add_isl_royalmailaddress_v2_pkey PRIMARY KEY (udprn);
    END IF;
END;
$$;

CREATE OR REPLACE PROCEDURE os_ingest.add_gb_royalmailaddress_secondary_indexes()
LANGUAGE plpgsql
AS $$
BEGIN
    CREATE INDEX IF NOT EXISTS add_gb_royalmailaddress_v2_postcode_idx
        ON os_data.add_gb_royalmailaddress_v2 (upper(replace(postcode, ' ', '')));
    CREATE INDEX IF NOT EXISTS add_gb_royalmailaddress_v2_thoroughfare_idx
        ON os_data.add_gb_royalmailaddress_v2 (thoroughfare);
END;
$$;

CREATE OR REPLACE PROCEDURE os_ingest.add_isl_royalmailaddress_secondary_indexes()
LANGUAGE plpgsql
AS $$
BEGIN
    CREATE INDEX IF NOT EXISTS add_isl_royalmailaddress_v2_postcode_idx
        ON os_data.add_isl_royalmailaddress_v2 (upper(replace(postcode, ' ', '')));
    CREATE INDEX IF NOT EXISTS add_isl_royalmailaddress_v2_thoroughfare_idx
        ON os_data.add_isl_royalmailaddress_v2 (thoroughfare);
END;
$$;

CREATE OR REPLACE PROCEDURE os_ingest.create_address_lookup_view()
LANGUAGE plpgsql
AS $$
BEGIN
    CREATE OR REPLACE VIEW address_lookup.address_lookup AS
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
END;
$$;

INSERT INTO os_control.index_definition (
    dataset, table_name, index_name, index_type, definition
)
VALUES
    (
        'add_gb_royalmailaddress',
        'os_data.add_gb_royalmailaddress_v2',
        'add_gb_royalmailaddress_v2_thoroughfare_idx',
        'index',
        'thoroughfare'
    ),
    (
        'add_isl_royalmailaddress',
        'os_data.add_isl_royalmailaddress_v2',
        'add_isl_royalmailaddress_v2_thoroughfare_idx',
        'index',
        'thoroughfare'
    );
