package uk.gov.companieshouse.addresslookup.lambda.shared.os;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;

final class OsClientTest {
    @Test
    void followsHttpsRedirectAndSendsApiKeyOnlyToApiOsUk() throws Exception {
        var http = new RedirectingHttpClient();
        var os = new OsClient("test-key", Duration.ofSeconds(840), JSON, http);

        try (var in =
                     os.download(
                             "https://api.os.uk/downloads/v1/dataPackages/12345/versions/159159/downloads?fileName=add_isl_royalmailaddress.zip")) {
            assertArrayEquals("zip-bytes".getBytes(), in.readAllBytes());
        }

        assertEquals(2, http.requests.size());
        assertEquals("api.os.uk", http.requests.get(0).uri().getHost());
        assertTrue(http.requests.get(0).headers().firstValue("key").isPresent());
        assertEquals("os-downloads.example", http.requests.get(1).uri().getHost());
        assertTrue(http.requests.get(1).headers().firstValue("key").isEmpty());
    }

    @Test
    void publicConstructorBuildsAClientThatOnlyAcceptsApiOsUkUrls() {
        var os = new OsClient("test-key", Duration.ofSeconds(1), Duration.ofSeconds(1), JSON);

        var failure = assertThrows(IllegalArgumentException.class, () -> os.download("https://example.com/file.zip"));

        assertEquals("Expected OS API URL", failure.getMessage());
    }

    @Test
    void failsAfterTooManyRedirects() {
        var http = new RedirectingHttpClient(request -> redirect(302, "https://api.os.uk/next"));
        var os = new OsClient("test-key", Duration.ofSeconds(840), JSON, http);

        var failure = assertThrows(IOException.class, () -> os.download("https://api.os.uk/start"));

        assertEquals("Too many redirects", failure.getMessage());
        assertEquals(5, http.requests.size());
    }

    @Test
    void rejectsNonRedirectErrorStatus() {
        var http = new RedirectingHttpClient(request -> new Reply(500, Map.of(), new byte[0]));
        var os = new OsClient("test-key", Duration.ofSeconds(840), JSON, http);

        var failure = assertThrows(IllegalArgumentException.class, () -> os.download("https://api.os.uk/start"));

        assertEquals("OS HTTP status 500", failure.getMessage());
        assertEquals(1, http.requests.size());
    }

    @Test
    void rejectsRedirectToHttp() {
        var http = new RedirectingHttpClient(request -> redirect(302, "http://os-downloads.example/file.zip"));
        var os = new OsClient("test-key", Duration.ofSeconds(840), JSON, http);

        var failure = assertThrows(IllegalArgumentException.class, () -> os.download("https://api.os.uk/start"));

        assertEquals("HTTPS required", failure.getMessage());
        assertEquals(1, http.requests.size());
    }

    @Test
    void rejectsRedirectWithoutLocationHeader() {
        var http = new RedirectingHttpClient(request -> new Reply(302, Map.of(), new byte[0]));
        var os = new OsClient("test-key", Duration.ofSeconds(840), JSON, http);

        assertThrows(NoSuchElementException.class, () -> os.download("https://api.os.uk/start"));
    }

    @Test
    void rejectsUrlsOutsideApiOsUk() {
        var http = new RedirectingHttpClient();
        var os = new OsClient("test-key", Duration.ofSeconds(840), JSON, http);

        for (String url : List.of("https://example.com/file.zip", "http://api.os.uk/file.zip")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> os.download(url));
            assertEquals("Expected OS API URL", failure.getMessage());
        }
        assertTrue(http.requests.isEmpty());
    }

    @Test
    void jsonParsesResponseBody() throws Exception {
        var http = new RedirectingHttpClient(request -> new Reply(200, Map.of(), "{\"a\":1}".getBytes()));
        var os = new OsClient("test-key", Duration.ofSeconds(840), JSON, http);

        assertEquals(1, os.json("https://api.os.uk/meta").path("a").asInt());
    }

    @Test
    void jsonRejectsOversizedResponseBody() {
        var http = new RedirectingHttpClient(request -> new Reply(200, Map.of(), new byte[4 * 1024 * 1024 + 1]));
        var os = new OsClient("test-key", Duration.ofSeconds(840), JSON, http);

        var failure = assertThrows(IllegalArgumentException.class, () -> os.json("https://api.os.uk/meta"));

        assertEquals("OS metadata too large", failure.getMessage());
    }

    private static Reply redirect(int status, String location) {
        return new Reply(status, Map.of("location", List.of(location)), new byte[0]);
    }

    private record Reply(int status, Map<String, List<String>> headers, byte[] body) {
    }

    private static final class RedirectingHttpClient extends HttpClient {
        final List<HttpRequest> requests = new ArrayList<>();
        private final Function<HttpRequest, Reply> script;

        RedirectingHttpClient() {
            this(request -> "api.os.uk".equals(request.uri().getHost())
                    ? redirect(307, "https://os-downloads.example/signed/add_isl_royalmailaddress.zip?token=redacted")
                    : new Reply(200, Map.of(), "zip-bytes".getBytes()));
        }

        RedirectingHttpClient(Function<HttpRequest, Reply> script) {
            this.script = script;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            requests.add(request);
            var reply = script.apply(request);
            return response(reply.status(), reply.headers(), handler, reply.body());
        }

        @SuppressWarnings("unchecked")
        private static <T> HttpResponse<T> response(
                int status, Map<String, List<String>> headers, HttpResponse.BodyHandler<T> handler, byte[] body) {
            var info = new ResponseInfo(status, HttpHeaders.of(headers, (k, v) -> true));
            var subscriber = handler.apply(info);
            subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                @Override
                public void request(long n) {
                    // Intentionally empty: the body is pushed synchronously below, so demand needs no tracking.
                }

                @Override
                public void cancel() {
                    // Intentionally empty: the fake response is fully delivered, so there is nothing to cancel.
                }
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
