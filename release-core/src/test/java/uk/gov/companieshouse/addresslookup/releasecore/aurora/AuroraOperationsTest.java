package uk.gov.companieshouse.addresslookup.releasecore.aurora;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.SupplyType;

final class AuroraOperationsTest {
  private static final Dataset DATASET = Dataset.ADD_GB_ROYALMAILADDRESS;

  private final UUID id = UUID.randomUUID();
  private final List<String> statements = new ArrayList<>();
  private final PGConnection pgConnection = mock(PGConnection.class);
  private final CopyManager copyManager = mock(CopyManager.class);
  private long rowCount;
  private AuroraOperations operations;

  @BeforeEach
  void setUp() throws SQLException {
    when(pgConnection.getCopyAPI()).thenReturn(copyManager);
    var dataSource =
        proxy(
            DataSource.class,
            (method, args) ->
                "getConnection".equals(method) ? proxy(Connection.class, this::connection) : null);
    operations = new AuroraOperations(new JdbcTemplate(dataSource));
  }

  private Object connection(String method, Object[] args) {
    return switch (method) {
      case "prepareStatement" -> statement((String) args[0]);
      case "createStatement" -> statement(null);
      case "unwrap" -> pgConnection;
      default -> null;
    };
  }

  private Object statement(String preparedSql) {
    return proxy(
        PreparedStatement.class,
        (method, args) -> {
          var sql = preparedSql != null ? preparedSql : args.length > 0 ? (String) args[0] : null;
          if ("executeUpdate".equals(method)) {
            statements.add(sql);
            return 1;
          }
          if (!"executeQuery".equals(method)) return null;
          statements.add(sql);
          return singleRow(sql.contains("count(*)") ? rowCount : "ok");
        });
  }

  private ResultSet singleRow(Object value) {
    var remaining = new boolean[] {true};
    var metaData =
        proxy(
            ResultSetMetaData.class, (method, args) -> "getColumnCount".equals(method) ? 1 : null);
    return proxy(
        ResultSet.class,
        (method, args) ->
            switch (method) {
              case "next" -> {
                boolean has = remaining[0];
                remaining[0] = false;
                yield has;
              }
              case "getMetaData" -> metaData;
              case "getLong" -> ((Number) value).longValue();
              case "getObject", "getString" -> value;
              default -> null;
            });
  }

  private interface Handler {
    Object handle(String method, Object[] args);
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, Handler handler) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              var result = handler.handle(method.getName(), args == null ? new Object[0] : args);
              if (result != null || !method.getReturnType().isPrimitive()) return result;
              return method.getReturnType() == boolean.class ? Boolean.FALSE : (Object) 0;
            });
  }

  @Test
  void importS3AcceptsMatchingCount() {
    rowCount = 5;

    assertDoesNotThrow(() -> operations.importS3(DATASET, SupplyType.COU, id, "b", "k", "r", 5));

    assertTrue(statements.stream().anyMatch(s -> s.contains("table_import_from_s3")));
    assertTrue(
        statements.stream().anyMatch(s -> s.startsWith("SELECT count(*) FROM os_stage_raw")));
  }

  @Test
  void importS3RejectsCountMismatch() {
    rowCount = 4;

    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> operations.importS3(DATASET, SupplyType.COU, id, "b", "k", "r", 5));

    assertEquals("OS recordCount mismatch: add_gb_royalmailaddress", ex.getMessage());
  }

  @Test
  void importS3RejectsNegativeExpectedCount() {
    rowCount = 0;

    assertThrows(
        IllegalArgumentException.class,
        () -> operations.importS3(DATASET, SupplyType.FULL, id, "b", "k", "r", -1));
  }

  @Test
  void prepareAndPromoteCallStoredProcedures() {
    var target = LocalDate.of(2026, 1, 1);

    operations.prepare(DATASET, SupplyType.COU, id, target);
    operations.promote(SupplyType.COU, id);
    operations.promote(SupplyType.FULL, id);

    assertEquals(
        List.of(
            "CALL os_ingest.resolve_add_gb_royalmailaddress(?,?)",
            "CALL os_ingest.promote_cou(?)",
            "CALL os_ingest.promote_full(?)"),
        statements.subList(statements.size() - 3, statements.size()));
  }

  @Test
  void requireEmptyLiveTablesAcceptsEmptyTables() {
    rowCount = 0;

    assertDoesNotThrow(operations::requireEmptyLiveTables);
  }

  @Test
  void requireEmptyLiveTablesRejectsPopulatedTables() {
    rowCount = 3;

    assertThrows(IllegalArgumentException.class, operations::requireEmptyLiveTables);
  }

  @Test
  void importLocalCopiesCsvAndVerifiesCount() throws Exception {
    rowCount = 2;
    var csv = new ByteArrayInputStream("a,b".getBytes(StandardCharsets.UTF_8));
    when(copyManager.copyIn(anyString(), eq(csv))).thenReturn(2L);

    assertDoesNotThrow(() -> operations.importLocal(DATASET, SupplyType.FULL, id, csv, 2));

    verify(copyManager).copyIn(startsWith("COPY os_stage_full.add_gb_royalmailaddress ("), eq(csv));
  }

  @Test
  void importLocalReportsCopyFailure() throws Exception {
    var csv = new ByteArrayInputStream(new byte[0]);
    when(copyManager.copyIn(anyString(), eq(csv))).thenThrow(new IOException("disk"));

    assertThrows(
        UncategorizedSQLException.class,
        () -> operations.importLocal(DATASET, SupplyType.FULL, id, csv, 0));
  }
}
