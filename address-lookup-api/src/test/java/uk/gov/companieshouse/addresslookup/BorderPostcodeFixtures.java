package uk.gov.companieshouse.addresslookup;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/**
 * Provides regional postcode cases with compact query inputs and canonical response values.
 */
final class BorderPostcodeFixtures {

  private static final List<Case> CASES =
      List.of(
          new Case("DG89JA", "DG8 9JA", "GB-SCT"),
          new Case("CH11DF", "CH1 1DF", "GB-ENG"),
          new Case("CF143AT", "CF14 3AT", "GB-WLS"));

  private BorderPostcodeFixtures() {}

  static Stream<Arguments> cases() {
    return CASES.stream()
        .map(postcode ->
            Arguments.of(postcode.query(), postcode.canonical(), postcode.legacyCountry()));
  }

  static Set<String> compactPostcodes() {
    return CASES.stream()
        .map(postcode -> postcode.query().replace(" ", ""))
        .collect(Collectors.toUnmodifiableSet());
  }

  private record Case(String query, String canonical, String legacyCountry) {}
}
