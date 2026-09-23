package uk.gov.companieshouse.addresslookup.releasecore.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.companieshouse.addresslookup.releasecore.state.*;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.ReleaseManifest;

@Service
public class ReleaseRegistration {
  private final ReleaseRepository releases;
  private final ReleaseFileRepository files;
  private final SchemaContractRepository contracts;
  private final WatermarkRepository watermark;

  public ReleaseRegistration(
      ReleaseRepository releases,
      ReleaseFileRepository files,
      SchemaContractRepository contracts,
      WatermarkRepository watermark) {
    this.releases = releases;
    this.files = files;
    this.contracts = contracts;
    this.watermark = watermark;
  }

  @Transactional(rollbackFor = Exception.class)
  public void register(ReleaseManifest manifest, String key, String digest) throws Exception {
    // A short singleton lock also serializes first registration; never held during an import.
    watermark.lock();
    var existing = releases.findById(manifest.id());
    if (existing.isPresent()) {
      if (!digest.equals(existing.get().getDigest()))
        throw new IllegalArgumentException(
            "Immutable release changed; manual intervention required");
      return;
    }
    for (var dataset : Dataset.values()) {
      var summary = manifest.summary(dataset);
      if (!contracts
          .findById(dataset.datasetName())
          .orElseThrow()
          .getVersion()
          .equals(summary.schemaVersion()))
        throw new IllegalArgumentException(
            "Deployed schema mismatch; manual intervention required");
    }
    releases.saveAndFlush(
        new Release(
            manifest.id(),
            manifest.type(),
            manifest.previous(),
            manifest.target(),
            key,
            digest,
            manifest.document()));
    for (var dataset : Dataset.values()) files.save(new ReleaseFile(manifest.id(), dataset));
  }
}
