-- Jira: ALS-83
-- Purpose: Enable PostGIS and create  the database-feed schemas.
-- Author/team: Address Lookup Service
-- Date: 2026-10-13
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE SCHEMA os_control;
CREATE SCHEMA os_stage;
CREATE SCHEMA os_data;
CREATE SCHEMA os_prev;
CREATE SCHEMA os_ingest;
CREATE SCHEMA address_lookup;
