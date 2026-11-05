package uk.gov.companieshouse.addresslookup.lambda.upload.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import uk.gov.companieshouse.addresslookup.lambda.upload.service.StagingLoader.StagedManifest;
import uk.gov.companieshouse.addresslookup.releasecore.aurora.AuroraOperations;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.SupplyType;

@ExtendWith(MockitoExtension.class)
class StagingLoaderTest {
  private static final UUID ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");
  private static final Dataset DATASET = Dataset.ADD_GB_ROYALMAILADDRESS;
  private static final StagedManifest STAGED = new StagedManifest(ID, SupplyType.FULL, "2.0", 5);
  private static final InputStream CSV = new ByteArrayInputStream(new byte[0]);

  @Mock private AuroraOperations aurora;
  @Mock private JdbcTemplate jdbc;
  @Mock private PlatformTransactionManager tx;
  @Mock private TransactionStatus status;

  private StagingLoader loader;

  @BeforeEach
  void setUp() {
    when(tx.getTransaction(any())).thenReturn(status);
    loader = new StagingLoader(aurora, jdbc, tx);
  }

  private void ledgerCount(int count) {
    when(jdbc.queryForObject(contains("pg_advisory_xact_lock"), eq(String.class), any(Object[].class)))
        .thenReturn("");
    when(jdbc.queryForObject(contains("import_ledger"), eq(Boolean.class), any(Object[].class)))
        .thenReturn(count > 0);
  }

  @Test
  void recordsTheActualLoadedRowCountInTheLedgerAndCommits() throws Exception {
    ledgerCount(0);
    when(aurora.importLocal(DATASET, SupplyType.FULL, ID, CSV, 5)).thenReturn(5L);

    assertTrue(loader.load(DATASET, STAGED, "f", CSV));

    verify(jdbc)
        .update(
            contains("INSERT INTO os_ingest.import_ledger"),
            eq(ID.toString()),
            eq(DATASET.datasetName()),
            eq("f"),
            eq(5L));
    verify(tx).commit(any());
  }

  @Test
  void skipsDatasetAlreadyInLedger() throws Exception {
    ledgerCount(1);

    assertFalse(loader.load(DATASET, STAGED, "f", CSV));

    verify(aurora, never()).importLocal(any(), any(), any(), any(), anyLong());
    verify(jdbc, never()).update(contains("import_ledger"), any(Object[].class));
  }

  @Test
  void rowCountMismatchIsPermanentAndRollsBack() throws Exception {
    ledgerCount(0);
    doThrow(new IllegalArgumentException("expected 5 rows"))
        .when(aurora)
        .importLocal(any(), any(), any(), any(), anyLong());

    var e = assertThrows(PermanentFailureException.class, () -> loader.load(DATASET, STAGED, "f", CSV));

    assertEquals(PermanentFailureException.ROW_COUNT_MISMATCH, e.code());
    verify(tx).rollback(any());
    verify(jdbc, never()).update(contains("import_ledger"), any(Object[].class));
  }

  @Test
  void invalidRowDataIsPermanentWithoutLeakingTheDatabaseMessage() throws Exception {
    ledgerCount(0);
    doThrow(new RuntimeException(new SQLException("invalid input: secret-value", "22P02")))
        .when(aurora)
        .importLocal(any(), any(), any(), any(), anyLong());

    var e = assertThrows(PermanentFailureException.class, () -> loader.load(DATASET, STAGED, "f", CSV));

    assertEquals(PermanentFailureException.INVALID_CSV_DATA, e.code());
    assertFalse(e.getMessage().contains("secret-value"));
  }

  @Test
  void otherFailuresAreTransient() throws Exception {
    ledgerCount(0);
    doThrow(new java.io.IOException("copy"))
        .when(aurora)
        .importLocal(any(), any(), any(), any(), anyLong());

    var e = assertThrows(IllegalStateException.class, () -> loader.load(DATASET, STAGED, "f", CSV));

    assertEquals("Landing load failed", e.getMessage());
    verify(tx).rollback(any());
  }
}
