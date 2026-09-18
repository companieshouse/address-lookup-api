package uk.gov.companieshouse.addresslookup.releasecore.persistence;

import uk.gov.companieshouse.addresslookup.releasecore.domain.Dataset;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static uk.gov.companieshouse.addresslookup.releasecore.persistence.Jdbc.*;

public final class ControlRepository {
    private final Connection connection;

    public ControlRepository(Connection connection) {
        this.connection = connection;
    }

    public void failureDetail(String reason, UUID run) throws Exception {
        exec(connection, "UPDATE os_control.workflow SET detail=? WHERE run_id=? AND state='ACTIVE'" , reason, run);
    }

    public void failureAudit(String reason, UUID run) throws Exception {
        exec(connection, "UPDATE os_control.run SET detail=? WHERE id=? AND status='RUNNING'" , reason, run);
    }

    public void enqueue(UUID run, String dataset, String action) throws Exception {
        exec(connection, "INSERT INTO os_control.outbox(run_id,dataset,action) VALUES (?,?,?) ON CONFLICT DO NOTHING" , run, dataset, action);
    }

    public Map<String, Object> lockFile(UUID run, String dataset) throws Exception {
        return one(connection, "SELECT f.*,w.mode,w.target,w.previous,w.state workflow_state FROM os_control.file f JOIN os_control.workflow w USING(run_id) WHERE f.run_id=? AND dataset=? FOR UPDATE OF f" , run, dataset);
    }

    public void blockFile(UUID run, String dataset) throws Exception {
        exec(connection, "UPDATE os_control.file SET state='BLOCKED' WHERE run_id=? AND dataset=?" , run, dataset);
    }

    public void blockWorkflow(String reason, UUID run) throws Exception {
        exec(connection, "UPDATE os_control.workflow SET state='BLOCKED',detail=? WHERE run_id=? AND state<>'COMPLETED'" , reason, run);
    }

    public void failRun(String reason, UUID run) throws Exception {
        exec(connection, "UPDATE os_control.run SET status='FAILED',detail=? WHERE id=? AND status<>'COMPLETED'" , reason, run);
    }

    public void lockRelease() throws Exception {
        exec(connection, "SELECT pg_advisory_xact_lock(781234991)");
    }

    public long activeCount() throws Exception {
        return count(connection, "SELECT count(*) FROM os_control.workflow WHERE state='ACTIVE'");
    }

    public Map<String, Object> watermark() throws Exception {
        return one(connection, "SELECT valid_from FROM os_control.watermark WHERE singleton");
    }

    public long requestCount(String request) throws Exception {
        return count(connection, "SELECT count(*) FROM os_control.workflow WHERE request_id=?" , request);
    }

    public long targetCount(LocalDate target) throws Exception {
        return count(connection, "SELECT count(*) FROM os_control.workflow WHERE target=?" , target);
    }

    public void createRun(UUID run, LocalDate target) throws Exception {
        exec(connection, "INSERT INTO os_control.run(id,status,release_date) VALUES (?,'RUNNING',?)" , run, target);
    }

    public void createWorkflow(UUID run, String request, String mode, LocalDate previous, LocalDate target, String digest, String manifest) throws Exception {
        exec(connection, "INSERT INTO os_control.workflow(run_id,request_id,mode,previous,target,digest,manifest) VALUES (?,?,?,?,?,?,?::jsonb)" , run, request, mode, previous, target, digest, manifest);
    }

    public long schemaCount(String dataset, String version) throws Exception {
        return count(connection, "SELECT count(*) FROM os_control.schema_contract WHERE dataset=? AND version=?" , dataset, version);
    }

    public void createFile(UUID run, String dataset, String summary, String url, String md5, String bucket) throws Exception {
        exec(connection, "INSERT INTO os_control.file(run_id,dataset,summary,download_url,expected_md5,bucket) VALUES (?,?,?::jsonb,?,?,?)" , run, dataset, summary, url, md5, bucket);
    }

    public void zipUploaded(String key, String version, String sha256, UUID run, String dataset) throws Exception {
        exec(connection, "UPDATE os_control.file SET zip_key=?,zip_version=?,zip_sha256=?,state='ZIP_SCAN' WHERE run_id=? AND dataset=?" , key, version, sha256, run, dataset);
    }

    public void receiveScan(String eventId, String bucket, String key, String version, String payload) throws Exception {
        exec(connection, "INSERT INTO os_control.scan_inbox(id,bucket,object_key,version,event) VALUES (?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING" , eventId, bucket, key, version, payload);
    }

    public Map<String, Object> lockScannedFile(String bucket, String zipKey, String csvKey) throws Exception {
        return one(connection, "SELECT * FROM os_control.file WHERE bucket=? AND (zip_key=? OR csv_key=?) FOR UPDATE" , bucket, zipKey, csvKey);
    }

    public void scanProcessed(String eventId) throws Exception {
        exec(connection, "UPDATE os_control.scan_inbox SET processed_at=now() WHERE id=?" , eventId);
    }

    public void scanRejected(boolean zip, String result, UUID run, String dataset) throws Exception {
        exec(connection, (zip ? "UPDATE os_control.file SET zip_scan=? WHERE run_id=? AND dataset=?" : "UPDATE os_control.file SET csv_scan=? WHERE run_id=? AND dataset=?") , result, run, dataset);
    }

    public void scanClean(boolean zip, String next, UUID run, String dataset) throws Exception {
        exec(connection, (zip ? "UPDATE os_control.file SET zip_scan='NO_THREATS_FOUND',state=? WHERE run_id=? AND dataset=?" : "UPDATE os_control.file SET csv_scan='NO_THREATS_FOUND',state=? WHERE run_id=? AND dataset=?") , next, run, dataset);
    }

    public void csvUploaded(String key, String version, String sha256, UUID run, String dataset) throws Exception {
        exec(connection, "UPDATE os_control.file SET csv_key=?,csv_version=?,csv_sha256=?,state='CSV_SCAN' WHERE run_id=? AND dataset=?" , key, version, sha256, run, dataset);
    }

    public void fileReady(UUID run, String dataset) throws Exception {
        exec(connection, "UPDATE os_control.file SET state='READY' WHERE run_id=? AND dataset=?" , run, dataset);
    }

    public long readyCount(UUID run) throws Exception {
        return count(connection, "SELECT count(*) FROM os_control.file WHERE run_id=? AND state='READY'" , run);
    }

    public Map<String, Object> lockWorkflow(UUID run) throws Exception {
        return one(connection, "SELECT * FROM os_control.workflow WHERE run_id=? FOR UPDATE" , run);
    }

    public void lockFiles(UUID run) throws Exception {
        exec(connection, "SELECT dataset FROM os_control.file WHERE run_id=? ORDER BY dataset FOR UPDATE" , run);
    }

    public long cleanReadyCount(UUID run) throws Exception {
        return count(connection, "SELECT count(*) FROM os_control.file WHERE run_id=? AND state='READY' AND zip_scan='NO_THREATS_FOUND' AND csv_scan='NO_THREATS_FOUND'" , run);
    }

    public void outboxSent(Object id) throws Exception {
        exec(connection, "UPDATE os_control.outbox SET sent_at=now() WHERE id=?" , id);
    }

    public List<Map<String, Object>> pendingScans() throws Exception {
        var pending = new ArrayList<Map<String, Object>>();
        try (var p = connection.prepareStatement("SELECT i.event FROM os_control.scan_inbox i WHERE i.processed_at IS NULL AND EXISTS(SELECT 1 FROM os_control.file f WHERE f.bucket=i.bucket AND (f.zip_key=i.object_key OR f.csv_key=i.object_key)) ORDER BY i.received_at LIMIT 20"); var r = p.executeQuery()) {
            while (r.next()) pending.add(new com.fasterxml.jackson.databind.ObjectMapper().readValue(r.getString(1),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        }
        return pending;
    }

    public List<Map<String, Object>> pendingEvents() throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        try (var p = connection.prepareStatement("SELECT o.* FROM os_control.outbox o JOIN os_control.workflow w USING(run_id) LEFT JOIN os_control.file f ON f.run_id=o.run_id AND f.dataset=o.dataset WHERE w.state='ACTIVE' AND (o.sent_at IS NULL OR (o.sent_at<now()-interval '20 minutes' AND (f.state=o.action OR (o.action='PROMOTE' AND (SELECT count(*) FROM os_control.file x WHERE x.run_id=o.run_id AND x.state='READY')=4)))) ORDER BY o.id LIMIT 10 FOR UPDATE OF o SKIP LOCKED"); var r = p.executeQuery()) {
            while (r.next())
                rows.add(Map.of("id", r.getLong("id"), "runId", r.getObject("run_id").toString(), "dataset", r.getString("dataset"), "action", r.getString("action")));
        }
        return rows;
    }

    public void audit(UUID run, String status, String detail) throws Exception {
        exec(connection, "UPDATE os_control.run SET status=?, detail=?, finished_at=now() WHERE id=?", status, detail, run);
    }

    public boolean tryLocalLock() throws Exception {
        return count(connection, "SELECT CASE WHEN pg_try_advisory_lock(781234991) THEN 1 ELSE 0 END") == 1;
    }

    public void unlockLocal() throws Exception {
        exec(connection, "SELECT pg_advisory_unlock(781234991)");
    }

    public LocalDate requiredWatermark() throws Exception {
        var row = one(connection, "SELECT valid_from FROM os_control.watermark WHERE singleton FOR UPDATE");
        if (row == null) throw new IllegalArgumentException("COU requires a verified baseline and watermark; seed first");
        return ((java.sql.Date) row.get("valid_from")).toLocalDate();
    }

    public void recordDelivery(UUID run, String dataset, String summary, String digest, String expected, long rows) throws Exception {
        exec(connection, "INSERT INTO os_control.cou_dataset VALUES (?,?,?::jsonb,?,?,?)", run, dataset, summary, digest, expected, rows);
    }

    public void setTarget(UUID run, LocalDate target) throws Exception {
        exec(connection, "UPDATE os_control.run SET release_date=? WHERE id=?", target, run);
    }

    public Map<String, Object> releaseAt(LocalDate target) throws Exception {
        return one(connection, "SELECT digest,mode FROM os_control.release WHERE valid_from=?", target);
    }

    public void requireLocalFullDestination(boolean replaceSample) throws Exception {
        long releases = count(connection, "SELECT count(*) FROM os_control.release");
        if (replaceSample && releases == 1 && count(connection, "SELECT count(*) FROM os_control.watermark w JOIN os_control.release r ON w.valid_from=r.valid_from WHERE r.mode='local-sample'") == 1)
            return;
        if (releases != 0 || count(connection, "SELECT count(*) FROM os_control.watermark") != 0)
            throw new IllegalArgumentException("Full import requires empty DB, or replaceLocalSample=true with only the local sample release");
        for (var dataset : Dataset.values())
            if (count(connection, "SELECT count(*) FROM " + dataset.table().data()) != 0)
                throw new IllegalArgumentException("Full import requires empty live tables");
    }
}
