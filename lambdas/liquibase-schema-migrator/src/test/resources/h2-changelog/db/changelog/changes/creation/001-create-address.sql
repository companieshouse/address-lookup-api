--liquibase formatted sql

--changeset address-lookup-api:001-create-address
CREATE TABLE address (id integer PRIMARY KEY, postcode varchar(8) NOT NULL);
