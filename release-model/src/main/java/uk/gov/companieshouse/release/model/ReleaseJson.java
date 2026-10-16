package uk.gov.companieshouse.release.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Shared wire-format mapper. Configure once here, before any release is read or written. */
public final class ReleaseJson {
  public static final ObjectMapper MAPPER;
  static {
    MAPPER = new ObjectMapper();
    MAPPER.registerModule(new JavaTimeModule());
  }
  private ReleaseJson() {}
}
