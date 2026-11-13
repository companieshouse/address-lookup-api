package uk.gov.companieshouse.addresslookup.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PostcodeValidatorTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SW1A 1AA", "sw1a1aa", " M1 1AE ", "B33 8TH", "CR2 6XH", "DN55 1PT", "W1A 0AX", "GIR 0AA",
        "BT557KL", "EC1A1BB", "ZZ1 1ZZ"
      })
  void shouldAcceptWellFormedPostcodes(String postcode) {
    assertTrue(PostcodeValidator.isValid(postcode));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "   ", "12345", "A", "INVALID", "INVALID@POSTCODE", "SW1A 1AA; DROP TABLE x",
        "BT557KLEXTRA", "SW1A\n1AA", "SW1A-1AA", "1A 1AA", "SWA1 1AA", "SW1A 1A1", "SW1A 1AAA",
        "AAA1 1AA", "S W 1A 1AA", "ＳＷ１Ａ １ＡＡ", "SW1A                1AA"
      })
  void shouldRejectMalformedPostcodes(String postcode) {
    assertFalse(PostcodeValidator.isValid(postcode));
  }
}
