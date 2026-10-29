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

    private static final List<Case> CASES = List.of(
            new Case("DG89JA", "DG8 9JA", "GB-SCT"),
            new Case("DG27AZ", "DG2 7AZ", "GB-SCT"),
            new Case("NE119NU", "NE11 9NU", "GB-ENG"),
            new Case("NE311JA", "NE31 1JA", "GB-ENG"),
            new Case("CA11AA", "CA1 1AA", "GB-ENG"),
            new Case("CA101AT", "CA10 1AT", "GB-ENG"),
            new Case("TD12SJ", "TD1 2SJ", "GB-SCT"),
            new Case("TD11BE", "TD1 1BE", "GB-SCT"),
            new Case("CH11DE", "CH1 1DE", "GB-ENG"),
            new Case("CH11DF", "CH1 1DF", "GB-ENG"),
            new Case("SY11EB", "SY1 1EB", "GB-ENG"),
            new Case("SY100AU", "SY10 0AU", "GB-ENG"),
            new Case("HR11AD", "HR1 1AD", "GB-ENG"),
            new Case("HR11AE", "HR1 1AE", "GB-ENG"),
            new Case("SY217BL", "SY21 7BL", "GB-WLS"),
            new Case("LL137TD", "LL13 7TD", "GB-WLS"),
            new Case("NP253EQ", "NP25 3EQ", "GB-WLS"));

    private BorderPostcodeFixtures() {
    }

    static Stream<Arguments> cases() {
        return CASES.stream()
                .map(postcode -> Arguments.of(
                        postcode.query(),
                        postcode.canonical(),
                        postcode.legacyCountry()));
    }

    static Set<String> compactPostcodes() {
        return CASES.stream()
                .map(postcode -> postcode.query().replace(" ", ""))
                .collect(Collectors.toUnmodifiableSet());
    }

    private record Case(String query, String canonical, String legacyCountry) {
    }
}
