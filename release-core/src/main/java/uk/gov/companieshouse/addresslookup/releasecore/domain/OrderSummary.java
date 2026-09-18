package uk.gov.companieshouse.addresslookup.releasecore.domain;

import java.time.LocalDate;

public record OrderSummary(String featureName, String crs, String schemaVersion,
                           String validFromDate, String validToDate, long recordCount) {
    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    public static int srid(DatasetCatalog.Table t) {
        return t.name().contains("_isl_") ? 4258 : 27700;
    }

    public void validate(DatasetCatalog.Table t, LocalDate previous, LocalDate target) {
        require(t.name().replace('_', '-').equals(featureName), "Wrong featureName: " + featureName);
        require(t.version().equals(schemaVersion), "Unsupported schemaVersion");
        require(LocalDate.parse(validFromDate).equals(previous), "COU continuity gap");
        require(LocalDate.parse(validToDate).equals(target) && target.isAfter(previous), "Invalid COU target");
        require(recordCount >= 0, "Negative recordCount");
        require(("http://www.opengis.net/def/crs/EPSG/0/" + srid(t)).equals(crs), "Unsupported CRS; supply a verified adapter");
    }
}
