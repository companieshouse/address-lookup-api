package uk.gov.companieshouse.addresslookup.releasecore.aurora;

import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.SupplyType;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.UUID;

import static uk.gov.companieshouse.addresslookup.releasecore.persistence.Jdbc.*;

/** PostgreSQL bulk operations only. No method commits; caller owns the transaction. */
public final class AuroraGateway {
    private final Connection connection;
    public AuroraGateway(Connection connection) { this.connection = connection; }

    private void beginImport(Dataset dataset, SupplyType mode, UUID run) throws Exception {
        if (connection.getAutoCommit()) throw new IllegalStateException("Bulk import requires a transaction");
        exec(connection, "SELECT pg_advisory_xact_lock(781234991)");
        if (count(connection, "SELECT count(*) FROM os_control.release WHERE run_id=?", run) != 0)
            throw new IllegalStateException("Run already promoted");
        exec(connection, "SELECT set_config('os.run_id',?,true)", run.toString());
        exec(connection, "DELETE FROM " + dataset.landing(mode) + " WHERE run_id=?", run);
        exec(connection, "DELETE FROM os_control.dataset_ready WHERE run_id=? AND dataset=?", run, dataset.datasetName());
    }

    public void importS3(Dataset dataset, SupplyType mode, UUID run, String bucket, String key,
                         String region, long expected) throws Exception {
        beginImport(dataset, mode, run);
        exec(connection, "SELECT aws_s3.table_import_from_s3(?,?,?,aws_commons.create_s3_uri(?,?,?))",
            dataset.landing(mode), dataset.table().columnList(), "(format csv, header true, encoding 'UTF8')", bucket, key, region);
        verifyCount(dataset, mode, run, expected);
    }

    private void verifyCount(Dataset dataset, SupplyType mode, UUID run, long expected) throws Exception {
        if (expected < 0 || count(connection, "SELECT count(*) FROM " + dataset.landing(mode) + " WHERE run_id=?", run) != expected)
            throw new IllegalArgumentException("OS recordCount mismatch: " + dataset.datasetName());
    }

    public void prepare(Dataset dataset, SupplyType mode, UUID run, LocalDate target) throws Exception {
        exec(connection, dataset.prepareCall(mode), run, target);
    }

    public void validateCou(UUID run) throws Exception {
        exec(connection, "CALL os_ingest.validate_cou(?)", run);
    }

    public void promote(SupplyType mode, UUID run, LocalDate previous, LocalDate target,
                        String digest, String manifest) throws Exception {
        String call = mode == SupplyType.COU ? "CALL os_ingest.promote_cou(?,?,?,?,?::jsonb)"
                                            : "CALL os_ingest.promote_full(?,?,?,?,?::jsonb)";
        exec(connection, call, run, previous, target, digest, manifest);
    }
}
