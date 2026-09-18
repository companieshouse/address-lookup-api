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
    @Mapping(target = "addressLine1", expression = "java(join(address.getDependentThoroughfare(), address.getThoroughfare()))")
    @Mapping(target = "addressLine2", expression = "java(join(address.getDoubleDependentLocality(), address.getDependentLocality()))")
    @Mapping(target = "country", expression = "java(toLegacyCountry(address.getCountry()))")
    LegacyAddress toLegacyAddress(RoyalMailAddressLookup address);

    @Mapping(target = "premise", expression = "java((String) null)")
    @Mapping(target = "addressLine1", expression = "java(join(address.getDependentThoroughfare(), address.getThoroughfare()))")
    @Mapping(target = "addressLine2", expression = "java(join(address.getDoubleDependentLocality(), address.getDependentLocality()))")
    @Mapping(target = "country", expression = "java(toLegacyCountry(address.getCountry()))")
    LegacyAddress toLegacyAddressWithoutPremise(RoyalMailAddressLookup address);

    default String toPremise(RoyalMailAddressLookup address) {
        return join(
                address.getOrganisationName(),
                address.getDepartmentName(),
                address.getSubBuildingName(),
                address.getBuildingName(),
                address.getBuildingNumber() == null ? null : address.getBuildingNumber().toString());
    }

    default String join(String... parts) {
        String value = Stream.of(parts)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .collect(Collectors.joining(", "));

        return value.isBlank() ? null : value;
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
