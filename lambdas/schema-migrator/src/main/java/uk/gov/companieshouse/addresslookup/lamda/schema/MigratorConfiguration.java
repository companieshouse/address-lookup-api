package uk.gov.companieshouse.addresslookup.lamda.schema;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import static uk.gov.companieshouse.addresslookup.lamda.schema.MigrationRequest.require;

/**
 * Everything the function is configured with. All of it comes from Terraform-managed environment variables; the
 * invocation payload cannot influence where changelogs are read from or which database is changed.
 */
public record MigratorConfiguration(
        String changelogBucket,
        String changelogKeyPrefix,
        String jdbcUrl,
        String usernameParameter,
        String passwordParameter,
        String contexts,
        String labels,
        Map<String, String> changeLogParameters,
        long minRemainingMillis,
        long statementTimeoutMillis,
        ArchiveLimits archiveLimits) {

    public static final String ARTEFACT_NAME = "address-lookup-db-schema";
    public static final String MASTER_CHANGELOG = "db/changelog/db.changelog-master.yaml";

    private static final String RDS_CA_RESOURCE = "/rds/eu-west-2-bundle.pem";

    public record ArchiveLimits(long maxArchiveBytes, long maxExtractedBytes, int maxEntries) {
        public static final ArchiveLimits DEFAULT = new ArchiveLimits(10L << 20, 50L << 20, 1_000);
    }

    public MigratorConfiguration {
        require(notBlank(changelogBucket), "Missing CHANGELOG_BUCKET");
        require(notBlank(changelogKeyPrefix), "Missing CHANGELOG_KEY_PREFIX");
        require(notBlank(jdbcUrl), "Missing database location");
        require(notBlank(usernameParameter), "Missing DB_USERNAME_PARAMETER");
        require(notBlank(passwordParameter), "Missing DB_PASSWORD_PARAMETER");
        require(minRemainingMillis > 0, "MIN_REMAINING_MILLIS must be positive");
        require(statementTimeoutMillis > 0, "STATEMENT_TIMEOUT_MILLIS must be positive");
        changeLogParameters = Map.copyOf(changeLogParameters);
    }

    public String changelogKey(String version) {
        return changelogKeyPrefix + "/" + ARTEFACT_NAME + "-" + version + ".zip";
    }

    /**
     * Builds the configuration inside Lambda. The connection always verifies the server certificate against the
     * RDS regional CA bundle packaged in this jar, matching the cluster's rds.force_ssl=1.
     */
    public static MigratorConfiguration fromEnvironment(Map<String, String> env) throws IOException {
        Path caBundle = installRdsCaBundle(Path.of(System.getProperty("java.io.tmpdir")));
        String jdbcUrl = "jdbc:postgresql://%s:%s/%s?sslmode=verify-full&sslrootcert=%s".formatted( // trufflehog:ignore
                required(env, "DB_HOST"), env.getOrDefault("DB_PORT", "5432"), required(env, "DB_NAME"), caBundle);

        return new MigratorConfiguration(
                required(env, "CHANGELOG_BUCKET"),
                env.getOrDefault("CHANGELOG_KEY_PREFIX", "address-lookup-api"),
                jdbcUrl,
                required(env, "DB_USERNAME_PARAMETER"),
                required(env, "DB_PASSWORD_PARAMETER"),
                env.getOrDefault("LIQUIBASE_CONTEXTS", "aws"),
                env.getOrDefault("LIQUIBASE_LABELS", ""),
                Map.of(),
                Long.parseLong(env.getOrDefault("MIN_REMAINING_MILLIS", "120000")),
                Long.parseLong(env.getOrDefault("STATEMENT_TIMEOUT_MILLIS", "780000")),
                ArchiveLimits.DEFAULT);
    }

    static Path installRdsCaBundle(Path directory) throws IOException {
        Path target = directory.resolve("rds-eu-west-2-bundle.pem");
        try (InputStream bundle = MigratorConfiguration.class.getResourceAsStream(RDS_CA_RESOURCE)) {
            require(bundle != null, "RDS CA bundle missing from the deployment package");
            Files.copy(bundle, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        require(notBlank(value), "Missing " + name);
        return value;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
