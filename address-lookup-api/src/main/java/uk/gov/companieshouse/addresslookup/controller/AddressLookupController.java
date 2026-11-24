package uk.gov.companieshouse.addresslookup.controller;

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
import uk.gov.companieshouse.addresslookup.validation.PostcodeValidator;

import java.util.List;

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
        if (postcode.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found");
        }
        if (!PostcodeValidator.isValid(postcode) || containsWhitespace(postcode)) {
            return List.of();
        }
        return addressLookupService.lookupLegacyAddressesByPostcode(postcode);
    }

    @GetMapping("/postcode")
    public LegacyAddress lookupPostcode(@RequestParam("postcode") String postcode) {
        if (postcode.isEmpty()
                || !PostcodeValidator.isValid(postcode)
                || containsWhitespace(postcode)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found");
        }
        return addressLookupService
                .lookupLegacyAddressByPostcode(postcode)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Address not found"));
    }

    // Stored postcodes are matched in compact form only, so spaced input never matches.
    private static boolean containsWhitespace(String postcode) {
        return postcode.chars().anyMatch(Character::isWhitespace);
    }

    private static boolean containsNonAlphanumeric(String postcode) {
        return postcode.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
    }

    // Empty input or non-alphanumeric characters (including whitespace) can never match a stored
    // postcode, so they are reported as not found rather than as malformed requests.
    private static void validatePostcode(String postcode) {
        if (postcode.isEmpty() || containsNonAlphanumeric(postcode)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found");
        }

        if (!PostcodeValidator.isValid(postcode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid postcode");
        }
    }
}
