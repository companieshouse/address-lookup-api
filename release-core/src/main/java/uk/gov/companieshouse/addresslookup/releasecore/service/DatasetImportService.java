package uk.gov.companieshouse.addresslookup.releasecore.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.companieshouse.addresslookup.releasecore.aurora.AuroraOperations;
import uk.gov.companieshouse.addresslookup.releasecore.state.Release;
import uk.gov.companieshouse.addresslookup.releasecore.state.ReleaseFileRepository;
import uk.gov.companieshouse.addresslookup.releasecore.state.ReleaseRepository;
import uk.gov.companieshouse.addresslookup.releasecore.state.SchemaContractRepository;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.ReleaseJson;
import uk.gov.companieshouse.release.model.ReleaseManifest;

import java.util.UUID;

@Service
public class DatasetImportService {
  private final ReleaseRepository releases;
  private final ReleaseFileRepository files;
  private final AuroraOperations aurora;
  private final SchemaContractRepository contracts;

  public DatasetImportService(
      ReleaseRepository releases,
      ReleaseFileRepository files,
      AuroraOperations aurora,
      SchemaContractRepository contracts) {
    this.releases = releases;
    this.files = files;
    this.aurora = aurora;
    this.contracts = contracts;
  }

  /** Called only after checking exact immutable object version and GuardDuty evidence. */
  @Transactional(rollbackFor = Exception.class)
  public boolean importClean(
      UUID id,
      Dataset dataset,
      String bucket,
      String key,
      String version,
      String checksum,
      String region)
      throws Exception {
    var file = files.lock(id, dataset.datasetName()).orElseThrow();
    var release = releases.findById(id).orElseThrow();
    if (file.ready(release.getSupplyType())) return false;
    if (release.getStatus() != Release.Status.PROCESSING)
      throw new IllegalStateException("Release is not processing");
    var manifest = ReleaseManifest.read(ReleaseJson.MAPPER.readTree(release.getManifest()));
    var summary = manifest.summary(dataset);
    if (!contracts
        .findById(dataset.datasetName())
        .orElseThrow()
        .getVersion()
        .equals(summary.schemaVersion())) {
      throw new IllegalArgumentException("Deployed schema changed; manual intervention required");
    }
    aurora.importS3(dataset, manifest.type(), id, bucket, key, region, summary.recordCount());
    aurora.prepare(dataset, manifest.type(), id, manifest.target());
    file.ready(manifest.type(), bucket, key, version, checksum);
    return true;
  }
}
