package uk.gov.companieshouse.addresslookup.releasecore.state;

import jakarta.persistence.*;
import uk.gov.companieshouse.release.model.SupplyType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "release", schema = "os_control")
public class Release {
  @Id
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "supply_type")
  private SupplyType supplyType;

  @Column(name = "valid_from")
  private LocalDate validFrom;

  @Column(name = "valid_to")
  private LocalDate validTo;

  @Column(name = "manifest_key")
  private String manifestKey;

  private String digest;

  @Column(columnDefinition = "text")
  private String manifest;

  @Enumerated(EnumType.STRING)
  private Status status;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Version private long version;

  public enum Status {
    PROCESSING,
    COMPLETED,
    BLOCKED
  }

  protected Release() {}

  public Release(
      UUID id,
      SupplyType type,
      LocalDate from,
      LocalDate to,
      String key,
      String digest,
      String manifest) {
    this.id = id;
    supplyType = type;
    validFrom = from;
    validTo = to;
    manifestKey = key;
    this.digest = digest;
    this.manifest = manifest;
    status = Status.PROCESSING;
    createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public SupplyType getSupplyType() {
    return supplyType;
  }

  public LocalDate getValidFrom() {
    return validFrom;
  }

  public LocalDate getValidTo() {
    return validTo;
  }

  public String getManifestKey() {
    return manifestKey;
  }

  public String getDigest() {
    return digest;
  }

  public String getManifest() {
    return manifest;
  }

  public Status getStatus() {
    return status;
  }

  public void complete() {
    status = Status.COMPLETED;
    completedAt = Instant.now();
  }
}
