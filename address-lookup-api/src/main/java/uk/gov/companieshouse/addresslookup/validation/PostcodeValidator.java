package uk.gov.companieshouse.addresslookup.validation;

import java.util.regex.Pattern;

/** Validates the format of UK postcodes (not whether the postcode exists). */
public final class PostcodeValidator {

  // Outward code (A9, A99, A9A, AA9, AA99, AA9A), optional whitespace, inward code (9AA),
  // or the special-case GIR 0AA.
  private static final Pattern UK_POSTCODE =
      Pattern.compile(
          "^ {0,5}(?:[A-Z]{1,2}\\d[A-Z\\d]? {0,5}\\d[A-Z]{2}|GIR {0,5}0AA) {0,5}$",
          Pattern.CASE_INSENSITIVE);

  private PostcodeValidator() {}

  /**
   * Checks that the value has a valid UK postcode format.
   *
   * @param postcode value to check, may be null
   * @return true when the value is a well-formed UK postcode
   */
  public static boolean isValid(String postcode) {
    return postcode != null && UK_POSTCODE.matcher(postcode).matches();
  }
}
