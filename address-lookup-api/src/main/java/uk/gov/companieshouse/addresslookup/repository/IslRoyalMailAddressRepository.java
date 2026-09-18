package uk.gov.companieshouse.addresslookup.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gov.companieshouse.addresslookup.entity.IslRoyalMailAddress;

public interface IslRoyalMailAddressRepository extends JpaRepository<IslRoyalMailAddress, Integer> {

    @Query(value = """
            SELECT *
            FROM add_isl_royalmailaddress
            WHERE upper(replace(postcode, ' ', '')) = :postcode
            ORDER BY buildingnumber, buildingname, organisationname, udprn
            """, nativeQuery = true)
    List<IslRoyalMailAddress> findByNormalizedPostcode(@Param("postcode") String postcode);
}
