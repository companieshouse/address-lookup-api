-- Jira: ALS-83
-- Purpose: Register the GB and ISL Royal Mail v2 dataset and index contracts.
-- Author/team: Address Lookup Service
-- Date: 2026-10-13
-- The index_definition row shape is provisional for future import Lambda work.
CREATE TABLE os_control.schema_contract (
    dataset text NOT NULL,
    version text NOT NULL,
    CONSTRAINT schema_contract_pkey PRIMARY KEY (dataset)
);

INSERT INTO os_control.schema_contract (dataset, version)
VALUES
    ('add_gb_royalmailaddress', '2.0'),
    ('add_isl_royalmailaddress', '2.0');

CREATE TABLE os_control.index_definition (
    dataset text NOT NULL,
    table_name text NOT NULL,
    index_name text NOT NULL,
    index_type text NOT NULL CHECK (index_type IN ('primary_key', 'index')),
    definition text NOT NULL,
    CONSTRAINT index_definition_pkey PRIMARY KEY (dataset, index_name),
    CONSTRAINT index_definition_dataset_fkey
        FOREIGN KEY (dataset) REFERENCES os_control.schema_contract (dataset)
);

COMMENT ON TABLE os_control.index_definition IS
    'Provisional row contract; reconcile with the import Lambda promote_full consumer when that work is scoped.';

INSERT INTO os_control.index_definition (
    dataset, table_name, index_name, index_type, definition
)
VALUES
    (
        'add_gb_royalmailaddress',
        'os_data.add_gb_royalmailaddress_v2',
        'add_gb_royalmailaddress_v2_pkey',
        'primary_key',
        'udprn'
    ),
    (
        'add_gb_royalmailaddress',
        'os_data.add_gb_royalmailaddress_v2',
        'add_gb_royalmailaddress_v2_postcode_idx',
        'index',
        'upper(replace(postcode, '' '', ''''))'
    ),
    (
        'add_isl_royalmailaddress',
        'os_data.add_isl_royalmailaddress_v2',
        'add_isl_royalmailaddress_v2_pkey',
        'primary_key',
        'udprn'
    ),
    (
        'add_isl_royalmailaddress',
        'os_data.add_isl_royalmailaddress_v2',
        'add_isl_royalmailaddress_v2_postcode_idx',
        'index',
        'upper(replace(postcode, '' '', ''''))'
    );
