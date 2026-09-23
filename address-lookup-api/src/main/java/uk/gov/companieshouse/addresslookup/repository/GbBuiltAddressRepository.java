package uk.gov.companieshouse.addresslookup.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gov.companieshouse.addresslookup.entity.GbBuiltAddress;

import java.util.List;

public interface GbBuiltAddressRepository extends JpaRepository<GbBuiltAddress, Long> {

    @Query(value = """
            SELECT *
            FROM add_gb_builtaddress
            WHERE upper(replace(postcode, ' ', '')) = :postcode
            ORDER BY uprn
            """, nativeQuery = true)
    List<GbBuiltAddress> findByNormalizedPostcode(@Param("postcode") String postcode);
}
