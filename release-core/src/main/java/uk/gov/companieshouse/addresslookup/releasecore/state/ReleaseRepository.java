package uk.gov.companieshouse.addresslookup.releasecore.state;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReleaseRepository extends JpaRepository<Release, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from Release r where r.id=:id")
  Optional<Release> lock(@org.springframework.data.repository.query.Param("id") UUID id);

  List<Release> findTop20ByStatusOrderByValidToAsc(Release.Status status);

  List<Release> findByStatusOrderByValidToAsc(Release.Status status);
}
