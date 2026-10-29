package uk.gov.companieshouse.addresslookup.dbmigrate;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Ordering guarantees that need no database: what is checked before anything is touched. */
class MigrationServiceTest {

  private final AtomicInteger bundleReads = new AtomicInteger();
  private final AtomicInteger credentialReads = new AtomicInteger();

  private MigrationService service(boolean allowRepair, byte[] snapshot) {
    return new MigrationService(
        new MigrationConfig("snapshot-bucket", "/address-lookup/test/db/schema-owner", allowRepair, "10s", 1_000_000, 100),
        (bucket, key, version) -> {
          bundleReads.incrementAndGet();
          return new ByteArrayInputStream(snapshot);
        },
        name -> {
          credentialReads.incrementAndGet();
          throw new AssertionError("credentials must not be read");
        },
        credentials -> {
          throw new AssertionError("must not connect");
        },
        new FlywayRunner());
  }

  @Test
  void checksumMismatchFailsBeforeCredentialsOrDatabase() throws Exception {
    byte[] snapshot = SnapshotBundleTest.zip(Map.of("migrations/V1__x.sql", "SELECT 1;"));
    var request = new MigrationRequest(Command.MIGRATE, "address-lookup-api/db-schema-snapshots/x.zip", "v1", "0".repeat(64));

    var e = assertThrows(MigrationException.class, () -> service(false, snapshot).handle(request));

    assertEquals(MigrationException.Code.BUNDLE_CHECKSUM_MISMATCH, e.code());
    assertEquals(0, credentialReads.get());
  }

  @Test
  void repairIsRefusedUnlessAllowedWithoutReadingAnything() {
    var request = new MigrationRequest(Command.REPAIR, "address-lookup-api/db-schema-snapshots/x.zip", "v1", "0".repeat(64));

    var e = assertThrows(MigrationException.class, () -> service(false, new byte[0]).handle(request));

    assertEquals(MigrationException.Code.REPAIR_DISABLED, e.code());
    assertEquals(0, bundleReads.get());
    assertEquals(0, credentialReads.get());
  }

  @Test
  void lockTimeoutMustBeAPlainDuration() {
    for (String bad : new String[] {"10", "10m", "0s", "10s'; DROP TABLE x; --", ""})
      assertThrows(
          IllegalStateException.class,
          () -> new MigrationConfig("b", "/p", false, bad, 1, 1),
          bad);
    assertEquals("500ms", new MigrationConfig("b", "/p", false, "500ms", 1, 1).lockTimeout());
  }
}
