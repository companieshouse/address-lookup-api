package uk.gov.companieshouse.addresslookup.dbbootstrap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks which TLS versions the server accepts, the way a client would see them: PostgreSQL's SSLRequest, then a
 * handshake pinned to one protocol version.
 *
 * <p>It needs no CA bundle. The server has accepted the version once it sends its certificate, so the probe records
 * the negotiated version at that point and then rejects the certificate. The handshake therefore never completes, and
 * nothing (no startup message, no credentials) is ever sent to the server.
 *
 * <p>This is a protocol-only check, not endpoint authentication. An {@link Result#ACCEPTED} result does not mean
 * the certificate is trusted or matches the hostname. Credential-bearing JDBC connections must independently
 * verify both the certificate chain and hostname before authenticating.
 */
public final class TlsProtocolProbe {

    /** PostgreSQL SSLRequest code (1234 << 16 | 5679). */
    private static final int SSL_REQUEST_CODE = 80877103;

    /** Protocol support only; ACCEPTED does not indicate a trusted certificate or an authenticated endpoint. */
    public enum Result { ACCEPTED, REFUSED, SSL_NOT_SUPPORTED }

    /** @param detail why the version was refused, for the verify report; null when accepted */
    public record Outcome(Result result, String detail) {
    }

    private final Duration timeout;

    public TlsProtocolProbe(Duration timeout) {
        this.timeout = timeout;
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

            StopAtServerCertificate stopper = new StopAtServerCertificate();
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {stopper}, null);
            try (SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(socket, host, port, true)) {
                tls.setEnabledProtocols(new String[] {protocol});
                tls.startHandshake();
                return new Outcome(Result.REFUSED, "handshake completed without a server certificate");
            } catch (SSLException e) {
                String negotiated = stopper.negotiated.get();
                if (negotiated == null) {
                    return new Outcome(Result.REFUSED, e.getMessage());
                }
                return protocol.equals(negotiated)
                        ? new Outcome(Result.ACCEPTED, null)
                        : new Outcome(Result.REFUSED, "negotiated " + negotiated);
            }
        }
    }

    /** Records the negotiated protocol when the server certificate arrives, then rejects every certificate. */
    private static final class StopAtServerCertificate extends X509ExtendedTrustManager {

        private final AtomicReference<String> negotiated = new AtomicReference<>();

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            if (socket instanceof SSLSocket tls) {
                record(tls.getHandshakeSession());
            }
            throw stop();
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            record(engine.getHandshakeSession());
            throw stop();
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw stop();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            throw stop();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            throw stop();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw stop();
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        private void record(SSLSession session) {
            if (session != null) {
                negotiated.set(session.getProtocol());
            }
        }

        private static CertificateException stop() {
            return new CertificateException("TLS version probe: the handshake is stopped at the server certificate");
        }
    }
}
