package uk.gov.companieshouse.release.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

public record OrderSummary(
    String featureName,
    String crs,
    String schemaVersion,
    @JsonFormat(pattern = "yyyy-MM-dd")
    LocalDate validFromDate,
    @JsonFormat(pattern = "yyyy-MM-dd")
    LocalDate validToDate,
    long recordCount) {
  private static void require(boolean ok, String message) {
    if (!ok) throw new IllegalArgumentException(message);
  }

  public static int srid(DatasetCatalog.Table t) {
    return t.name().contains("_isl_") ? 4258 : 27700;
  }

  public void validate(DatasetCatalog.Table t, LocalDate previous, LocalDate target) {
    validate(t, SupplyType.COU, previous, target);
  }

  public void validate(
      DatasetCatalog.Table t, SupplyType type, LocalDate previous, LocalDate target) {
    require(t.name().replace('_', '-').equals(featureName), "Wrong featureName: " + featureName);
    require(t.version().equals(schemaVersion), "Unsupported schemaVersion");
    if (type == SupplyType.COU)
      require(validFromDate.equals(previous), "COU continuity gap");
    if (validFromDate != null)
      require(!validFromDate.isAfter(target), "Invalid validFromDate");
    require(
        validToDate.equals(target)
            && (previous == null || target.isAfter(previous)),
        "Invalid COU target");
    require(recordCount >= 0, "Negative recordCount");
    require(
        ("http://www.opengis.net/def/crs/EPSG/0/" + srid(t)).equals(crs),
        "Unsupported CRS; supply a verified adapter");
  }
}
