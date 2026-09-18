package uk.gov.companieshouse.addresslookup.mapper;

import java.util.List;
import org.mapstruct.Mapper;
import uk.gov.companieshouse.addresslookup.entity.RoyalMailAddressLookup;
import uk.gov.companieshouse.addresslookup.model.RoyalMailAddressDto;

@Mapper(componentModel = "spring")
public interface RoyalMailAddressMapper {

    RoyalMailAddressDto toDto(RoyalMailAddressLookup address);

    List<RoyalMailAddressDto> toDtos(List<RoyalMailAddressLookup> addresses);
}
