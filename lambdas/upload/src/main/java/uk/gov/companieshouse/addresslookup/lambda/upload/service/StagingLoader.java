package uk.gov.companieshouse.addresslookup.lambda.upload.service;

import java.io.InputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
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

  private static final String INSERT_MANIFEST =
      "INSERT INTO " + MANIFEST_TABLE + " (release_id, dataset, supply_type, previous_release,"
          + " target_release, data_package_id, version_id, schema_version, record_count)"
          + " VALUES (?::uuid,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING";
  private static final String MANIFEST_MATCHES =
      "SELECT EXISTS (SELECT 1 FROM " + MANIFEST_TABLE
          + " WHERE release_id=?::uuid AND dataset=? AND supply_type=?"
          + " AND previous_release IS NOT DISTINCT FROM ?::date"
          + " AND target_release=? AND data_package_id=? AND version_id=?"
          + " AND schema_version=? AND record_count=?)";
  private static final String SELECT_MANIFEST =
      "SELECT supply_type, schema_version, record_count FROM " + MANIFEST_TABLE
          + " WHERE release_id=?::uuid AND dataset=?";
  private static final String LOCK_DATASET =
      "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text";
  private static final String LEDGER_HAS_DATASET =
      "SELECT EXISTS (SELECT 1 FROM " + LEDGER_TABLE + " WHERE release_id=?::uuid AND dataset=?)";
  private static final String INSERT_LEDGER =
      "INSERT INTO " + LEDGER_TABLE + " (release_id, dataset, file, row_count)"
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
   * A redelivered manifest is a no-op; a different manifest for the same release is permanent.
   */
  public void recordManifest(ReleaseManifest manifest) {
    transaction.executeWithoutResult(
        status ->
            manifest
                .files()
                .forEach(
                    (dataset, file) -> {
                      // Same order as the INSERT columns and the EXISTS predicates.
                      Object[] row = {
                        manifest.id().toString(),
                        dataset.datasetName(),
                        manifest.type().name(),
                        manifest.previous(),
                        manifest.target(),
                        manifest.dataPackageId(),
                        manifest.versionId(),
                        file.summary().schemaVersion(),
                        file.summary().recordCount()
                      };
                      jdbc.update(INSERT_MANIFEST, row);
                      if (!Boolean.TRUE.equals(
                          jdbc.queryForObject(MANIFEST_MATCHES, Boolean.class, row))) {
                        throw new PermanentFailureException(
                            PermanentFailureException.MANIFEST_CONFLICT,
                            "A different manifest is already recorded for dataset "
                                + dataset.datasetName());
                      }
                    }));
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

  /**
   * Returns false when the ledger already records the dataset for the release, so redeliveries and
   * re-extracted (new version, same content) CSVs are harmless and never double count. The ledger
   * row count is the number of rows actually landed.
   */
  public boolean load(Dataset dataset, StagedManifest staged, String file, InputStream csv) {
    String releaseId = staged.releaseId().toString();
    return Boolean.TRUE.equals(
        transaction.execute(
            status -> {
              // Serialises duplicate deliveries of the same dataset; released at commit.
              jdbc.queryForObject(
                  LOCK_DATASET, String.class, releaseId + "/" + dataset.datasetName());
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

  /** Bad data cannot succeed on retry; anything else (connections, locks) can. */
  private static RuntimeException classify(Exception e) {
    if (e instanceof IllegalArgumentException) {
      return new PermanentFailureException(
          PermanentFailureException.ROW_COUNT_MISMATCH, e.getMessage());
    }
    for (Throwable t = e; t != null; t = t.getCause()) {
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
