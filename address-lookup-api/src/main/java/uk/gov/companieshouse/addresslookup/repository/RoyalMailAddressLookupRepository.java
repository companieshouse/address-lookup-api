package uk.gov.companieshouse.addresslookup.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gov.companieshouse.addresslookup.entity.RoyalMailAddressLookup;

public interface RoyalMailAddressLookupRepository extends JpaRepository<RoyalMailAddressLookup, String> {

    @Query(value = """
            SELECT *
            FROM royalmail_address_lookup
            WHERE upper(replace(postcode, ' ', '')) = :postcode
            ORDER BY buildingnumber, buildingname, organisationname, udprn, address_source
            """, nativeQuery = true)
    List<RoyalMailAddressLookup> findByNormalizedPostcode(@Param("postcode") String postcode);
}
