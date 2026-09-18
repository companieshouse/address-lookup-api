package uk.gov.companieshouse.addresslookup.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gov.companieshouse.addresslookup.entity.RoyalMailAddress;

public interface RoyalMailAddressRepository extends JpaRepository<RoyalMailAddress, Integer> {

    @Query(value = """
            SELECT udprn,
                   organisationname,
                   departmentname,
                   subbuildingname,
                   buildingname,
                   buildingnumber,
                   dependentthoroughfare,
                   thoroughfare,
                   doubledependentlocality,
                   dependentlocality,
                   posttown,
                   postcode,
                   deliverypointsuffix,
                   uprn,
                   latitude,
                   longitude
            FROM add_gb_royalmailaddress
            WHERE upper(replace(postcode, ' ', '')) = :postcode
            ORDER BY buildingnumber, buildingname, organisationname, udprn
            """, nativeQuery = true)
    List<RoyalMailAddress> findByNormalizedPostcode(@Param("postcode") String postcode);
}
