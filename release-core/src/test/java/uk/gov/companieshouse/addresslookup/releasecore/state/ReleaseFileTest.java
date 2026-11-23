package uk.gov.companieshouse.addresslookup.releasecore.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import uk.gov.companieshouse.addresslookup.releasecore.state.ReleaseFile.Status;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.SupplyType;

final class ReleaseFileTest {
  private final UUID releaseId = UUID.randomUUID();

  @Test
  void newFileWaitsWithoutLocation() {
    var file = new ReleaseFile(releaseId, Dataset.ADD_GB_ROYALMAILADDRESS);

    assertEquals(releaseId, file.getReleaseId());
    assertEquals("add_gb_royalmailaddress", file.getDataset());
    assertEquals(Status.WAITING, file.getStatus());
    assertNull(file.getBucket());
    assertNull(file.getChecksum());
    assertFalse(file.ready(SupplyType.COU));
  }

  @Test
  void readyRecordsLocationAndChecksum() {
    var file = new ReleaseFile(releaseId, Dataset.ADD_GB_ROYALMAILADDRESS);

    file.ready(SupplyType.FULL, "bucket", "key.csv", "v1", "abc");

    assertEquals(Status.FULL_READY, file.getStatus());
    assertEquals("bucket", file.getBucket());
    assertEquals("abc", file.getChecksum());
    assertTrue(file.ready(SupplyType.FULL));
    assertFalse(file.ready(SupplyType.COU));
  }

  @Test
  void couFilesBecomeResolved() {
    var file = new ReleaseFile(releaseId, Dataset.ADD_ISL_ROYALMAILADDRESS);

    file.ready(SupplyType.COU, "bucket", "key.csv", "v1", "abc");

    assertEquals(Status.RESOLVED, file.getStatus());
    assertTrue(file.ready(SupplyType.COU));
  }

  @Test
  void rejectsSecondReady() {
    var file = new ReleaseFile(releaseId, Dataset.ADD_GB_ROYALMAILADDRESS);
    file.ready(SupplyType.COU, "bucket", "key.csv", "v1", "abc");

    assertThrows(
        IllegalStateException.class,
        () -> file.ready(SupplyType.COU, "bucket", "key.csv", "v1", "abc"));
  }

  @Test
  void idIsDeterministicPerReleaseAndDataset() {
    var first = new ReleaseFile(releaseId, Dataset.ADD_GB_ROYALMAILADDRESS);
    var again = new ReleaseFile(releaseId, Dataset.ADD_GB_ROYALMAILADDRESS);
    var other = new ReleaseFile(releaseId, Dataset.ADD_ISL_ROYALMAILADDRESS);

    assertEquals(first.getId(), again.getId());
    assertNotEquals(first.getId(), other.getId());
  }
}
