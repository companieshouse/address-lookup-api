package uk.gov.companieshouse.addresslookup.dbbootstrap;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Checks which TLS versions the server accepts, the way a client would see them: PostgreSQL's SSLRequest, then a
 * handshake pinned to one protocol version.
 *
 * <p>It needs no CA bundle. The server has accepted the version once it sends its certificate, so the probe records
 * the negotiated version at that point and then rejects the certificate. The handshake therefore never completes, and
 * nothing (no startup message, no credentials) is ever sent to the server.
 *
 * <p>{@link #plaintext} checks that a client without TLS is refused. Aurora applies {@code rds.force_ssl} through
 * pg_hba.conf and does not expose it as a setting, so the refusal itself is the evidence. It sends a startup message
 * for {@link #PROBE_ROLE}, which need not exist, and stops at the server's first answer; it never sends a password.
 *
 * <p>This is a protocol-only check, not endpoint authentication. An {@link Result#ACCEPTED} result does not mean
 * the certificate is trusted or matches the hostname. Credential-bearing JDBC connections enforce encryption
 * separately through their {@code sslmode}.
 */
public final class TlsProtocolProbe {

  /** PostgreSQL SSLRequest code (1234 << 16 | 5679). */
  private static final int SSL_REQUEST_CODE = 80877103;

  /** PostgreSQL protocol 3.0 (3 << 16). */
  private static final int PROTOCOL_3_0 = 196608;

  /** SQLSTATE invalid_authorization_specification: pg_hba.conf has no entry, or rejects the connection. */
  private static final String HBA_REJECTED = "28000";

  static final String PROBE_ROLE = "address_lookup_tls_probe";

  /**
   * Protocol support only; ACCEPTED does not indicate a trusted certificate or an authenticated endpoint.
   * UNEXPECTED is any other answer to the plaintext probe.
   */
  public enum Result {
    ACCEPTED,
    REFUSED,
    SSL_NOT_SUPPORTED,
    UNEXPECTED
  }

  /** @param detail why the version was refused, for the verify report; null when accepted */
  public record Outcome(Result result, String detail) {}

  private final Duration timeout;

  public TlsProtocolProbe(Duration timeout) {
    this.timeout = timeout;
  }

  public Outcome probe(String host, int port, String protocol)
      throws IOException, GeneralSecurityException {
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
        return new Outcome(
            Result.SSL_NOT_SUPPORTED, "server answered the SSLRequest with " + answer);
      }

      StopAtServerCertificate stopper = new StopAtServerCertificate();
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, new TrustManager[] {stopper}, null);
      try (SSLSocket tls =
          (SSLSocket) context.getSocketFactory().createSocket(socket, host, port, true)) {
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

  /**
   * Sends a startup message without TLS. REFUSED when pg_hba.conf rejects it (SQLSTATE 28000, e.g.
   * "no pg_hba.conf entry for host ..., no encryption"); ACCEPTED when the server asks for authentication instead.
   */
  public Outcome plaintext(String host, int port, String database) throws IOException {
    int millis = Math.toIntExact(timeout.toMillis());
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), millis);
      socket.setSoTimeout(millis);
      byte[] parameters =
          startupParameters(
              "user",
              PROBE_ROLE,
              "database",
              database,
              "application_name",
              "address-lookup-db-bootstrap-tls-probe");
      DataOutputStream out = new DataOutputStream(socket.getOutputStream());
      out.writeInt(8 + parameters.length);
      out.writeInt(PROTOCOL_3_0);
      out.write(parameters);
      out.flush();

      DataInputStream in = new DataInputStream(socket.getInputStream());
      int type = in.read();
      if (type == 'R') {
        return new Outcome(Result.ACCEPTED, "the server asked a non-TLS client to authenticate");
      }
      if (type != 'E') {
        return new Outcome(Result.UNEXPECTED, "server answered the startup message with " + type);
      }
      int length = in.readInt();
      if (length < 4 || length > 8192) {
        return new Outcome(Result.UNEXPECTED, "error response length " + length);
      }
      byte[] body = new byte[length - 4];
      in.readFully(body);
      String sqlState = null;
      String message = null;
      int i = 0;
      while (i < body.length && body[i] != 0) {
        byte field = body[i++];
        int end = i;
        while (end < body.length && body[end] != 0) {
          end++;
        }
        String value = new String(body, i, end - i, StandardCharsets.UTF_8);
        if (field == 'C') {
          sqlState = value;
        } else if (field == 'M') {
          message = value;
        }
        i = end + 1;
      }
      return HBA_REJECTED.equals(sqlState)
          ? new Outcome(Result.REFUSED, message)
          : new Outcome(Result.UNEXPECTED, sqlState + ": " + message);
    }
  }

  private static byte[] startupParameters(String... keysAndValues) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (String value : keysAndValues) {
      bytes.writeBytes(value.getBytes(StandardCharsets.UTF_8));
      bytes.write(0);
    }
    bytes.write(0);
    return bytes.toByteArray();
  }

  /** Records the negotiated protocol when the server certificate arrives, then rejects every certificate. */
  private static final class StopAtServerCertificate extends X509ExtendedTrustManager {

    private final AtomicReference<String> negotiated = new AtomicReference<>();

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
        throws CertificateException {
      if (socket instanceof SSLSocket tls) {
        recordProtocol(tls.getHandshakeSession());
      }
      throw stop();
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
        throws CertificateException {
      recordProtocol(engine.getHandshakeSession());
      throw stop();
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
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
    public void checkClientTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      throw stop();
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }

    private void recordProtocol(SSLSession session) {
      if (session != null) {
        negotiated.set(session.getProtocol());
      }
    }

    private static CertificateException stop() {
      return new CertificateException(
          "TLS version probe: the handshake is stopped at the server certificate");
    }
  }
}
