package uk.gov.companieshouse.release.model;

import java.util.Arrays;

/** The supported datasets, deliberately explicit. */
public enum Dataset {
  ADD_GB_ROYALMAILADDRESS(
      "add_gb_royalmailaddress",
      "os_stage_raw.add_gb_royalmailaddress",
      "os_stage_full.add_gb_royalmailaddress",
      "CALL os_ingest.resolve_add_gb_royalmailaddress(?,?)",
      "CALL os_ingest.stage_full_add_gb_royalmailaddress(?,?)"),
  ADD_ISL_ROYALMAILADDRESS(
      "add_isl_royalmailaddress",
      "os_stage_raw.add_isl_royalmailaddress",
      "os_stage_full.add_isl_royalmailaddress",
      "CALL os_ingest.resolve_add_isl_royalmailaddress(?,?)",
      "CALL os_ingest.stage_full_add_isl_royalmailaddress(?,?)");
  private final String name, raw, full, resolve, validateFull;

  Dataset(String name, String raw, String full, String resolve, String validateFull) {
    this.name = name;
    this.raw = raw;
    this.full = full;
    this.resolve = resolve;
    this.validateFull = validateFull;
  }

  public String datasetName() {
    return name;
  }

  public String landing(SupplyType mode) {
    return mode == SupplyType.COU ? raw : full;
  }

  public String prepareCall(SupplyType mode) {
    return mode == SupplyType.COU ? resolve : validateFull;
  }

  public static Dataset named(String name) {
    return Arrays.stream(values())
        .filter(d -> d.name.equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown dataset: " + name));
  }
}
