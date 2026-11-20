package db.local.java;

/**
 * Seeds os_data.add_isl_royalmailaddress_v2 (ETRS89 geographic, SRID 4258) from longitude and
 * latitude. The class name is the Flyway repeatable-migration name required by BaseJavaMigration.
 */
@SuppressWarnings("java:S101")
public class R__seed_add_isl_royalmailaddress extends LocalRoyalMailCsvSeed {
  public R__seed_add_isl_royalmailaddress() {
    super("add_isl_royalmailaddress", "longitude", "latitude");
  }
}
