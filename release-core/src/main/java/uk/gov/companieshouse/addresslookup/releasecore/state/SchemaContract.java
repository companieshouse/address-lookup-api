package uk.gov.companieshouse.addresslookup.releasecore.state;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "schema_contract", schema = "os_control")
public class SchemaContract {
  @Id
  private String dataset;
  private String version;

  protected SchemaContract() {}

  public String getVersion() {
    return version;
  }
}
