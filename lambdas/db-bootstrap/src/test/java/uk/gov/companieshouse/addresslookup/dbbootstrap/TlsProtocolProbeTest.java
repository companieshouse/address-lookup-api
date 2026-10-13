package uk.gov.companieshouse.addresslookup.dbbootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.DataInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Runs the probe against an in-process server that answers like PostgreSQL with ssl_min_protocol_version=TLSv1.3. */
class TlsProtocolProbeTest {

    private static final char[] PASSWORD = "changeit".toCharArray();

    @TempDir
    static Path directory;

    private static KeyStore keyStore;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @BeforeAll
    static void createCertificate() throws Exception {
        Path file = directory.resolve("server.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", file.toString(),
                "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD))
                .redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes());
        assertEquals(0, keytool.waitFor(), output);
        keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            keyStore.load(in, PASSWORD);
        }
    }

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    @Test
    void refusesTls12AndAcceptsTls13() throws Exception {
        TlsProtocolProbe probe = probe();

        try (ServerSocket server = server('S', 2)) {
            assertEquals(TlsProtocolProbe.Result.REFUSED, probe.probe("localhost", server.getLocalPort(), "TLSv1.2").result());
            assertEquals(TlsProtocolProbe.Result.ACCEPTED, probe.probe("localhost", server.getLocalPort(), "TLSv1.3").result());
        }
    }

    @Test
    void reportsAServerWithoutTls() throws Exception {
        try (ServerSocket server = server('N', 1)) {
            assertEquals(TlsProtocolProbe.Result.SSL_NOT_SUPPORTED,
                    probe().probe("localhost", server.getLocalPort(), "TLSv1.3").result());
        }
    }

    @Test
    void refusesAServerItDoesNotTrust() throws Exception {
        KeyStore empty = KeyStore.getInstance("PKCS12");
        empty.load(null, null);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(empty);
        TlsProtocolProbe untrusting = new TlsProtocolProbe(trust.getTrustManagers(), Duration.ofSeconds(5));

        try (ServerSocket server = server('S', 1)) {
            assertEquals(TlsProtocolProbe.Result.REFUSED,
                    untrusting.probe("localhost", server.getLocalPort(), "TLSv1.3").result());
        }
    }

    private static TlsProtocolProbe probe() throws Exception {
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keyStore);
        return new TlsProtocolProbe(trust.getTrustManagers(), Duration.ofSeconds(5));
    }

    /** Answers {@code connections} SSLRequests with {@code answer}, then handshakes with TLS 1.3 only. */
    private ServerSocket server(char answer, int connections) throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(keyStore, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        executor.submit(() -> {
            for (int i = 0; i < connections; i++) {
                try (Socket socket = server.accept()) {
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    assertEquals(8, in.readInt());
                    assertEquals(80877103, in.readInt());
                    socket.getOutputStream().write(answer);
                    socket.getOutputStream().flush();
                    if (answer == 'S') {
                        try (SSLSocket tls = (SSLSocket) context.getSocketFactory()
                                .createSocket(socket, null, socket.getPort(), true)) {
                            tls.setUseClientMode(false);
                            tls.setEnabledProtocols(new String[] {"TLSv1.3"});
                            tls.startHandshake();
                            tls.getInputStream().read();
                        } catch (Exception ignored) {
                            // the refused handshake ends here
                        }
                    }
                } catch (Exception ignored) {
                    return null;
                }
            }
            return null;
        });
        return server;
    }
}
