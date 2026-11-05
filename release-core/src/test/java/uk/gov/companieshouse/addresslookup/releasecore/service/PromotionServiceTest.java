package uk.gov.companieshouse.addresslookup.releasecore.service;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import uk.gov.companieshouse.addresslookup.releasecore.aurora.AuroraOperations;
import uk.gov.companieshouse.addresslookup.releasecore.state.Release;
import uk.gov.companieshouse.addresslookup.releasecore.state.ReleaseFile;
import uk.gov.companieshouse.addresslookup.releasecore.state.ReleaseFileRepository;
import uk.gov.companieshouse.addresslookup.releasecore.state.ReleaseRepository;
import uk.gov.companieshouse.addresslookup.releasecore.state.Watermark;
import uk.gov.companieshouse.addresslookup.releasecore.state.WatermarkRepository;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.SupplyType;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class PromotionServiceTest {
  private static final UUID RELEASE_ID = UUID.randomUUID();
  private static final LocalDate VALID_FROM = LocalDate.of(2026, 1, 1);
  private static final LocalDate VALID_TO = LocalDate.of(2026, 2, 1);

  @Test
  void createsFullAddressArtefactsAroundFullPromotionInOrder() {
    var aurora = mock(AuroraOperations.class);
    var service = service(SupplyType.FULL, aurora);

    assertTrue(service.promote(RELEASE_ID));

    InOrder order = inOrder(aurora);
    order.verify(aurora).createAddressTables();
    order.verify(aurora).promote(SupplyType.FULL, RELEASE_ID);
    order.verify(aurora).createAddressIndexes();
    order.verify(aurora).createAddressLookupView();
    order.verifyNoMoreInteractions();
  }

  @Test
  void doesNotRebuildAddressArtefactsForCouPromotion() {
    var aurora = mock(AuroraOperations.class);
    var service = service(SupplyType.COU, aurora);

    assertTrue(service.promote(RELEASE_ID));

    verify(aurora).promote(SupplyType.COU, RELEASE_ID);
    verify(aurora, never()).createAddressTables();
    verify(aurora, never()).createAddressIndexes();
    verify(aurora, never()).createAddressLookupView();
  }

  private static PromotionService service(SupplyType type, AuroraOperations aurora) {
    var releases = mock(ReleaseRepository.class);
    var files = mock(ReleaseFileRepository.class);
    var watermarks = mock(WatermarkRepository.class);
    var watermark = mock(Watermark.class);
    var release =
        new Release(RELEASE_ID, type, VALID_FROM, VALID_TO, "key", "digest", "{}");
    when(watermarks.lock()).thenReturn(watermark);
    when(watermark.getDate()).thenReturn(VALID_FROM);
    when(releases.lock(RELEASE_ID)).thenReturn(Optional.of(release));
    when(files.findByReleaseId(RELEASE_ID))
        .thenReturn(
            Arrays.stream(Dataset.values())
                .map(
                    dataset -> {
                      var file = new ReleaseFile(RELEASE_ID, dataset);
                      file.ready(type, "bucket", "key", "version", "checksum");
                      return file;
                    })
                .toList());
    return new PromotionService(releases, files, watermarks, aurora);
  }
}
