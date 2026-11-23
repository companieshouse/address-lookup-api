package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uk.gov.companieshouse.addresslookup.lambda.shared.testsupport.FakeS3;

final class ReconciliationCursorTest {
  private static final String CURSOR = "reconciliation/sweep.json";

  private final FakeS3 s3 = new FakeS3();
  private ReconciliationCursor cursor;

  @BeforeEach
  void setUp() {
    cursor = new ReconciliationCursor(s3.client(), "bucket", "incoming/", "sweep");
  }

  private void store(String key) {
    s3.objects.put(key, new byte[0]);
  }

  private void storeCursor(String json) {
    s3.objects.put(CURSOR, json.getBytes(UTF_8));
  }

  @Test
  void emptyPrefixYieldsNothing() throws IOException {
    assertEquals(Optional.empty(), cursor.next());
  }

  @Test
  void advancesThroughKeysAndWrapsAround() throws IOException {
    store("incoming/a.csv");
    store("incoming/b.csv");

    assertEquals(Optional.of("incoming/a.csv"), cursor.next());
    assertEquals(Optional.of("incoming/b.csv"), cursor.next());
    assertEquals(Optional.of("incoming/a.csv"), cursor.next());
    assertTrue(new String(s3.objects.get(CURSOR), UTF_8).contains("\"after\":\"incoming/a.csv\""));
  }

  @Test
  void resumesAfterStoredPosition() throws IOException {
    store("incoming/a.csv");
    store("incoming/b.csv");
    storeCursor("{\"after\":\"incoming/a.csv\"}");

    assertEquals(Optional.of("incoming/b.csv"), cursor.next());
  }

  @Test
  void rejectsOversizedCursor() {
    store("incoming/a.csv");
    storeCursor("{\"after\":\"incoming/" + "x".repeat(5000) + "\"}");

    var ex = assertThrows(IllegalArgumentException.class, cursor::next);

    assertEquals("Reconciliation cursor too large", ex.getMessage());
  }

  @Test
  void rejectsCursorOutsidePrefix() {
    store("incoming/a.csv");
    storeCursor("{\"after\":\"other/a.csv\"}");

    var ex = assertThrows(IllegalArgumentException.class, cursor::next);

    assertEquals("Unexpected reconciliation cursor prefix", ex.getMessage());
  }

  @Test
  void rejectsCursorWithoutPosition() {
    store("incoming/a.csv");
    storeCursor("{}");

    assertThrows(IllegalArgumentException.class, cursor::next);
  }

  @Test
  void retriesAfterLosingRace() throws IOException {
    store("incoming/a.csv");
    s3.putConflicts = 1;

    assertEquals(Optional.of("incoming/a.csv"), cursor.next());
  }

  @Test
  void failsWhenCursorStaysContended() {
    store("incoming/a.csv");
    s3.putConflicts = 5;

    var ex = assertThrows(IllegalStateException.class, cursor::next);

    assertEquals("Reconciliation cursor busy; retry next invocation", ex.getMessage());
  }

  @Test
  void propagatesUnexpectedStorageErrors() {
    store("incoming/a.csv");
    s3.putErrorStatus = 500;

    assertThrows(S3Exception.class, cursor::next);
  }
}
