package uk.gov.companieshouse.addresslookup.controller;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.companieshouse.addresslookup.model.AddressLookupResponse;
import uk.gov.companieshouse.addresslookup.model.LegacyAddress;
import uk.gov.companieshouse.addresslookup.model.RoyalMailAddressDto;
import uk.gov.companieshouse.addresslookup.service.AddressLookupService;

@RestController
@RequestMapping("/address-lookup-api")
public class AddressLookupController {

    private final AddressLookupService addressLookupService;

    public AddressLookupController(AddressLookupService addressLookupService) {
        this.addressLookupService = addressLookupService;
    }

    @GetMapping("/addresses")
    public AddressLookupResponse lookupAddress(@RequestParam("postcode") String postcode) {
        validatePostcode(postcode);
        List<RoyalMailAddressDto> addresses = addressLookupService.lookupByPostcode(postcode);
        return new AddressLookupResponse(postcode, addresses.size(), addresses);
    }

    @GetMapping("/multiple-addresses")
    public List<LegacyAddress> lookupMultipleAddresses(@RequestParam("postcode") String postcode) {
        validatePostcode(postcode);
        return addressLookupService.lookupLegacyAddressesByPostcode(postcode);
    }

    @GetMapping("/postcode")
    public LegacyAddress lookupPostcode(@RequestParam("postcode") String postcode) {
        validatePostcode(postcode);
        return addressLookupService.lookupLegacyAddressByPostcode(postcode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found"));
    }

    private void validatePostcode(String postcode) {
        if (postcode == null || postcode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Postcode is required");
        }
    }
}
