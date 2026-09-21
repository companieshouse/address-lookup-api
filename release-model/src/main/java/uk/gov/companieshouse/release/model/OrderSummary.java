package uk.gov.companieshouse.release.model;

import java.time.LocalDate;

public record OrderSummary(
    String featureName,
    String crs,
    String schemaVersion,
    String validFromDate,
    String validToDate,
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
      require(LocalDate.parse(validFromDate).equals(previous), "COU continuity gap");
    if (validFromDate != null)
      require(!LocalDate.parse(validFromDate).isAfter(target), "Invalid validFromDate");
    require(
        LocalDate.parse(validToDate).equals(target)
            && (previous == null || target.isAfter(previous)),
        "Invalid COU target");
    require(recordCount >= 0, "Negative recordCount");
    require(
        ("http://www.opengis.net/def/crs/EPSG/0/" + srid(t)).equals(crs),
        "Unsupported CRS; supply a verified adapter");
  }
}
