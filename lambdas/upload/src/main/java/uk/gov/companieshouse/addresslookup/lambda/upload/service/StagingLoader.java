package uk.gov.companieshouse.addresslookup.lambda.upload.service;

import java.io.InputStream;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.companieshouse.addresslookup.releasecore.aurora.AuroraOperations;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.ReleaseManifest;
import uk.gov.companieshouse.release.model.SupplyType;

/**
 * Records data package manifests, and loads each CSV into its landing table together with its
 * ledger row in a single transaction.
 */
@Component
public class StagingLoader {
  /** What the release manifest promised for one dataset. */
  public record StagedManifest(
      UUID releaseId, SupplyType type, String schemaVersion, long recordCount) {}

  private static final String MANIFEST_TABLE = "os_ingest.data_package_manifest";
  private static final String LEDGER_TABLE = "os_ingest.import_ledger";
  private static final String SELECT_EXISTS = "SELECT EXISTS (SELECT 1 FROM ";

  private static final String INSERT_MANIFEST =
      "INSERT INTO "
          + MANIFEST_TABLE
          + " (release_id, dataset, supply_type, previous_release,"
          + " target_release, data_package_id, version_id, schema_version, record_count)"
          + " VALUES (?::uuid,?,?,?,?,?,?,?,?)";
  private static final String RECORDED_MANIFEST =
      "SELECT dataset, supply_type, previous_release::text, target_release::text,"
          + " data_package_id, version_id, schema_version, record_count::text FROM "
          + MANIFEST_TABLE
          + " WHERE release_id=?::uuid";
  private static final String SELECT_MANIFEST =
      "SELECT supply_type, schema_version, record_count FROM "
          + MANIFEST_TABLE
          + " WHERE release_id=?::uuid AND dataset=?";
  private static final String MANIFEST_RECORDED =
      SELECT_EXISTS + MANIFEST_TABLE + " WHERE release_id=?::uuid)";
  private static final String LOCK_DATASET =
      "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text";
  private static final String LEDGER_HAS_DATASET =
      SELECT_EXISTS + LEDGER_TABLE + " WHERE release_id=?::uuid AND dataset=?)";
  private static final String INSERT_LEDGER =
      "INSERT INTO "
          + LEDGER_TABLE
          + " (release_id, dataset, file, row_count)"
          + " VALUES (?::uuid,?,?,?) ON CONFLICT DO NOTHING";

  private final AuroraOperations aurora;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;

  public StagingLoader(
      AuroraOperations aurora, JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.aurora = aurora;
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /**
   * Records every dataset the release promises so ingest can tell when a release is incomplete.
   * Recording is serialised per release. A redelivered manifest is a no-op; a manifest that adds,
   * omits or changes any dataset or metadata of the recorded release is permanent.
   */
  public void recordManifest(ReleaseManifest manifest) {
    String releaseId = manifest.id().toString();
    Map<String, List<String>> incoming = new LinkedHashMap<>();
    manifest
        .files()
        .forEach((dataset, file) ->
            incoming.put(
                dataset.datasetName(),
                Arrays.asList(
                    manifest.type().name(),
                    Objects.toString(manifest.previous(), null),
                    manifest.target().toString(),
                    manifest.dataPackageId(),
                    manifest.versionId(),
                    file.summary().schemaVersion(),
                    Long.toString(file.summary().recordCount()))));
    transaction.executeWithoutResult(status -> {
      jdbc.query(LOCK_DATASET, (RowCallbackHandler) rs -> {}, "manifest/" + releaseId);
      Map<String, List<String>> recorded = new LinkedHashMap<>();
      jdbc.query(
          RECORDED_MANIFEST,
          (RowCallbackHandler)
              rs ->
                  recorded.put(
                      rs.getString(1),
                      Arrays.asList(
                          rs.getString(2),
                          rs.getString(3),
                          rs.getString(4),
                          rs.getString(5),
                          rs.getString(6),
                          rs.getString(7),
                          rs.getString(8))),
          releaseId);
      if (recorded.isEmpty()) {
        manifest
            .files()
            .forEach((dataset, file) ->
                jdbc.update(
                    INSERT_MANIFEST,
                    releaseId,
                    dataset.datasetName(),
                    manifest.type().name(),
                    manifest.previous(),
                    manifest.target(),
                    manifest.dataPackageId(),
                    manifest.versionId(),
                    file.summary().schemaVersion(),
                    file.summary().recordCount()));
      } else if (!recorded.equals(incoming)) {
        throw new PermanentFailureException(
            PermanentFailureException.MANIFEST_CONFLICT,
            "A different manifest is already recorded for this release");
      }
    });
  }

  public Optional<StagedManifest> stagedManifest(UUID releaseId, Dataset dataset) {
    List<StagedManifest> rows =
        jdbc.query(
            SELECT_MANIFEST,
            (rs, i) ->
                new StagedManifest(
                    releaseId,
                    SupplyType.valueOf(rs.getString("supply_type")),
                    rs.getString("schema_version"),
                    rs.getLong("record_count")),
            releaseId.toString(),
            dataset.datasetName());
    return rows.stream().findFirst();
  }

  /** A manifest is recorded in one transaction, so any row means every promised dataset is known. */
  public boolean manifestRecorded(UUID releaseId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(MANIFEST_RECORDED, Boolean.class, releaseId.toString()));
  }

  /**
   * Returns false when the ledger already records the dataset for the release, so redeliveries and
   * re-extracted (new version, same content) CSVs are harmless and never double count. The ledger
   * row count is the number of rows actually landed.
   */
  public boolean load(Dataset dataset, StagedManifest staged, String file, InputStream csv) {
    String releaseId = staged.releaseId().toString();
    return Boolean.TRUE.equals(
        transaction.execute(status -> {
          // Serialises duplicate deliveries of the same dataset; released at commit.
          jdbc.query(
              LOCK_DATASET, (RowCallbackHandler) rs -> {}, releaseId + "/" + dataset.datasetName());
          if (Boolean.TRUE.equals(
              jdbc.queryForObject(
                  LEDGER_HAS_DATASET, Boolean.class, releaseId, dataset.datasetName()))) {
            return false;
          }
          long loaded;
          try {
            loaded =
                aurora.importLocal(
                    dataset, staged.type(), staged.releaseId(), csv, staged.recordCount());
          } catch (Exception e) {
            throw classify(e);
          }
          jdbc.update(INSERT_LEDGER, releaseId, dataset.datasetName(), file, loaded);
          return true;
        }));
  }

  /**
   * Bad data cannot succeed on retry; anything else (connections, locks) can. The cause chain is
   * walked because AuroraOperations is a {@code @Repository}: if its methods stopped declaring
   * {@code throws Exception}, Spring's exception translation would wrap IllegalArgumentException
   * in InvalidDataAccessApiUsageException.
   */
  private static RuntimeException classify(Exception e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof IllegalArgumentException mismatch) {
        return new PermanentFailureException(
            PermanentFailureException.ROW_COUNT_MISMATCH, mismatch.getMessage());
      }
      if (t instanceof SQLException sql
          && sql.getSQLState() != null
          && (sql.getSQLState().startsWith("22") || sql.getSQLState().startsWith("23"))) {
        // The database message can quote a CSV value, so it is deliberately not propagated.
        return new PermanentFailureException(
            PermanentFailureException.INVALID_CSV_DATA, "CSV rows rejected by the database");
      }
    }
    return new IllegalStateException("Landing load failed", e);
  }
}
