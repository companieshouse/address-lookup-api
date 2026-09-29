--liquibase formatted sql

--changeset address-lookup-api:000-create-marker contextFilter:!local
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE lower(table_name) = 'marker'
CREATE TABLE marker (label varchar(20) NOT NULL);
INSERT INTO marker (label) VALUES ('${marker.label}');
