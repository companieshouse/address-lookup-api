package uk.gov.companieshouse.addresslookup.lambda.download;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import uk.gov.companieshouse.addresslookup.lambda.download.service.DownloadService;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;
import uk.gov.companieshouse.addresslookup.lambda.shared.sqs.SQSHelper;

import java.util.List;

public final class SQSHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {
    private final DownloadService service =
            AcquisitionApplication.start(DownloadService.class);
    private Context context;

    private static final List<String> NON_FAILURE_RESPONSES = List.of("ZIP_SCAN");

    @Override
    public SQSBatchResponse handleRequest(SQSEvent sqsEvent, Context context) {
        this.context = context;
        List<SQSBatchResponse.BatchItemFailure> output = sqsEvent.getRecords().stream()
                .map(this::handleMessage).filter(this::filterBatchItemFailure).toList();
        return new SQSBatchResponse(output);
    }

    private SQSBatchResponse.BatchItemFailure handleMessage(SQSEvent.SQSMessage sqsMessage) {
        try {

            String response = service.download(SQSHelper.extractMapFromSQSMessageBody(sqsMessage),
                    context);
            if (!NON_FAILURE_RESPONSES.contains(response)) {
                return new SQSBatchResponse.BatchItemFailure(sqsMessage.getMessageId());
            }
        } catch (Exception e) {
            throw new RuntimeException("download failed; durable state allows retry", e);
        }
        return null;
    }

    private boolean filterBatchItemFailure(SQSBatchResponse.BatchItemFailure batchItemFailure) {
        return batchItemFailure != null;
    }
}
