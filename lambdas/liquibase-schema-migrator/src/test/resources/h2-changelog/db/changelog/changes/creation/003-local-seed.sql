--liquibase formatted sql

--changeset address-lookup-api:003-local-seed contextFilter:local
INSERT INTO address (id, postcode) VALUES (1, 'CF14 3UZ');
