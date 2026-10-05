package uk.gov.companieshouse.addresslookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class OsDataSchemaMigrationIT {

    private static final Logger LOGGER = LoggerFactory.getLogger(OsDataSchemaMigrationIT.class);

    private static final List<String> RENAMED_TABLES = List.of(
            "add_gb_builtaddress_v3",
            "add_isl_builtaddress_v3",
            "add_gb_royalmailaddress_v1",
            "add_isl_royalmailaddress_v1");

    @SuppressWarnings("resource")
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("postgis/postgis:18-3.6")
                            .asCompatibleSubstituteFor("postgres"))
                    .withCreateContainerCmdModifier(
                            command -> command.withPlatform("linux/amd64"));

    @Test
    void shouldReconcileTablesFromPreviouslyRecordedHistoryAndBeIdempotent() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        applyChangelog(dataSource, "classpath:db/changelog/previous-master.yaml");
        applyChangelog(dataSource, "classpath:db/changelog/previous-local-seed.yaml");

        Map<String, Long> rowCountsBeforeMigration = new LinkedHashMap<>();
        Map<String, Long> tableOidsBeforeMigration = new LinkedHashMap<>();
        for (String table : RENAMED_TABLES) {
            Long rowCountBeforeMigration = queryRowCount(
                    jdbcTemplate, table, "before-migration");
            assertTrue(rowCountBeforeMigration > 0, "Expected seed rows in " + table);
            rowCountsBeforeMigration.put(table, rowCountBeforeMigration);
            jdbcTemplate.execute("ALTER TABLE os_data." + table + " SET SCHEMA public");
            tableOidsBeforeMigration.put(table, jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)::oid::bigint",
                    Long.class,
                    "public." + table));
        }

        applyChangelog(dataSource, "classpath:db/changelog/db.changelog-master.yaml");
        long changeSetCountAfterMigration = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM DATABASECHANGELOG", Long.class);
        Map<String, Long> rowCountsAfterMigration = new LinkedHashMap<>();

        for (String table : RENAMED_TABLES) {
            Long tableOidAfterMigration = jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)::oid::bigint",
                    Long.class,
                    "os_data." + table);
            assertEquals(tableOidsBeforeMigration.get(table), tableOidAfterMigration);
            assertEquals(0L, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = 'public' AND table_name = ?",
                    Long.class,
                    table).longValue());
            Long rowCountAfterMigration = queryRowCount(
                    jdbcTemplate, table, "after-migration");
            assertEquals(
                    rowCountsBeforeMigration.get(table),
                    rowCountAfterMigration,
                    "Expected rows in " + table + " to survive schema reconciliation");
            rowCountsAfterMigration.put(table, rowCountAfterMigration);
        }

        applyChangelog(dataSource, "classpath:db/changelog/db.changelog-master.yaml");
        Map<String, Long> rowCountsAfterRerun = new LinkedHashMap<>();
        for (String table : RENAMED_TABLES) {
            Long rowCountAfterRerun = queryRowCount(
                    jdbcTemplate, table, "after-liquibase-rerun");
            assertEquals(
                    rowCountsBeforeMigration.get(table),
                    rowCountAfterRerun,
                    "Expected rows in " + table
                            + " to remain unchanged after rerunning Liquibase");
            rowCountsAfterRerun.put(table, rowCountAfterRerun);
        }
        assertEquals(changeSetCountAfterMigration, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM DATABASECHANGELOG", Long.class).longValue());
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM DATABASECHANGELOG "
                        + "WHERE id IN ('012-ensure-os-data-schema', "
                        + "'013-reconcile-renamed-address-tables')",
                Long.class).longValue());
    }

    private Long queryRowCount(JdbcTemplate jdbcTemplate, String table, String phase) {
        String sql = "SELECT COUNT(*) FROM os_data." + table;
        Long rowCount = jdbcTemplate.queryForObject(sql, Long.class);
        LOGGER.info("Migration row count phase={} sql=[{}] result={}", phase, sql, rowCount);
        return rowCount;
    }

    private void applyChangelog(DriverManagerDataSource dataSource, String changeLog) throws Exception {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(changeLog);
        liquibase.setContexts("local");
        liquibase.afterPropertiesSet();
    }
}
