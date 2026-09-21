package uk.gov.companieshouse.addresslookup.releasecore.state;

import jakarta.persistence.*;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.SupplyType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "release_file",
    schema = "os_control",
    uniqueConstraints = @UniqueConstraint(columnNames = {"release_id", "dataset"}))
public class ReleaseFile {
  @Id
  private UUID id;

  @Column(name = "release_id", nullable = false)
  private UUID releaseId;

  @Column(nullable = false)
  private String dataset;

  @Enumerated(EnumType.STRING)
  private Status status;

  private String bucket;

  @Column(name = "csv_key")
  private String csvKey;

  @Column(name = "csv_version")
  private String csvVersion;

  private String checksum;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Version private long version;

  public enum Status {
    WAITING,
    RESOLVED,
    FULL_READY
  }

  protected ReleaseFile() {}

  public ReleaseFile(UUID releaseId, Dataset dataset) {
    id =
        UUID.nameUUIDFromBytes(
            (releaseId + ":" + dataset.datasetName())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    this.releaseId = releaseId;
    this.dataset = dataset.datasetName();
    status = Status.WAITING;
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getReleaseId() {
    return releaseId;
  }

  public String getDataset() {
    return dataset;
  }

  public Status getStatus() {
    return status;
  }

  public boolean ready(SupplyType type) {
    return status == (type == SupplyType.COU ? Status.RESOLVED : Status.FULL_READY);
  }

  public void ready(
      SupplyType type, String bucket, String key, String objectVersion, String checksum) {
    if (status != Status.WAITING) throw new IllegalStateException("File already processed");
    this.bucket = bucket;
    csvKey = key;
    csvVersion = objectVersion;
    this.checksum = checksum;
    status = type == SupplyType.COU ? Status.RESOLVED : Status.FULL_READY;
    updatedAt = Instant.now();
  }
}
