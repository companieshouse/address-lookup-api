--liquibase formatted sql

--changeset address-lookup-api:002-create-address-view
CREATE VIEW address_view AS SELECT id, postcode, 'GB' AS address_source FROM address;
