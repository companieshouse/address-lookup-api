package uk.gov.companieshouse.addresslookup.service;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import uk.gov.companieshouse.addresslookup.model.RoyalMailAddressDto;

/** Opt-in English Royal Mail lookup; never stores Places data as FULL CSV records. */
@Component
public class OsPlacesClient {
  private static final Logger LOG = LoggerFactory.getLogger(OsPlacesClient.class);
  private static final int PAGE_SIZE = 100;
  private static final int MAX_RESULTS = 10_000;
  private final boolean enabled;
  private final RestClient client;

  /** Builds the bounded HTTPS client; credentials are required only when enabled. */
  @Autowired
  public OsPlacesClient(
      @Value("${os.places.fallback-enabled:false}") boolean enabled,
      @Value("${os.places.api-key:}") String key) {
    this(enabled, key, builder());
  }

  OsPlacesClient(boolean enabled, String key, RestClient.Builder builder) {
    if (enabled && (key.isBlank() || key.contains("\r") || key.contains("\n"))) {
      throw new IllegalArgumentException("Enabled OS Places fallback requires a valid OS_API_KEY.");
    }
    this.enabled = enabled;
    this.client =
        builder.baseUrl("https://api.os.uk/search/places/v1").defaultHeader("key", key).build();
  }

  private static RestClient.Builder builder() {
    var http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(5));
    return RestClient.builder().requestFactory(factory);
  }

  /** Returns complete deduplicated DPA results, or the existing empty result when disabled. */
  public List<Address> lookup(String postcode) {
    if (!enabled) {
      return List.of();
    }
    String normalized = normalize(postcode);
    if (!normalized.matches("[A-Z]{1,2}[0-9][A-Z0-9]?[0-9][A-Z]{2}")) {
      LOG.debug("Skipping OS fallback for invalid postcode syntax");
      return List.of();
    }
    try {
      var addresses = new TreeMap<Integer, Address>();
      int offset = 0;
      int total = -1;
      do {
        final int requestedOffset = offset;
        JsonNode page =
            client
                .get()
                .uri(uri ->
                    uri.path("/postcode")
                        .queryParam("postcode", normalized)
                        .queryParam("dataset", "DPA")
                        .queryParam("lr", "EN")
                        .queryParam("output_srs", "EPSG:4326")
                        .queryParam("maxresults", PAGE_SIZE)
                        .queryParam("offset", requestedOffset)
                        .build())
                .retrieve()
                .body(JsonNode.class);
        if (page == null
            || !page.path("header").path("totalresults").isIntegralNumber()
            || !page.path("header").path("offset").isIntegralNumber()
            || page.path("header").path("offset").asLong() != offset) {
          throw new IllegalArgumentException("Invalid Places page.");
        }
        long reported = page.path("header").path("totalresults").asLong();
        if (reported < 0 || reported > MAX_RESULTS || (total >= 0 && reported != total)) {
          throw new IllegalArgumentException("Invalid or changing Places total.");
        }
        total = (int) reported;
        var results = page.path("results");
        // Places omits results altogether for a genuine zero-match response.
        if (total == 0 && (results.isMissingNode() || results.isNull())) {
          return List.of();
        }
        if (!results.isArray()
            || results.size() > PAGE_SIZE
            || offset + results.size() > total
            || (results.isEmpty() && offset < total)) {
          throw new IllegalArgumentException("Incomplete Places page.");
        }
        for (JsonNode result : results) {
          Address address = address(result.path("DPA"), normalized);
          Address previous = addresses.putIfAbsent(address.address().udprn(), address);
          if (previous != null && !previous.equals(address)) {
            throw new IllegalArgumentException("Conflicting Places identifier.");
          }
        }
        offset += results.size();
      } while (offset < total);
      return List.copyOf(addresses.values());
    } catch (RestClientException | IllegalArgumentException exception) {
      LOG.warn("OS Places fallback failed ({})", exception.getClass().getSimpleName());
      // Upstream errors can contain response bodies; do not expose them or authentication.
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OS Places lookup failed");
    }
  }

  private static Address address(JsonNode dpa, String postcode) {
    if (!dpa.isObject()
        || !"EN".equals(text(dpa, "LANGUAGE"))
        || !postcode.equals(normalize(required(dpa, "POSTCODE")))) {
      throw new IllegalArgumentException("Unexpected Places address.");
    }
    String id = required(dpa, "UDPRN");
    if (!id.matches("[0-9]{1,8}") || Integer.parseInt(id) <= 0) {
      throw new IllegalArgumentException("Invalid Places UDPRN.");
    }
    BigDecimal latitude = coordinate(dpa, "LAT", 90);
    BigDecimal longitude = coordinate(dpa, "LNG", 180);
    var dto =
        new RoyalMailAddressDto(
            Integer.valueOf(id),
            number(dpa, "UPRN"),
            text(dpa, "ORGANISATION_NAME"),
            text(dpa, "DEPARTMENT_NAME"),
            text(dpa, "SUB_BUILDING_NAME"),
            text(dpa, "BUILDING_NAME"),
            text(dpa, "BUILDING_NUMBER") == null
                ? null
                : Integer.valueOf(text(dpa, "BUILDING_NUMBER")),
            text(dpa, "DEPENDENT_THOROUGHFARE_NAME"),
            text(dpa, "THOROUGHFARE_NAME"),
            text(dpa, "DOUBLE_DEPENDENT_LOCALITY"),
            text(dpa, "DEPENDENT_LOCALITY"),
            required(dpa, "POST_TOWN"),
            required(dpa, "POSTCODE"),
            text(dpa, "DELIVERY_POINT_SUFFIX"),
            latitude,
            longitude);
    String country =
        switch (required(dpa, "COUNTRY_CODE")) {
          case "E" -> "England";
          case "S" -> "Scotland";
          case "W" -> "Wales";
          case "N" -> "Northern Ireland";
          case "J", "L" -> "Channel Islands";
          case "M" -> "Isle of Man";
          default -> throw new IllegalArgumentException("Unsupported Places country.");
        };
    return new Address(dto, country);
  }

  private static BigDecimal coordinate(JsonNode dpa, String field, int limit) {
    BigDecimal value = new BigDecimal(required(dpa, field));
    if (value.abs().compareTo(BigDecimal.valueOf(limit)) > 0) {
      throw new IllegalArgumentException("Invalid Places coordinate.");
    }
    return value;
  }

  private static Long number(JsonNode dpa, String field) {
    String value = text(dpa, field);
    return value == null ? null : Long.valueOf(value);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    if (!value.isValueNode()) {
      throw new IllegalArgumentException("Invalid Places field.");
    }
    String result = value.asText();
    return result.isBlank() ? null : result;
  }

  private static String required(JsonNode node, String field) {
    String value = text(node, field);
    if (value == null) {
      throw new IllegalArgumentException("Missing Places field.");
    }
    return value;
  }

  private static String normalize(String postcode) {
    return postcode.replace(" ", "").toUpperCase(Locale.ROOT);
  }

  /** Country remains internal because the structured public DTO has no country field. */
  public record Address(RoyalMailAddressDto address, String country) {}
}
