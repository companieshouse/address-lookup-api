package uk.gov.companieshouse.addresslookup.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class RoyalMailAddressLookupRepositoryTest {

    @Test
    void queriesQualifiedAddressLookupView() throws NoSuchMethodException {
        Query query = RoyalMailAddressLookupRepository.class
                .getMethod("findByNormalizedPostcode", String.class)
                .getAnnotation(Query.class);

        assertTrue(query.value().contains("FROM address_lookup.address_lookup"));
        assertFalse(query.value().contains("FROM royalmail_address_lookup"));
    }
}
