package uk.gov.companieshouse.addresslookup.dbbootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void refusesAClientWithoutTlsWhenPgHbaRejectsIt() throws Exception {
        String message = "no pg_hba.conf entry for host \"127.0.0.1\", user \"address_lookup_tls_probe\", "
                + "database \"addressdb\", SSL off";
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Future<String> startup = plaintextServer(server, errorResponse("28000", message));

            TlsProtocolProbe.Outcome outcome = probe().plaintext("localhost", server.getLocalPort(), "addressdb");

            assertEquals(TlsProtocolProbe.Result.REFUSED, outcome.result());
            assertEquals(message, outcome.detail());
            assertEquals("user=address_lookup_tls_probe database=addressdb "
                    + "application_name=address-lookup-db-bootstrap-tls-probe; then nothing", startup.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void reportsAServerThatAsksAClientWithoutTlsToAuthenticateAndSendsNoPassword() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Future<String> startup = plaintextServer(server, authenticationSasl());

            TlsProtocolProbe.Outcome outcome = probe().plaintext("localhost", server.getLocalPort(), "addressdb");

            assertEquals(TlsProtocolProbe.Result.ACCEPTED, outcome.result());
            assertTrue(startup.get(5, TimeUnit.SECONDS).endsWith("; then nothing"));
        }
    }

    @Test
    void reportsAnyOtherErrorToAClientWithoutTlsAsUnexpected() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            plaintextServer(server, errorResponse("53300", "sorry, too many clients already"));

            TlsProtocolProbe.Outcome outcome = probe().plaintext("localhost", server.getLocalPort(), "addressdb");

            assertEquals(TlsProtocolProbe.Result.UNEXPECTED, outcome.result());
            assertEquals("53300: sorry, too many clients already", outcome.detail());
        }
    }

    /**
     * Reads one protocol 3.0 startup message (no SSLRequest), answers with {@code reply}, then returns the startup
     * parameters followed by "; then nothing" if the client closed without sending anything else.
     */
    private Future<String> plaintextServer(ServerSocket server, byte[] reply) {
        return executor.submit(() -> {
            try (Socket socket = server.accept()) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                byte[] body = new byte[in.readInt() - 4];
                in.readFully(body);
                DataInputStream message = new DataInputStream(new ByteArrayInputStream(body));
                assertEquals(196608, message.readInt());
                String[] parts = new String(body, 4, body.length - 6, StandardCharsets.UTF_8).split("\0");
                StringBuilder parameters = new StringBuilder();
                for (int i = 0; i + 1 < parts.length; i += 2) {
                    parameters.append(i == 0 ? "" : " ").append(parts[i]).append('=').append(parts[i + 1]);
                }
                socket.getOutputStream().write(reply);
                socket.getOutputStream().flush();
                int next;
                try {
                    next = in.read();
                } catch (SocketException reset) {
                    // closing with the server's reply unread resets the connection; nothing more was sent
                    next = -1;
                }
                return parameters + (next == -1 ? "; then nothing" : "; then more");
            }
        });
    }

    private static byte[] errorResponse(String sqlState, String message) throws Exception {
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        for (String field : new String[] {"SFATAL", "VFATAL", "C" + sqlState, "M" + message}) {
            fields.writeBytes(field.getBytes(StandardCharsets.UTF_8));
            fields.write(0);
        }
        fields.write(0);
        return frame('E', fields.toByteArray());
    }

    private static byte[] authenticationSasl() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        new DataOutputStream(body).writeInt(10);
        body.writeBytes("SCRAM-SHA-256".getBytes(StandardCharsets.UTF_8));
        body.write(0);
        body.write(0);
        return frame('R', body.toByteArray());
    }

    private static byte[] frame(char type, byte[] body) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(type);
        out.writeInt(4 + body.length);
        out.write(body);
        return bytes.toByteArray();
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
