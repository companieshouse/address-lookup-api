package uk.gov.companieshouse.addresslookup.releasecore.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.companieshouse.addresslookup.releasecore.aurora.AuroraOperations;
import uk.gov.companieshouse.addresslookup.releasecore.state.Release;
import uk.gov.companieshouse.addresslookup.releasecore.state.ReleaseFileRepository;
import uk.gov.companieshouse.addresslookup.releasecore.state.ReleaseRepository;
import uk.gov.companieshouse.addresslookup.releasecore.state.WatermarkRepository;
import uk.gov.companieshouse.release.model.Dataset;

import java.util.Objects;
import java.util.UUID;

@Service
public class PromotionService {
  private final ReleaseRepository releases;
  private final ReleaseFileRepository files;
  private final WatermarkRepository watermarks;
  private final AuroraOperations aurora;

  public PromotionService(
      ReleaseRepository releases,
      ReleaseFileRepository files,
      WatermarkRepository watermarks,
      AuroraOperations aurora) {
    this.releases = releases;
    this.files = files;
    this.watermarks = watermarks;
    this.aurora = aurora;
  }

  @Transactional(rollbackFor = Exception.class)
  public boolean promote(UUID id) {
    var watermark = watermarks.lock();
    var release = releases.lock(id).orElseThrow();
    if (release.getStatus() != Release.Status.PROCESSING) return false;
    if (!Objects.equals(watermark.getDate(), release.getValidFrom())) return false;
    var ready = files.findByReleaseId(id);
    if (ready.size() != Dataset.values().length
        || ready.stream().anyMatch(f -> !f.ready(release.getSupplyType()))) return false;
    // Procedure rechecks the barrier, validates prospective data and atomically changes live
    // tables.
    aurora.promote(release.getSupplyType(), id);
    release.complete();
    watermark.advance(release.getValidTo());
    return true;
  }
}
