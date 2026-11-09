package uk.gov.companieshouse.addresslookup.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class OsPlacesClientTest {
  private final RestClient.Builder builder = RestClient.builder();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private final OsPlacesClient client = new OsPlacesClient(true, "test-secret", builder);

  @Test
  void disabledFallbackDoesNotRequireCredentialsOrMakeRequests() {
    assertTrue(new OsPlacesClient(false, "", builder).lookup("CF143AT").isEmpty());
    server.verify();
    assertThrows(IllegalArgumentException.class, () -> new OsPlacesClient(true, "", builder));
    assertThrows(
        IllegalArgumentException.class, () -> new OsPlacesClient(true, "bad\nkey", builder));
  }

  @Test
  void paginatesEnglishDpaAndDeduplicatesIdentifiersBeforeReturning() {
    String first = address("4213550");
    server
        .expect(requestTo(url(0)))
        .andExpect(header("key", "test-secret"))
        .andRespond(withSuccess(page(3, 0, first + "," + first), MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(url(2)))
        .andRespond(withSuccess(page(3, 2, address("4213555")), MediaType.APPLICATION_JSON));
    var result = client.lookup("cf14 3at");
    assertEquals(2, result.size());
    assertEquals(4213550, result.getFirst().address().udprn());
    assertEquals("Wales", result.getFirst().country());
    assertEquals("SOURCE ROAD", result.getFirst().address().thoroughfare());
    assertEquals("51.5", result.getFirst().address().latitude().toPlainString());
    server.verify();
  }

  @Test
  void genuineNoResultsReturnsEmpty() {
    server
        .expect(requestTo(url(0)))
        .andRespond(withSuccess(page(0, 0, ""), MediaType.APPLICATION_JSON));
    assertTrue(client.lookup("CF143AT").isEmpty());
    server.verify();
  }

  @Test
  void acceptsRealZeroMatchResponseWithNoResultsMember() {
    server
        .expect(requestTo(url(0)))
        .andRespond(
            withSuccess(
                "{\"header\":{\"totalresults\":0,\"offset\":0}}", MediaType.APPLICATION_JSON));
    assertTrue(client.lookup("CF143AT").isEmpty());
    server.verify();
  }

  @Test
  void rejectsWrongPostcodeInsteadOfReturningAnUnrelatedAddress() {
    server
        .expect(requestTo(url(0)))
        .andRespond(
            withSuccess(
                page(1, 0, address("4213550").replace("CF14 3AT", "SW1A 1AA")),
                MediaType.APPLICATION_JSON));
    assertBadGateway();
  }

  @Test
  void rejectsEmptyIncompletePagesAndExcessiveTotals() {
    server
        .expect(requestTo(url(0)))
        .andRespond(withSuccess(page(1, 0, ""), MediaType.APPLICATION_JSON));
    assertBadGateway();
    server.verify();
    server.reset();
    server
        .expect(requestTo(url(0)))
        .andRespond(withSuccess(page(10001, 0, ""), MediaType.APPLICATION_JSON));
    assertBadGateway();
  }

  @Test
  void rejectsConflictingDuplicateIdentifiers() {
    String row = address("4213550");
    server
        .expect(requestTo(url(0)))
        .andRespond(
            withSuccess(
                page(2, 0, row + "," + row.replace("SOURCE ROAD", "DIFFERENT ROAD")),
                MediaType.APPLICATION_JSON));
    assertBadGateway();
  }

  @Test
  void reportsUpstreamFailuresWithoutExposingTheBodyOrKey() {
    server
        .expect(requestTo(url(0)))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("test-secret upstream detail"));
    var error = assertBadGateway();
    assertFalse(error.toString().contains("test-secret"));
    assertFalse(error.toString().contains("upstream detail"));
    assertNull(error.getCause());
  }

  @Test
  void reportsTimeoutsAsErrorsNotSuccessfulEmptyAddresses() {
    server
        .expect(requestTo(url(0)))
        .andRespond(request -> {
          throw new SocketTimeoutException("timeout");
        });
    assertBadGateway();
  }

  private ResponseStatusException assertBadGateway() {
    var error = assertThrows(ResponseStatusException.class, () -> client.lookup("CF143AT"));
    assertEquals(HttpStatus.BAD_GATEWAY, error.getStatusCode());
    server.verify();
    return error;
  }

  private static String url(int offset) {
    return "https://api.os.uk/search/places/v1/postcode?postcode=CF143AT"
        + "&dataset=DPA&lr=EN&output_srs=EPSG:4326&maxresults=100&offset="
        + offset;
  }

  private static String page(int total, int offset, String rows) {
    return "{\"header\":{\"totalresults\":"
        + total
        + ",\"offset\":"
        + offset
        + "},\"results\":["
        + rows
        + "]}";
  }

  private static String address(String id) {
    return """
    {"DPA":{"UDPRN":"%s","UPRN":"10002513014","LANGUAGE":"EN",
    "POSTCODE":"CF14 3AT","POST_TOWN":"CARDIFF","BUILDING_NUMBER":"12",
    "THOROUGHFARE_NAME":"SOURCE ROAD","COUNTRY_CODE":"W","LAT":51.5,"LNG":-3.2}}
    """
        .formatted(id);
  }
}
