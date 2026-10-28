package uk.gov.companieshouse.release.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;
import uk.gov.companieshouse.release.model.utils.TestUtils;

import java.time.LocalDate;

public class PackageVersionDataTest {

    @Test
    void testPackageVersionData() throws Exception {
        PackageVersionData packageVersionData = TestUtils.jsonFileToObject(
                "package/data-package-20780-version-150035-data.json",
                PackageVersionData.class, ReleaseJson.MAPPER);
        assertNotNull(packageVersionData);
        assertEquals(LocalDate.parse("2026-10-05"), packageVersionData.createdOn());
        assertEquals("https://api.os.uk/downloads/v1/dataPackages/20780", packageVersionData.dataPackageUrl());
        assertEquals(12, packageVersionData.downloads().size());
        assertEquals("CSV", packageVersionData.format());
        assertEquals("150035", packageVersionData.id());
        assertEquals("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104", packageVersionData.nextVersionUrl());
        assertEquals("https://api.os.uk/downloads/v1/dataPackages/20780/versions/149954", packageVersionData.previousVersionUrl());
        assertEquals("2026-10-05", packageVersionData.productVersion());
        assertEquals("UPDATE", packageVersionData.reason());
        assertEquals(SupplyType.COU, packageVersionData.supplyType());
        assertEquals("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150035", packageVersionData.url());

    }
}
