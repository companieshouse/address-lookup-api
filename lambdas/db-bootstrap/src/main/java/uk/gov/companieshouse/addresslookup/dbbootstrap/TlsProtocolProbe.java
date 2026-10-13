package uk.gov.companieshouse.addresslookup.dbbootstrap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;

/**
 * Checks which TLS versions the server accepts, the way a client would see them: PostgreSQL's SSLRequest, then a
 * handshake pinned to one protocol version. No startup message or credentials are ever sent.
 */
public final class TlsProtocolProbe {

    /** PostgreSQL SSLRequest code (1234 << 16 | 5679). */
    private static final int SSL_REQUEST_CODE = 80877103;

    public enum Result { ACCEPTED, REFUSED, SSL_NOT_SUPPORTED }

    /** @param detail why the handshake failed, for the verify report; null when accepted */
    public record Outcome(Result result, String detail) {
    }

    private final TrustManager[] trustManagers;
    private final Duration timeout;

    public TlsProtocolProbe(TrustManager[] trustManagers, Duration timeout) {
        this.trustManagers = trustManagers;
        this.timeout = timeout;
    }

    /** Trusts only the certificates in a PEM bundle, e.g. the RDS eu-west-2 CA bundle. */
    public static TlsProtocolProbe trusting(Path pemBundle, Duration timeout) throws IOException, GeneralSecurityException {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        try (InputStream in = Files.newInputStream(pemBundle)) {
            int i = 0;
            for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                trustStore.setCertificateEntry("ca-" + i++, certificate);
            }
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        return new TlsProtocolProbe(factory.getTrustManagers(), timeout);
    }

    public Outcome probe(String host, int port, String protocol) throws IOException, GeneralSecurityException {
        int millis = Math.toIntExact(timeout.toMillis());
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), millis);
            socket.setSoTimeout(millis);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(8);
            out.writeInt(SSL_REQUEST_CODE);
            out.flush();
            int answer = new DataInputStream(socket.getInputStream()).read();
            if (answer != 'S') {
                return new Outcome(Result.SSL_NOT_SUPPORTED, "server answered the SSLRequest with " + answer);
            }

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers, null);
            try (SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(socket, host, port, true)) {
                tls.setEnabledProtocols(new String[] {protocol});
                tls.startHandshake();
                String negotiated = tls.getSession().getProtocol();
                return protocol.equals(negotiated)
                        ? new Outcome(Result.ACCEPTED, null)
                        : new Outcome(Result.REFUSED, "negotiated " + negotiated);
            } catch (SSLException e) {
                return new Outcome(Result.REFUSED, e.getMessage());
            }
        }
    }
}
