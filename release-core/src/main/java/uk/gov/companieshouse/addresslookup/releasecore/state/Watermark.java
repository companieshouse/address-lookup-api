package uk.gov.companieshouse.addresslookup.releasecore.state;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "watermark", schema = "os_control")
public class Watermark {
  @Id
  private boolean singleton = true;

  @Column(name = "valid_from")
  private LocalDate date;

  protected Watermark() {}

  public LocalDate getDate() {
    return date;
  }

  public void advance(LocalDate target) {
    date = target;
  }
}
