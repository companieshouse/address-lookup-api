package db.local.java;

/** Seeds os_data.add_gb_royalmailaddress_v2 (British National Grid, SRID 27700). */
public class LocalGbRoyalMailCsvSeed extends LocalRoyalMailCsvSeed {
  public LocalGbRoyalMailCsvSeed() {
    super("add_gb_royalmailaddress", new LocalIslRoyalMailCsvSeed());
  }
}
