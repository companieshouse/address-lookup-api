package uk.gov.companieshouse.addresslookup.lambda.upload.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IamAuthDataSourceTest {
  @Test
  void requestsAFreshTokenForEveryPhysicalConnection() {
    var calls = new AtomicInteger();
    var source =
        new IamAuthDataSource(
            "jdbc:unsupported://nowhere/db",
            "upload_role",
            new Properties(),
            () -> "token-" + calls.incrementAndGet());

    assertThrows(SQLException.class, () -> source.getConnectionFromDriver(new Properties()));
    var properties = new Properties();
    assertThrows(SQLException.class, () -> source.getConnectionFromDriver(properties));

    assertEquals(2, calls.get());
    assertEquals("token-2", properties.getProperty("password"));
  }
}
