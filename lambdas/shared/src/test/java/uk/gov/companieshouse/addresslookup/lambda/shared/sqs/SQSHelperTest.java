package uk.gov.companieshouse.addresslookup.lambda.shared.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import org.junit.jupiter.api.Test;
import uk.gov.companieshouse.addresslookup.lambda.test.SQSMessageBuilder;

import java.util.Map;

public class SQSHelperTest {

    @Test
    void CheckSQSMessageBodyConvertsToMap() throws Exception {
        String body = """
            {
              "version": "0",
              "id": "2bf949e0-06d4-7223-b89d-54392b711694",
              "detail-type": "DOWNLOAD",
              "source": "os.acquisition",
              "account": "450865997890",
              "time": "2026-11-06T16:44:55Z",
              "region": "eu-west-2",
              "resources": [],
              "detail": {
                "dataset":"add_gb_royalmailaddress",
                "planKey": "acquisitions/2026-10-06/12345678-1234-1234-1234-123456787abc.json"
              }
            }
            """;
        SQSEvent.SQSMessage sqsMessage = SQSMessageBuilder.builder()
                .body(body)
                .build();

        Map<String, Object> map = SQSHelper.extractMapFromSQSMessageBody(sqsMessage);

        assertNotNull(map);
        assertEquals("2bf949e0-06d4-7223-b89d-54392b711694", map.get("id"));
        assertEquals("DOWNLOAD", map.get("detail-type"));
        assertEquals("os.acquisition", map.get("source"));
        assertInstanceOf(Map.class, map.get("detail"));
        //noinspection unchecked
        Map<String, Object> detailMap = (Map<String, Object>)map.get("detail");
        assertEquals("add_gb_royalmailaddress", detailMap.get("dataset"));
        assertEquals("acquisitions/2026-10-06/12345678-1234-1234-1234-123456787abc.json",
                detailMap.get("planKey"));

    }
}
