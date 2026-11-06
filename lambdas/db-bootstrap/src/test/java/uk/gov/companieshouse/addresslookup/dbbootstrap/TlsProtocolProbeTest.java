package uk.gov.companieshouse.addresslookup.dbbootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void acceptsTheProtocolWithAnUntrustedCertificateButNeverCompletesTheHandshake() throws Exception {
        AtomicBoolean completed = new AtomicBoolean();

        try (ServerSocket server = server('S', 1, completed)) {
            assertEquals(TlsProtocolProbe.Result.ACCEPTED,
                    probe().probe("localhost", server.getLocalPort(), "TLSv1.3").result());
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertFalse(completed.get());
    }

    @Test
    void recognizesExplicitPlaintextRejectionOnCurrentAndOlderPostgresVersions() throws Exception {
        for (String encryption : new String[] {"no encryption", "SSL off"}) {
            assertEquals(TlsProtocolProbe.Result.REFUSED,
                    plaintextResponse('E', error("28000",
                            "no pg_hba.conf entry for host \"127.0.0.1\", user \"master\", "
                                    + "database \"addressdb\", " + encryption)).result());
        }
    }

    @Test
    void stopsBeforeSendingAPasswordWhenPlaintextAuthenticationIsAllowed() throws Exception {
        assertEquals(TlsProtocolProbe.Result.ACCEPTED,
                plaintextResponse('R', new byte[] {0, 0, 0, 3}).result());
    }

    @Test
    void doesNotTreatUnrelatedErrorsAsProofOfSslEnforcement() {
        assertThrows(IOException.class,
                () -> plaintextResponse('E', error("28P01", "password authentication failed")));
        assertThrows(IOException.class,
                () -> plaintextResponse('E', error("28000", "no pg_hba.conf entry for host \"127.0.0.1\"")));
        assertThrows(IOException.class,
                () -> plaintextResponse('E', error("XX000", "no pg_hba.conf entry, no encryption")));
    }

    @Test
    void failsClosedOnUnexpectedOrMalformedResponses() {
        assertThrows(IOException.class, () -> plaintextResponse('Z', new byte[] {'I'}));
        assertThrows(IOException.class, () -> plaintextResponse('E', new byte[] {'M', 'x'}));
    }

    private static byte[] error(String sqlState, String detail) {
        return ("SFATAL\0C" + sqlState + "\0M" + detail + "\0\0").getBytes(StandardCharsets.UTF_8);
    }

    private TlsProtocolProbe.Outcome plaintextResponse(int type, byte[] payload) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            var response = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    int length = in.readInt();
                    assertEquals(196608, in.readInt());
                    assertEquals("user\0master\0database\0addressdb\0\0",
                            new String(in.readNBytes(length - 8), StandardCharsets.UTF_8));
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeByte(type);
                    out.writeInt(payload.length + 4);
                    out.write(payload);
                    out.flush();
                    assertEquals(-1, in.read(), "the probe must close without sending a password");
                }
                return null;
            });
            try {
                return probe().probePlaintext("localhost", server.getLocalPort(), "master", "addressdb");
            } finally {
                response.get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static TlsProtocolProbe probe() {
        return new TlsProtocolProbe(Duration.ofSeconds(5));
    }

    private ServerSocket server(char answer, int connections) throws Exception {
        return server(answer, connections, new AtomicBoolean());
    }

    /**
     * Answers {@code connections} SSLRequests with {@code answer}, then handshakes with TLS 1.3 only, setting
     * {@code completed} if a handshake ever completes.
     */
    private ServerSocket server(char answer, int connections, AtomicBoolean completed) throws Exception {
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
                            completed.set(true);
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
