-- Jira: ALS-143
-- Purpose: Register the built address and pre-build address datasets (schema 3.0) in the contracts.
-- Author/team: Address Lookup Service
-- Date: 2026-11-20
-- The Royal Mail active tables keep their _v2 names while the new tables are unsuffixed, so the
-- contract records each dataset's active table instead of the FULL procedures deriving it.
-- The new datasets are registered disabled: Discovery, the release manifest model and the Dataset
-- enum support only the two Royal Mail datasets, so FULL manifests carry no recordCount for them.
-- The FULL procedures only process enabled datasets; set enabled = true once that support ships.
ALTER TABLE os_control.schema_contract ADD COLUMN active_table text;
ALTER TABLE os_control.schema_contract ADD COLUMN enabled boolean NOT NULL DEFAULT true;

UPDATE os_control.schema_contract SET active_table = dataset || '_v2';

INSERT INTO os_control.schema_contract (dataset, version, active_table, enabled)
VALUES
    ('add_gb_builtaddress', '3.0', 'add_gb_builtaddress', false),
    ('add_gb_prebuildaddress', '3.0', 'add_gb_prebuildaddress', false),
    ('add_isl_builtaddress', '3.0', 'add_isl_builtaddress', false),
    ('add_isl_prebuildaddress', '3.0', 'add_isl_prebuildaddress', false);

ALTER TABLE os_control.schema_contract ALTER COLUMN active_table SET NOT NULL;

-- Primary key plus the postcode lookup index, as for Royal Mail. The index list is to be agreed in review.
INSERT INTO os_control.index_definition (
    dataset, table_name, index_name, index_type, definition
)
SELECT
    d.dataset,
    'os_data.' || d.dataset,
    d.dataset || x.suffix,
    x.index_type,
    x.definition
FROM (
    VALUES
        ('add_gb_builtaddress'),
        ('add_gb_prebuildaddress'),
        ('add_isl_builtaddress'),
        ('add_isl_prebuildaddress')
) AS d (dataset)
CROSS JOIN (
    VALUES
        ('_pkey', 'primary_key', 'uprn'),
        ('_postcode_idx', 'index', 'upper(replace(postcode, '' '', ''''))')
) AS x (suffix, index_type, definition);
