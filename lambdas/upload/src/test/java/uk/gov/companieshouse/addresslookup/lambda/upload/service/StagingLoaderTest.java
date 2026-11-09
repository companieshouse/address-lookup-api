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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import uk.gov.companieshouse.addresslookup.lambda.upload.service.StagingLoader.StagedManifest;
import uk.gov.companieshouse.addresslookup.releasecore.aurora.AuroraOperations;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.OrderSummary;
import uk.gov.companieshouse.release.model.ReleaseManifest;
import uk.gov.companieshouse.release.model.SupplyType;

@ExtendWith(MockitoExtension.class)
class StagingLoaderTest {
  private static final UUID ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");
  private static final Dataset DATASET = Dataset.ADD_GB_ROYALMAILADDRESS;
  private static final StagedManifest STAGED = new StagedManifest(ID, SupplyType.FULL, "2.0", 5);
  private static final InputStream CSV = new ByteArrayInputStream(new byte[0]);
  private static final LocalDate PREVIOUS = LocalDate.of(2026, 4, 1);
  private static final LocalDate TARGET = LocalDate.of(2026, 5, 1);

  @Mock private ResultSet rs;
  @Mock private AuroraOperations aurora;
  @Mock private JdbcTemplate jdbc;
  @Mock private PlatformTransactionManager tx;
  @Mock private TransactionStatus status;

  private StagingLoader loader;

  @BeforeEach
  void setUp() {
    lenient().when(tx.getTransaction(any())).thenReturn(status);
    loader = new StagingLoader(aurora, jdbc, tx);
  }

  private void ledgerCount(int count) {
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

    var e =
        assertThrows(PermanentFailureException.class, () -> loader.load(DATASET, STAGED, "f", CSV));

    assertEquals(PermanentFailureException.ROW_COUNT_MISMATCH, e.code());
    verify(tx).rollback(any());
    verify(jdbc, never()).update(contains("import_ledger"), any(Object[].class));
  }

  @Test
  void rowCountMismatchTranslatedBySpringIsStillPermanent() throws Exception {
    ledgerCount(0);
    var mismatch =
        new IllegalArgumentException("OS recordCount mismatch: " + DATASET.datasetName());
    doThrow(new InvalidDataAccessApiUsageException(mismatch.getMessage(), mismatch))
        .when(aurora)
        .importLocal(any(), any(), any(), any(), anyLong());

    var e =
        assertThrows(PermanentFailureException.class, () -> loader.load(DATASET, STAGED, "f", CSV));

    assertEquals(PermanentFailureException.ROW_COUNT_MISMATCH, e.code());
    verify(tx).rollback(any());
  }

  @Test
  void invalidRowDataIsPermanentWithoutLeakingTheDatabaseMessage() throws Exception {
    ledgerCount(0);
    doThrow(new RuntimeException(new SQLException("invalid input: secret-value", "22P02")))
        .when(aurora)
        .importLocal(any(), any(), any(), any(), anyLong());

    var e =
        assertThrows(PermanentFailureException.class, () -> loader.load(DATASET, STAGED, "f", CSV));

    assertEquals(PermanentFailureException.INVALID_CSV_DATA, e.code());
    assertFalse(e.getMessage().contains("secret-value"));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"08006", "40P01"})
  void sqlFailuresOutsideDataAndIntegrityClassesAreTransient(String sqlState) throws Exception {
    ledgerCount(0);
    doThrow(new RuntimeException(new SQLException("connection lost", sqlState)))
        .when(aurora)
        .importLocal(any(), any(), any(), any(), anyLong());

    assertThrows(IllegalStateException.class, () -> loader.load(DATASET, STAGED, "f", CSV));
  }

  @Test
  void integrityViolationIsPermanent() throws Exception {
    ledgerCount(0);
    doThrow(new RuntimeException(new SQLException("duplicate key", "23505")))
        .when(aurora)
        .importLocal(any(), any(), any(), any(), anyLong());

    var e =
        assertThrows(PermanentFailureException.class, () -> loader.load(DATASET, STAGED, "f", CSV));

    assertEquals(PermanentFailureException.INVALID_CSV_DATA, e.code());
  }

  private void recordedManifest(String... row) {
    lenient()
        .doAnswer(inv -> {
          if (row.length > 0) {
            var resultSet = mock(ResultSet.class);
            for (int i = 0; i < row.length; i++)
              when(resultSet.getString(i + 1)).thenReturn(row[i]);
            inv.<RowCallbackHandler>getArgument(1).processRow(resultSet);
          }
          return null;
        })
        .when(jdbc)
        .query(
            contains("FROM os_ingest.data_package_manifest"),
            any(RowCallbackHandler.class),
            eq(ID.toString()));
  }

  @Test
  void recordsEveryPromisedDatasetInOneTransaction() {
    recordedManifest();

    loader.recordManifest(manifest());

    verify(jdbc)
        .update(
            contains("INSERT INTO os_ingest.data_package_manifest"),
            eq(ID.toString()),
            eq(DATASET.datasetName()),
            eq("COU"),
            eq(PREVIOUS),
            eq(TARGET),
            eq("pkg"),
            eq("ver"),
            eq("2.0"),
            eq(5L));
    verify(tx).commit(status);
  }

  @Test
  void redeliveredManifestIsANoOp() {
    recordedManifest(
        DATASET.datasetName(),
        "COU",
        PREVIOUS.toString(),
        TARGET.toString(),
        "pkg",
        "ver",
        "2.0",
        "5");

    loader.recordManifest(manifest());

    verify(jdbc, never())
        .update(contains("INSERT INTO os_ingest.data_package_manifest"), any(Object[].class));
    verify(tx).commit(status);
  }

  @Test
  void manifestWithDifferentMetadataIsPermanentAndRollsBack() {
    recordedManifest(
        DATASET.datasetName(), "COU", PREVIOUS.toString(), "2026-06-01", "pkg", "ver", "2.0", "5");
    var manifest = manifest();

    var e = assertThrows(PermanentFailureException.class, () -> loader.recordManifest(manifest));

    assertEquals(PermanentFailureException.MANIFEST_CONFLICT, e.code());
    verify(jdbc, never())
        .update(contains("INSERT INTO os_ingest.data_package_manifest"), any(Object[].class));
    verify(tx).rollback(status);
    verify(tx, never()).commit(any());
  }

  @Test
  void manifestWithADifferentDatasetSetIsPermanent() {
    recordedManifest(
        Dataset.ADD_ISL_ROYALMAILADDRESS.datasetName(),
        "COU",
        PREVIOUS.toString(),
        TARGET.toString(),
        "pkg",
        "ver",
        "2.0",
        "5");
    var manifest = manifest();
    var e = assertThrows(PermanentFailureException.class, () -> loader.recordManifest(manifest));

    assertEquals(PermanentFailureException.MANIFEST_CONFLICT, e.code());
  }

  @Test
  void mapsTheStagedManifestRowForTheDataset() throws Exception {
    when(rs.getString("supply_type")).thenReturn("COU");
    when(rs.getString("schema_version")).thenReturn("2.0");
    when(rs.getLong("record_count")).thenReturn(7L);
    when(jdbc.query(
            contains("FROM os_ingest.data_package_manifest"),
            ArgumentMatchers.<RowMapper<StagedManifest>>any(),
            eq(ID.toString()),
            eq(DATASET.datasetName())))
        .thenAnswer(inv -> List.of(inv.<RowMapper<StagedManifest>>getArgument(1).mapRow(rs, 0)));

    assertEquals(
        Optional.of(new StagedManifest(ID, SupplyType.COU, "2.0", 7)),
        loader.stagedManifest(ID, DATASET));
  }

  @Test
  void noStagedManifestWhenTheDatasetWasNotPromised() {
    when(jdbc.query(
            contains("FROM os_ingest.data_package_manifest"),
            ArgumentMatchers.<RowMapper<StagedManifest>>any(),
            eq(ID.toString()),
            eq(DATASET.datasetName())))
        .thenReturn(List.of());

    assertTrue(loader.stagedManifest(ID, DATASET).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void reportsWhetherTheReleaseManifestIsRecorded(boolean recorded) {
    when(jdbc.queryForObject(
            contains("FROM os_ingest.data_package_manifest WHERE release_id=?::uuid)"),
            eq(Boolean.class),
            eq(ID.toString())))
        .thenReturn(recorded);

    assertEquals(recorded, loader.manifestRecorded(ID));
  }

  private static ReleaseManifest manifest() {
    var summary = new OrderSummary("feature", "crs", "2.0", PREVIOUS, TARGET, 5);
    return new ReleaseManifest(
        ID,
        SupplyType.COU,
        PREVIOUS,
        TARGET,
        "pkg",
        "ver",
        Map.of(DATASET, new ReleaseManifest.FileMetadata(summary, null)),
        "{}");
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
