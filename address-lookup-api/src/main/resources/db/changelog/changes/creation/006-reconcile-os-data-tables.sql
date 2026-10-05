--liquibase formatted sql

--changeset address-lookup-api:012-ensure-os-data-schema
CREATE SCHEMA IF NOT EXISTS os_data;

--changeset address-lookup-api:013-reconcile-renamed-address-tables splitStatements:false
DO $$
BEGIN
    IF to_regclass('os_data.add_gb_builtaddress_v3') IS NULL
            AND to_regclass('public.add_gb_builtaddress_v3') IS NOT NULL THEN
        ALTER TABLE public.add_gb_builtaddress_v3 SET SCHEMA os_data;
    END IF;

    IF to_regclass('os_data.add_isl_builtaddress_v3') IS NULL
            AND to_regclass('public.add_isl_builtaddress_v3') IS NOT NULL THEN
        ALTER TABLE public.add_isl_builtaddress_v3 SET SCHEMA os_data;
    END IF;

    IF to_regclass('os_data.add_gb_royalmailaddress_v1') IS NULL
            AND to_regclass('public.add_gb_royalmailaddress_v1') IS NOT NULL THEN
        ALTER TABLE public.add_gb_royalmailaddress_v1 SET SCHEMA os_data;
    END IF;

    IF to_regclass('os_data.add_isl_royalmailaddress_v1') IS NULL
            AND to_regclass('public.add_isl_royalmailaddress_v1') IS NOT NULL THEN
        ALTER TABLE public.add_isl_royalmailaddress_v1 SET SCHEMA os_data;
    END IF;
END;
$$;
