package uk.gov.companieshouse.addresslookup.dbbootstrap;

import org.postgresql.PGConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A mocked JDBC connection that answers queries by SQL fragment and records executed DDL, so the bootstrap and
 * verifier are unit tested without a database (the pipeline's test-unit has no Docker).
 */
final class FakeDatabase {

    static final String DATABASE = "addressdb";

    final Connection connection = mock(Connection.class);
    final PGConnection pgConnection = mock(PGConnection.class);
    final List<String> executed = new ArrayList<>();
    final List<String> prepared = new ArrayList<>();
    private final Map<String, List<Object[]>> results = new LinkedHashMap<>();

    FakeDatabase() throws SQLException {
        when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenAnswer(call -> executed.add(call.getArgument(0)) && false);
        when(connection.prepareStatement(anyString())).thenAnswer(call -> prepare(call.getArgument(0)));
    }

    /** Rows returned by any query containing {@code fragment}; no rows means an empty result. */
    FakeDatabase returns(String fragment, Object[]... rows) {
        results.put(fragment, List.of(rows));
        return this;
    }

    static Object[] row(Object... values) {
        return values;
    }

    /** A database ready for Flyway: PostGIS installed, a restricted owner, empty, TLS 1.3 only. */
    static FakeDatabase ready() throws SQLException {
        return new FakeDatabase()
                .returns("extname = 'postgis'", row("3.6.0"))
                .returns("r.rolsuper", row(false, true, false, true, true, true))
                .returns("rds.force_ssl", row("1"))
                .returns("ssl_min_protocol_version", row("TLSv1.3"))
                .returns("pg_stat_ssl", row("TLSv1.3"));
    }

    private PreparedStatement prepare(String sql) throws SQLException {
        prepared.add(sql);
        PreparedStatement statement = mock(PreparedStatement.class);
        Map<Integer, String> parameters = new HashMap<>();
        doAnswer(call -> parameters.put(call.getArgument(0), call.getArgument(1)))
                .when(statement).setString(anyInt(), org.mockito.ArgumentMatchers.any());
        when(statement.executeQuery()).thenAnswer(call -> resultSet(sql.startsWith("SELECT format(")
                ? List.<Object[]>of(row(format(sql, parameters)))
                : rows(sql)));
        return statement;
    }

    private List<Object[]> rows(String sql) {
        return results.entrySet().stream()
                .filter(entry -> sql.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(List.of());
    }

    /** Mimics PostgreSQL's format() with %I; parameter 1 is the template. */
    private static String format(String sql, Map<Integer, String> parameters) {
        String formatted = parameters.get(1);
        boolean hasDatabase = sql.contains("current_database()");
        for (int i = 2; formatted.contains("%I"); i++) {
            String value = parameters.get(i);
            if (value == null && hasDatabase && i == 2) {
                value = DATABASE;
            }
            formatted = formatted.replaceFirst("%I", "\"" + value + "\"");
        }
        return formatted;
    }

    private static ResultSet resultSet(List<Object[]> rows) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        AtomicInteger cursor = new AtomicInteger(-1);
        when(rs.next()).thenAnswer(call -> cursor.incrementAndGet() < rows.size());
        when(rs.getString(anyInt())).thenAnswer(call -> {
            Object value = rows.get(cursor.get())[(int) call.getArgument(0) - 1];
            return value == null ? null : value.toString();
        });
        when(rs.getBoolean(anyInt())).thenAnswer(call -> (Boolean) rows.get(cursor.get())[(int) call.getArgument(0) - 1]);
        return rs;
    }
}
