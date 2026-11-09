package uk.gov.companieshouse.addresslookup.lambda.upload.runtime;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.check;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The RDS Proxy endpoint and the database role the Lambda signs in as. */
@ConfigurationProperties("upload.db")
public record DatabaseProperties(
    String host, @DefaultValue("5432") int port, String name, String user) {
  public DatabaseProperties {
    check(host != null && !host.isBlank(), "Missing DB_HOST");
    check(name != null && !name.isBlank(), "Missing DB_NAME");
    check(user != null && !user.isBlank(), "Missing DB_USER");
    check(port > 0, "Invalid DB_PORT");
  }
}
