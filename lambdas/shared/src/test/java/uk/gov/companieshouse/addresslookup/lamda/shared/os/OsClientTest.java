package uk.gov.companieshouse.addresslookup.lamda.shared.os;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.JSON;

final class OsClientTest {
  @Test
  void followsHttpsRedirectAndSendsApiKeyOnlyToApiOsUk() throws Exception {
    var http = new RedirectingHttpClient();
    var os = new OsClient("test-key", Duration.ofSeconds(840), JSON, http);

    try (var in =
        os.download(
            "https://api.os.uk/downloads/v1/dataPackages/20781/versions/145051/downloads?fileName=add_isl_royalmailaddress.zip")) {
      assertArrayEquals("zip-bytes".getBytes(), in.readAllBytes());
    }

    assertEquals(2, http.requests.size());
    assertEquals("api.os.uk", http.requests.get(0).uri().getHost());
    assertTrue(http.requests.get(0).headers().firstValue("key").isPresent());
    assertEquals("os-downloads.example", http.requests.get(1).uri().getHost());
    assertTrue(http.requests.get(1).headers().firstValue("key").isEmpty());
  }

  private static final class RedirectingHttpClient extends HttpClient {
    final List<HttpRequest> requests = new ArrayList<>();

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      requests.add(request);
      if ("api.os.uk".equals(request.uri().getHost()))
        return response(
            307,
            Map.of("location", List.of("https://os-downloads.example/signed/add_isl_royalmailaddress.zip?token=redacted")),
            handler,
            new byte[0]);
      return response(200, Map.of(), handler, "zip-bytes".getBytes());
    }

    @SuppressWarnings("unchecked")
    private static <T> HttpResponse<T> response(
        int status, Map<String, List<String>> headers, HttpResponse.BodyHandler<T> handler, byte[] body) {
      var info = new ResponseInfo(status, HttpHeaders.of(headers, (k, v) -> true));
      var subscriber = handler.apply(info);
      subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
        @Override
        public void request(long n) {}

        @Override
        public void cancel() {}
      });
      if (body.length == 0) {
        subscriber.onComplete();
      } else {
        subscriber.onNext(List.of(java.nio.ByteBuffer.wrap(body)));
        subscriber.onComplete();
      }
      T responseBody = subscriber.getBody().toCompletableFuture().join();
      return new BasicResponse<>(status, info.headers(), responseBody);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> responseBodyHandler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      return null;
    }

    @Override
    public SSLParameters sslParameters() {
      return null;
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }
  }

  private record ResponseInfo(int statusCode, HttpHeaders headers)
      implements HttpResponse.ResponseInfo {
    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }

  private record BasicResponse<T>(int statusCode, HttpHeaders headers, T body)
      implements HttpResponse<T> {
    @Override
    public HttpRequest request() {
      return null;
    }

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return null;
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }
}
