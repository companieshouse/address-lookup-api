package uk.gov.companieshouse.addresslookup.lamda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.ssm.SsmClient;
import uk.gov.companieshouse.addresslookup.lamda.schema.ChangelogArchive;
import uk.gov.companieshouse.addresslookup.lamda.schema.MigrationRequest;
import uk.gov.companieshouse.addresslookup.lamda.schema.MigrationResult;
import uk.gov.companieshouse.addresslookup.lamda.schema.MigratorConfiguration;
import uk.gov.companieshouse.addresslookup.lamda.schema.ParameterStoreCredentials;
import uk.gov.companieshouse.addresslookup.lamda.schema.SchemaMigrator;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Applies a released db-schema changelog to Aurora. Invoked synchronously by Concourse only; it has no event
 * sources, and Terraform grants lambda:InvokeFunction to nobody else.
 *
 * <pre>{"mode": "VALIDATE|UPDATE|STATUS|RELEASE_LOCKS", "version": "1.0.3", "sha256": "..."}</pre>
 */
public final class Handler implements RequestHandler<Map<String, Object>, String> {

    private static final Logger LOG = LoggerFactory.getLogger("address-lookup-schema-migrator");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChangelogArchive archive;
    private final ParameterStoreCredentials credentials;
    private final SchemaMigrator migrator;

    public Handler() {
        this(configuration(), S3Client.create(), SsmClient.create());
    }

    Handler(MigratorConfiguration config, S3Client s3, SsmClient ssm) {
        this(new ChangelogArchive(s3, config),
                new ParameterStoreCredentials(ssm, config.usernameParameter(), config.passwordParameter()),
                new SchemaMigrator(config));
    }

    Handler(ChangelogArchive archive, ParameterStoreCredentials credentials, SchemaMigrator migrator) {
        this.archive = archive;
        this.credentials = credentials;
        this.migrator = migrator;
    }

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        Map<String, Object> log = new HashMap<>();
        log.put("request_id", context.getAwsRequestId());
        Path work = null;
        try {
            MigrationRequest request = MigrationRequest.from(event);
            log.put("mode", request.mode().name());
            log.put("version", request.version());
            LOG.info("Schema migration requested", log);

            // /tmp survives between invocations of a warm environment, so each one gets a fresh directory.
            work = Files.createTempDirectory("db-schema-");
            Path changelogs = archive.fetch(request, work);
            MigrationResult result = migrator.migrate(request, changelogs, credentials.load(),
                    context::getRemainingTimeInMillis);

            log.put("status", result.status().name());
            log.put("applied", result.appliedChangeSets());
            log.put("pending", result.pendingChangeSets());
            log.put("tag", String.valueOf(result.tag()));
            log.put("postgis_version", String.valueOf(result.postgisVersion()));
            LOG.info("Schema migration finished", log);
            return JSON.writeValueAsString(result);
        } catch (Exception e) {
            LOG.error("Schema migration failed", e, log);
            // Surfaces as FunctionError, which fails the pipeline task.
            throw new IllegalStateException("Schema migration failed: " + e.getMessage(), e);
        } finally {
            delete(work);
        }
    }

    private static MigratorConfiguration configuration() {
        try {
            return MigratorConfiguration.fromEnvironment(System.getenv());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            LOG.error("Could not remove " + directory, e);
        }
    }
}
