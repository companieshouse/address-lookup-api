package uk.gov.companieshouse.addresslookup.mapper;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uk.gov.companieshouse.addresslookup.entity.RoyalMailAddressLookup;
import uk.gov.companieshouse.addresslookup.model.LegacyAddress;

@Mapper(componentModel = "spring")
public interface LegacyAddressMapper {

  @Mapping(target = "premise", expression = "java(toPremise(address))")
  @Mapping(
      target = "addressLine1",
      expression = "java(toAddressLine1(address))")
  @Mapping(
      target = "addressLine2",
      expression =
          "java(join(address.getDoubleDependentLocality(), address.getDependentLocality()))")
  @Mapping(target = "country", expression = "java(toLegacyCountry(address.getCountry()))")
  LegacyAddress toLegacyAddress(RoyalMailAddressLookup address);

  @Mapping(target = "premise", expression = "java((String) null)")
  @Mapping(
      target = "addressLine1",
      expression = "java(join(address.getDependentThoroughfare(), address.getThoroughfare()))")
  @Mapping(
      target = "addressLine2",
      expression =
          "java(join(address.getDoubleDependentLocality(), address.getDependentLocality()))")
  @Mapping(target = "country", expression = "java(toLegacyCountry(address.getCountry()))")
  LegacyAddress toLegacyAddressWithoutPremise(RoyalMailAddressLookup address);

  /**
   * Builds the legacy premise. A building name with a building number keeps the number out of the
   * premise (it moves to address line 1); a sub-building with a building name but no number moves
   * the building name to address line 1.
   *
   * @param address address with the premise components
   * @return nonblank components joined by a space, or an empty string
   */
  default String toPremise(RoyalMailAddressLookup address) {
    if (hasText(address.getBuildingName())) {
      if (hasText(address.getBuildingNumber())) {
        return join(address.getSubBuildingName(), address.getBuildingName());
      }
      if (hasText(address.getSubBuildingName())) {
        return join(address.getSubBuildingName());
      }
      return join(address.getBuildingName());
    }
    return join(address.getSubBuildingName(), number(address));
  }

  default String toAddressLine1(RoyalMailAddressLookup address) {
    String street = join(address.getDependentThoroughfare(), address.getThoroughfare());
    if (!hasText(address.getBuildingName())) {
      return street;
    }
    if (hasText(address.getBuildingNumber())) {
      return join(number(address), street);
    }
    return hasText(address.getSubBuildingName()) ? join(address.getBuildingName(), street) : street;
  }

  private static String number(RoyalMailAddressLookup address) {
    return address.getBuildingNumber() == null ? null : address.getBuildingNumber().toString();
  }

  private static boolean hasText(Object value) {
    return value != null && !value.toString().isBlank();
  }

  default String join(String... parts) {
    String value =
        Stream.of(parts)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(part -> !part.isBlank())
            .collect(Collectors.joining(" "));

    return value.isBlank() ? "" : value;
  }

  default String toLegacyCountry(String country) {
    if (country == null || country.isBlank()) {
      return "United Kingdom";
    }

    return switch (country) {
      case "England" -> "GB-ENG";
      case "Scotland" -> "GB-SCT";
      case "Wales" -> "GB-WLS";
      case "Northern Ireland" -> "GB-NIR";
      default -> country;
    };
  }
}
