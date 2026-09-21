package uk.gov.companieshouse.release.model;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared wire-format mapper. Configure once here, before any release is read or written. */
public final class ReleaseJson {
  public static final ObjectMapper MAPPER = new ObjectMapper();

  private ReleaseJson() {}
}
