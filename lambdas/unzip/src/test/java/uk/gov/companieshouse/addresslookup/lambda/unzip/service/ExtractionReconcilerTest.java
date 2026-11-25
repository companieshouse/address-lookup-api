package uk.gov.companieshouse.addresslookup.lambda.unzip.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;

import com.amazonaws.services.lambda.runtime.Context;

import org.junit.jupiter.api.Test;

import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;
import uk.gov.companieshouse.addresslookup.lambda.shared.testsupport.FakeS3;
import uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout;
import uk.gov.companieshouse.release.model.Dataset;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

class ExtractionReconcilerTest {
    private static final String BUCKET = "acquisition";
    private static final Dataset DATASET = Dataset.ADD_GB_ROYALMAILADDRESS;
    private static final KeyLayout.PackageVersion PACKAGE =
            new KeyLayout.PackageVersion(KeyLayout.Feed.DAILY_COU, "20780", "150104");
    private static final String MANIFEST = KeyLayout.dataPackageManifestKey(PACKAGE);
    private static final String ZIP = KeyLayout.downloadZipKey(PACKAGE, DATASET);
    private static final String MD5 = "0123456789abcdef0123456789abcdef";

    @Test
    void waitsForManifestScanBeforeDispatchingDownload() throws Exception {
        var fixture = new Fixture();
        fixture.reconciler.reconcile(fixture.context);
        verifyNoInteractions(fixture.commands, fixture.extraction);

        fixture.s3.scanStatus.put(MANIFEST, "NO_THREATS_FOUND");
        fixture.reconciler.reconcile(fixture.context);
        verify(fixture.commands).send("DOWNLOAD", MANIFEST, DATASET.datasetName());
        verifyNoInteractions(fixture.extraction);
    }

    @Test
    void onlyExtractsTheCleanZipVersionMatchingChecksumEvidence() throws Exception {
        var fixture = new Fixture();
        fixture.s3.scanStatus.put(MANIFEST, "NO_THREATS_FOUND");
        fixture.s3.objects.put(ZIP, new byte[] {1});
        fixture.s3.objects.put(
                KeyLayout.checksumKey(ZIP),
                JSON.writeValueAsBytes(Map.of("versionId", FakeS3.DEFAULT_VERSION, "md5", MD5)));

        fixture.reconciler.reconcile(fixture.context);
        verifyNoInteractions(fixture.commands, fixture.extraction);

        fixture.s3.scanStatus.put(ZIP, "NO_THREATS_FOUND");
        fixture.reconciler.reconcile(fixture.context);
        verify(fixture.extraction)
                .unzip(
                        Map.of(
                                "detail",
                                Map.of(
                                        "schemaVersion", 1,
                                        "dataset", DATASET.datasetName(),
                                        "object",
                                                Map.of(
                                                        "bucket", BUCKET,
                                                        "key", ZIP,
                                                        "versionId", FakeS3.DEFAULT_VERSION))),
                        fixture.context);
        verifyNoInteractions(fixture.commands);
    }

    @Test
    void rejectsThreatsWithoutDispatchingOrExtracting() {
        var fixture = new Fixture();
        fixture.s3.scanStatus.put(MANIFEST, "THREATS_FOUND");
        assertThrows(IllegalStateException.class, () -> fixture.reconciler.reconcile(fixture.context));
        verifyNoInteractions(fixture.commands, fixture.extraction);
    }

    private static final class Fixture {
        final FakeS3 s3 = new FakeS3();
        final Commands commands = mock(Commands.class);
        final ExtractionService extraction = mock(ExtractionService.class);
        final Context context = mock(Context.class);
        final ExtractionReconciler reconciler;

        Fixture() {
            s3.objects.put(
                    MANIFEST,
                    ("{\"dataPackageId\":\"20780\",\"versionId\":\"150104\","
                                    + "\"add_gb_royalmailaddress\":{\"zip\":{\"md5\":\""
                                    + MD5
                                    + "\"}}}")
                            .getBytes(StandardCharsets.UTF_8));
            when(context.getRemainingTimeInMillis()).thenReturn(900_000);
            var properties =
                    new AcquisitionProperties(
                            BUCKET,
                            BUCKET,
                            "events",
                            "key",
                            "",
                            1024,
                            null,
                            null,
                            1024,
                            20,
                            Duration.ofSeconds(20),
                            Duration.ofSeconds(60));
            reconciler = new ExtractionReconciler(s3.client(), properties, commands, extraction);
        }
    }
}
