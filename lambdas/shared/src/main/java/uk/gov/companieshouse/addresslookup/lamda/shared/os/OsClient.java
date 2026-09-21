package uk.gov.companieshouse.addresslookup.lamda.shared.os;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.*;

/** API key is sent only to api.os.uk, never forwarded to the signed download host. */
public final class OsClient {
  private final HttpClient http;
  private final String key;
  private final Duration requestTimeout;
  private final com.fasterxml.jackson.databind.ObjectMapper json;

  public OsClient(
      String key,
      Duration connectTimeout,
      Duration requestTimeout,
      com.fasterxml.jackson.databind.ObjectMapper json) {
    this.key = key;
    this.requestTimeout = requestTimeout;
    this.json = json;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public InputStream download(String url) throws Exception {
    URI uri = URI.create(url);
    check(
        "https".equals(uri.getScheme()) && "api.os.uk".equals(uri.getHost()),
        "Expected OS API URL");
    for (int i = 0; i < 5; i++) {
      check("https".equals(uri.getScheme()), "HTTPS required");
      var b = HttpRequest.newBuilder(uri).timeout(requestTimeout).GET();
      if ("api.os.uk".equals(uri.getHost())) b.header("key", key);
      var r = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
      if (r.statusCode() == 200) return r.body();
      r.body().close();
      check(
          java.util.Set.of(301, 302, 303, 307, 308).contains(r.statusCode()),
          "OS HTTP status " + r.statusCode());
      uri = uri.resolve(r.headers().firstValue("location").orElseThrow());
    }
    throw new IOException("Too many redirects");
  }

  public JsonNode json(String url) throws Exception {
    try (var in = download(url)) {
      byte[] b = in.readNBytes(4 * 1024 * 1024 + 1);
      check(b.length <= 4 * 1024 * 1024, "OS metadata too large");
      return json.readTree(b);
    }
  }
}
