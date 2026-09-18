package uk.gov.companieshouse.addresslookup.lamda.shared.os;

import java.net.*;
import java.net.http.*;
import java.io.InputStream;
import java.io.IOException;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.JSON;
import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.check;

/**
 * API key is sent only to api.os.uk, never forwarded to the signed download host.
 */
public final class OsClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String key = "api_key"; // todo

    public InputStream download(String url) throws Exception {
        URI uri = URI.create(url);
        check("https".equals(uri.getScheme()) && "api.os.uk".equals(uri.getHost()), "Expected OS API URL");
        for (int i = 0; i < 5; i++) {
            check("https".equals(uri.getScheme()), "HTTPS required");
            var b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(840)).GET();
            if ("api.os.uk".equals(uri.getHost())) b.header("key", key);
            var r = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() == 200) return r.body();
            r.body().close();
            check(java.util.Set.of(301, 302, 303, 307, 308).contains(r.statusCode()), "OS HTTP status " + r.statusCode());
            uri = uri.resolve(r.headers().firstValue("location").orElseThrow());
        }
        throw new IOException("Too many redirects");
    }

    public JsonNode json(String url) throws Exception {
        try (var in = download(url)) {
            byte[] b = in.readNBytes(4 * 1024 * 1024 + 1);
            check(b.length <= 4 * 1024 * 1024, "OS metadata too large");
            return JSON.readTree(b);
        }
    }
}
