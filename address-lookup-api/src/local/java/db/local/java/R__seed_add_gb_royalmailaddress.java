package db.local.java;

/**
 * Seeds os_data.add_gb_royalmailaddress_v2 (British National Grid, SRID 27700) from easting and
 * northing. The class name is the Flyway repeatable-migration name required by BaseJavaMigration.
 */
@SuppressWarnings("java:S101")
public class R__seed_add_gb_royalmailaddress extends LocalRoyalMailCsvSeed {
  public R__seed_add_gb_royalmailaddress() {
    super("add_gb_royalmailaddress", "easting", "northing");
  }
}
