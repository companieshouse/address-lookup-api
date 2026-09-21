package uk.gov.companieshouse.addresslookup.releasecore.state;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReleaseFileRepository extends JpaRepository<ReleaseFile, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select f from ReleaseFile f where f.releaseId=:releaseId and f.dataset=:dataset")
  Optional<ReleaseFile> lock(
      @org.springframework.data.repository.query.Param("releaseId") UUID releaseId,
      @org.springframework.data.repository.query.Param("dataset") String dataset);

  List<ReleaseFile> findByReleaseId(UUID releaseId);
}
