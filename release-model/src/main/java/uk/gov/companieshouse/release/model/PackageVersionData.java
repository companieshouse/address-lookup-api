package uk.gov.companieshouse.release.model;

import java.time.LocalDate;
import java.util.List;

public record PackageVersionData(String id, String url, LocalDate createdOn, String reason,
                                 SupplyType supplyType, String productVersion, String format,
                                 String dataPackageUrl, String previousVersionUrl,
                                 String nextVersionUrl, List<Download> downloads) { }
