package uk.gov.companieshouse.addresslookup.dbbootstrap;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.ssm.SsmClient;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * Prepares the address lookup database for Flyway (ALS-90). Invoked only by Terraform (aws_lambda_invocation in
 * terraform/groups/aurora); it has no event sources or invoke permissions.
 *
 * <pre>{"command": "bootstrap"}  installs PostGIS, creates or updates the schema owner, then verifies
 * {"command": "verify"}     verifies only</pre>
 *
 * Credentials come from Parameter Store on every invocation; they are never part of the event or the logs.
 */
public final class Handler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOGGER = LoggerFactory.getLogger("address-lookup-db-bootstrap");

    /** Opens a JDBC connection; replaced by a mock in the unit tests. */
    @FunctionalInterface
    interface Connector {
        Connection connect(DatabaseCredentials credentials) throws SQLException;
    }

    private final BootstrapConfiguration config;
    private final Function<String, DatabaseCredentials> credentials;
    private final Connector connector;

    public Handler() {
        this(BootstrapConfiguration.fromEnvironment(System.getenv()));
    }

    private Handler(BootstrapConfiguration config) {
        this(config, new ParameterStoreCredentials(SsmClient.create()));
    }

    Handler(BootstrapConfiguration config, Function<String, DatabaseCredentials> credentials) {
        this(config, credentials, null);
    }

    Handler(BootstrapConfiguration config, Function<String, DatabaseCredentials> credentials, Connector connector) {
        this.config = config;
        this.credentials = credentials;
        this.connector = connector == null ? this::connect : connector;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        String command = event == null ? "bootstrap" : String.valueOf(event.getOrDefault("command", "bootstrap"));
        DatabaseCredentials.require("bootstrap".equals(command) || "verify".equals(command),
                "Unknown command " + command + "; expected bootstrap or verify");
        try {
            DatabaseCredentials master = credentials.apply(config.masterParameter());
            DatabaseCredentials owner = credentials.apply(config.schemaOwnerParameter());
            try (Connection connection = connector.connect(master)) {
                if ("bootstrap".equals(command)) {
                    DatabaseBootstrap.bootstrap(connection, master.username(), owner);
                }
                BootstrapVerifier.Report report = verifier().verify(connection, master, owner.username());
                Map<String, Object> result = JSON.convertValue(report, JSON.getTypeFactory()
                        .constructMapType(Map.class, String.class, Object.class));
                result.put("command", command);
                LOGGER.info("Database ready for Flyway: " + JSON.writeValueAsString(result));
                return result;
            }
        } catch (BootstrapVerifier.VerificationException e) {
            LOGGER.error("Database verification failed: " + e.report());
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // Surfaces as FunctionError, which fails the Terraform apply.
            throw new IllegalStateException("Database " + command + " failed: " + e.getMessage(), e);
        }
    }

    private BootstrapVerifier verifier() {
        return new BootstrapVerifier(config.verifyTls() ? new TlsProtocolProbe(config.timeout()) : null);
    }

    private Connection connect(DatabaseCredentials credentials) throws SQLException {
        Properties urlProperties = org.postgresql.Driver.parseURL(credentials.jdbcUrl(), new Properties());
        DatabaseCredentials.require(urlProperties != null, "Invalid PostgreSQL JDBC URL");
        DatabaseCredentials.require("verify-full".equals(urlProperties.getProperty("sslmode")),
                "Master JDBC connection requires sslmode=verify-full");
        // URL options override connection properties, so validate the driver's parsed options before connecting.
        String sslFactory = urlProperties.getProperty("sslfactory", "org.postgresql.ssl.LibPQFactory");
        DatabaseCredentials.require("org.postgresql.ssl.LibPQFactory".equals(sslFactory)
                        || "org.postgresql.ssl.DefaultJavaSSLFactory".equals(sslFactory),
                "Master JDBC connection requires a standard certificate-validating SSL factory");
        for (String option : new String[] {"sslfactoryarg", "sslhostnameverifier"}) {
            DatabaseCredentials.require(!urlProperties.containsKey(option),
                    "Master JDBC connection must not override " + option);
        }
        Properties properties = new Properties();
        properties.setProperty("user", credentials.username());
        properties.setProperty("password", credentials.password());
        properties.setProperty("connectTimeout", String.valueOf(config.timeout().toSeconds()));
        properties.setProperty("ApplicationName", "address-lookup-db-bootstrap");
        return DriverManager.getConnection(credentials.jdbcUrl(), properties);
    }
}
