package uk.gov.companieshouse.release.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.Test;
import uk.gov.companieshouse.release.model.utils.TestUtils;

import java.time.LocalDate;

public class OrderSummaryTest {

    @Test
    void testOrderSummary() throws Exception{
        OrderSummary orderSummaryOriginal = TestUtils.jsonFileToObject("summary/add_gb_builtaddress_orderSummary.json",
                OrderSummary.class, ReleaseJson.MAPPER);

        OrderSummary orderSummary = new OrderSummary(
                orderSummaryOriginal.featureName(),
                orderSummaryOriginal.crs(),
                orderSummaryOriginal.schemaVersion(),
                orderSummaryOriginal.validFromDate(),
                orderSummaryOriginal.validToDate(),
                orderSummaryOriginal.recordCount()
        );
        
        assertNotNull(orderSummary);
        assertEquals("http://www.opengis.net/def/crs/EPSG/0/27700", orderSummary.crs());
        assertEquals("add-gb-builtaddress", orderSummary.featureName());
        assertEquals(123, orderSummary.recordCount());
        assertEquals("3.0", orderSummary.schemaVersion());
        assertEquals(LocalDate.parse("2026-10-06"), orderSummary.validFromDate());
        assertEquals(LocalDate.parse("2026-10-07"), orderSummary.validToDate());
    }

    @Test
    void testValidate() throws Exception {
        OrderSummary orderSummary = TestUtils.jsonFileToObject("summary/add_gb_builtaddress_orderSummary.json",
                OrderSummary.class, ReleaseJson.MAPPER);

        DatasetCatalog.Table testTable = new DatasetCatalog.Table("add-gb-builtaddress", "3.0",
                null, null, null);
        try {
        orderSummary.validate(testTable, SupplyType.COU,
                LocalDate.parse("2026-10-06"),
                LocalDate.parse("2026-10-07"));
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            fail(throwable.getMessage());
        }
    }
}
