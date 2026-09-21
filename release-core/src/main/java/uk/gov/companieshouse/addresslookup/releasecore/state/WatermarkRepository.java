package uk.gov.companieshouse.addresslookup.releasecore.state;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface WatermarkRepository extends JpaRepository<Watermark, Boolean> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select w from Watermark w where w.singleton=true")
  Watermark lock();
}
