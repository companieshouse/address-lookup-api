package uk.gov.companieshouse.addresslookup.mapper;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import uk.gov.companieshouse.addresslookup.entity.RoyalMailAddressLookup;

/**
 * Decides which Royal Mail components make up the legacy premise and address line 1.
 *
 * <p>The legacy postcode lookup service builds these two fields differently depending on which of
 * three components an address has: sub-building name, building name and building number. The street
 * is always part of address line 1. This class holds that behaviour as a table of {@link Rule}s in
 * {@link #RULES}, one rule per combination of the three components (2 x 2 x 2 = 8 rules).
 *
 * <p>Example: an address with a sub-building name "FLAT 1", building name "SOVEREIGN HOUSE", number
 * 16 and street "HIGH STREET" matches the first rule, giving premise "FLAT 1 SOVEREIGN HOUSE" and
 * address line 1 "16 HIGH STREET".
 *
 * <p>Each rule names the exact set of components that must be present, and every other component
 * must be absent. Rules therefore do not depend on their order. To fix a newly reported mismatch,
 * change the {@code premise} or {@code addressLine1} of the matching rule; no other code needs to
 * change.
 */
final class LegacyAddressLayout {

  /** Address components that can be placed in the premise or address line 1. */
  enum Part {
    SUB_BUILDING_NAME(RoyalMailAddressLookup::getSubBuildingName),
    BUILDING_NAME(RoyalMailAddressLookup::getBuildingName),
    BUILDING_NUMBER(a -> a.getBuildingNumber() == null ? null : a.getBuildingNumber().toString()),
    STREET(a -> join(a.getDependentThoroughfare(), a.getThoroughfare()));

    private final Function<RoyalMailAddressLookup, String> extractor;

    Part(Function<RoyalMailAddressLookup, String> extractor) {
      this.extractor = extractor;
    }

    String extract(RoyalMailAddressLookup address) {
      return extractor.apply(address);
    }
  }

  /**
   * The components whose presence selects a rule. The street is excluded because it is always shown
   * in address line 1.
   */
  private static final Set<Part> DECIDING_PARTS =
      EnumSet.of(Part.SUB_BUILDING_NAME, Part.BUILDING_NAME, Part.BUILDING_NUMBER);

  /**
   * A single layout rule.
   *
   * @param description human-readable summary of the address shape, with example postcodes
   * @param presentParts the exact set of {@link #DECIDING_PARTS} an address must have for this rule
   *     to apply; deciding parts not listed must be absent or blank
   * @param premise parts joined with spaces to form the legacy premise
   * @param addressLine1 parts joined with spaces to form the legacy address line 1
   */
  record Rule(
      String description, Set<Part> presentParts, List<Part> premise, List<Part> addressLine1) {}

  /** Address line 1 made of the street alone. */
  private static final List<Part> STREET_ONLY = List.of(Part.STREET);
  /** Address line 1 made of the building number followed by the street, e.g. "16 HIGH STREET". */
  private static final List<Part> NUMBER_AND_STREET =
      List.of(Part.BUILDING_NUMBER, Part.STREET);

  /** One rule for each combination of {@link #DECIDING_PARTS}; exactly one matches any address. */
  static final List<Rule> RULES =
      List.of(
          new Rule(
              "sub-building, building name and number",
              EnumSet.of(Part.SUB_BUILDING_NAME, Part.BUILDING_NAME, Part.BUILDING_NUMBER),
              List.of(Part.SUB_BUILDING_NAME, Part.BUILDING_NAME),
              NUMBER_AND_STREET),
          new Rule(
              "sub-building and building name (e.g. LU4 9PL)",
              EnumSet.of(Part.SUB_BUILDING_NAME, Part.BUILDING_NAME),
              List.of(Part.SUB_BUILDING_NAME, Part.BUILDING_NAME),
              STREET_ONLY),
          new Rule(
              "building name and number (e.g. CH1 1DF Unit R2)",
              EnumSet.of(Part.BUILDING_NAME, Part.BUILDING_NUMBER),
              List.of(Part.BUILDING_NAME),
              NUMBER_AND_STREET),
          new Rule(
              "sub-building and number (e.g. CH1 1DF Unit R1, IM1 1BD flats)",
              EnumSet.of(Part.SUB_BUILDING_NAME, Part.BUILDING_NUMBER),
              List.of(Part.SUB_BUILDING_NAME),
              NUMBER_AND_STREET),
          new Rule(
              "building name only",
              EnumSet.of(Part.BUILDING_NAME),
              List.of(Part.BUILDING_NAME),
              STREET_ONLY),
          new Rule(
              "sub-building only",
              EnumSet.of(Part.SUB_BUILDING_NAME),
              List.of(Part.SUB_BUILDING_NAME),
              STREET_ONLY),
          new Rule(
              "building number only",
              EnumSet.of(Part.BUILDING_NUMBER),
              List.of(Part.BUILDING_NUMBER),
              STREET_ONLY),
          new Rule("street only", EnumSet.noneOf(Part.class), List.of(), STREET_ONLY));

  private LegacyAddressLayout() {}

  static String premise(RoyalMailAddressLookup address) {
    return render(address, select(address).premise());
  }

  static String addressLine1(RoyalMailAddressLookup address) {
    return render(address, select(address).addressLine1());
  }

  /**
   * Finds the rule for an address.
   *
   * @param address address to lay out
   * @return the single rule whose present parts equal the parts the address has
   */
  static Rule select(RoyalMailAddressLookup address) {
    Set<Part> present = presentParts(address);
    return RULES.stream()
        .filter(rule -> rule.presentParts().equals(present))
        .findFirst()
        .orElseThrow();
  }

  static String join(String... parts) {
    return Stream.of(parts)
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(part -> !part.isBlank())
        .collect(Collectors.joining(" "));
  }

  /** Returns which of the {@link #DECIDING_PARTS} have a non-blank value on the address. */
  private static Set<Part> presentParts(RoyalMailAddressLookup address) {
    Set<Part> present = EnumSet.noneOf(Part.class);
    DECIDING_PARTS.stream().filter(part -> has(address, part)).forEach(present::add);
    return present;
  }

  private static boolean has(RoyalMailAddressLookup address, Part part) {
    String value = part.extract(address);
    return value != null && !value.isBlank();
  }

  private static String render(RoyalMailAddressLookup address, List<Part> parts) {
    return join(parts.stream().map(part -> part.extract(address)).toArray(String[]::new));
  }
}
