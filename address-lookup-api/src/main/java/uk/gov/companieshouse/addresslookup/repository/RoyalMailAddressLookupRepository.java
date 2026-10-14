package uk.gov.companieshouse.addresslookup.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gov.companieshouse.addresslookup.entity.RoyalMailAddressLookup;

import java.util.List;

public interface RoyalMailAddressLookupRepository extends JpaRepository<RoyalMailAddressLookup, String> {

    // The Liquibase changelog creates the lookup view as address_lookup.address_lookup,
    // and this repository's entity maps to that same view. Querying
    // royalmail_address_lookup targets a different, nonexistent relation and caused
    // the local lookup integration test to fail at runtime.
    @Query(value = """
            SELECT *
            FROM address_lookup.address_lookup
            WHERE upper(replace(postcode, ' ', '')) = :postcode
            ORDER BY buildingnumber, buildingname, organisationname, udprn, address_source
            """, nativeQuery = true)
    List<RoyalMailAddressLookup> findByNormalizedPostcode(@Param("postcode") String postcode);
}
