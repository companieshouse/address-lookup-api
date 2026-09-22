package uk.gov.companieshouse.addresslookup.releasecore.aurora;

import org.postgresql.PGConnection;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.SupplyType;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.UUID;

/** Native bulk SQL only. JdbcTemplate shares the JPA transaction and connection. */
@Repository
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
public class AuroraOperations {
  private final JdbcTemplate jdbc;

  public AuroraOperations(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private void begin(Dataset dataset, SupplyType type, UUID id) {
    jdbc.queryForObject("select set_config('os.release_id',?,true)", String.class, id.toString());
    jdbc.update("DELETE FROM " + dataset.landing(type) + " WHERE release_id=?", id);
  }

  public void importS3(
      Dataset dataset,
      SupplyType type,
      UUID id,
      String bucket,
      String key,
      String region,
      long rows)
      throws Exception {
    begin(dataset, type, id);
    jdbc.queryForObject(
        "SELECT aws_s3.table_import_from_s3(?,?,?,aws_commons.create_s3_uri(?,?,?))",
        String.class,
        dataset.landing(type),
        dataset.table().columnList(),
        "(format csv, header true, encoding 'UTF8')",
        bucket,
        key,
        region);
    verifyCount(dataset, type, id, rows);
  }

  public void importLocal(Dataset dataset, SupplyType type, UUID id, InputStream csv, long rows)
      throws Exception {
    begin(dataset, type, id);
    String sql =
        "COPY "
            + dataset.landing(type)
            + " ("
            + dataset.table().columnList()
            + ") FROM STDIN WITH (FORMAT csv,HEADER true,ENCODING 'UTF8')";
    jdbc.execute(
        (ConnectionCallback<Long>)
            c -> {
              try {
                return c.unwrap(PGConnection.class).getCopyAPI().copyIn(sql, csv);
              } catch (java.io.IOException e) {
                throw new java.sql.SQLException("Local COPY failed", e);
              }
            });
    verifyCount(dataset, type, id, rows);
  }

  private void verifyCount(Dataset dataset, SupplyType type, UUID id, long expected) {
    Long actual =
        jdbc.queryForObject(
            "SELECT count(*) FROM " + dataset.landing(type) + " WHERE release_id=?", Long.class, id);
    if (expected < 0 || actual == null || actual != expected)
      throw new IllegalArgumentException("OS recordCount mismatch: " + dataset.datasetName());
  }

  public void prepare(Dataset dataset, SupplyType type, UUID id, LocalDate target) {
    jdbc.update(dataset.prepareCall(type), id, target);
  }

  public void requireEmptyLiveTables() throws Exception {
    for (var dataset : Dataset.values()) {
      Long rows = jdbc.queryForObject("SELECT count(*) FROM " + dataset.table().data(), Long.class);
      if (rows == null || rows != 0)
        throw new IllegalArgumentException("Full local import requires empty live tables");
    }
  }

  public void promote(SupplyType type, UUID id) {
    jdbc.update(
        type == SupplyType.COU ? "CALL os_ingest.promote_cou(?)" : "CALL os_ingest.promote_full(?)",
        id);
  }
}
