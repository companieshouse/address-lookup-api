package uk.gov.companieshouse.addresslookup.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import uk.gov.companieshouse.addresslookup.mapper.LegacyAddressLayout.Part;
import uk.gov.companieshouse.addresslookup.mapper.LegacyAddressLayout.Rule;

class LegacyAddressLayoutTest {

  private static final List<Set<Part>> ALL_COMBINATIONS =
      List.of(
          EnumSet.noneOf(Part.class),
          EnumSet.of(Part.SUB_BUILDING_NAME),
          EnumSet.of(Part.BUILDING_NAME),
          EnumSet.of(Part.BUILDING_NUMBER),
          EnumSet.of(Part.SUB_BUILDING_NAME, Part.BUILDING_NAME),
          EnumSet.of(Part.SUB_BUILDING_NAME, Part.BUILDING_NUMBER),
          EnumSet.of(Part.BUILDING_NAME, Part.BUILDING_NUMBER),
          EnumSet.of(Part.SUB_BUILDING_NAME, Part.BUILDING_NAME, Part.BUILDING_NUMBER));

  @Test
  void everyComponentCombinationMatchesExactlyOneRule() {
    for (Set<Part> combination : ALL_COMBINATIONS) {
      long matches =
          LegacyAddressLayout.RULES.stream()
              .map(Rule::presentParts)
              .filter(combination::equals)
              .count();

      assertEquals(1, matches, "rules matching " + combination);
    }
  }

  @Test
  void ruleCountEqualsCombinationCount() {
    Set<Set<Part>> distinct =
        LegacyAddressLayout.RULES.stream().map(Rule::presentParts).collect(Collectors.toSet());

    assertEquals(ALL_COMBINATIONS.size(), LegacyAddressLayout.RULES.size());
    assertEquals(ALL_COMBINATIONS.size(), distinct.size());
  }
}
