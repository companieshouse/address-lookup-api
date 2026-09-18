package uk.gov.companieshouse.addresslookup.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "add_gb_builtaddress")
public class GbBuiltAddress extends BuiltAddress {
}
