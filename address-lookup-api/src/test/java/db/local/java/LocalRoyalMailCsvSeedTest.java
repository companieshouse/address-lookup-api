package db.local.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;

class LocalRoyalMailCsvSeedTest {

  @AfterEach
  void clearSeedDirectory() {
    System.clearProperty(LocalRoyalMailCsvSeed.SEED_DIR_PROPERTY);
  }

  @Test
  void isRepeatableAndChecksumFollowsRecordedDownloadVersion(@TempDir Path dir) throws Exception {
    System.setProperty(LocalRoyalMailCsvSeed.SEED_DIR_PROPERTY, dir.toString());
    LocalGbRoyalMailCsvSeed seed = new LocalGbRoyalMailCsvSeed();
    assertNull(seed.getVersion());
    assertEquals(0, seed.getChecksum());

    Files.writeString(dir.resolve("add_gb_royalmailaddress.csv"), "udprn\n1\n");
    int unversioned = seed.getChecksum();
    assertNotEquals(0, unversioned);
    Files.writeString(dir.resolve("add_gb_royalmailaddress.csv.version"), "24833/1");
    int first = seed.getChecksum();
    assertEquals(first, seed.getChecksum());

    Files.writeString(dir.resolve("add_gb_royalmailaddress.csv.version"), "24833/2");
    assertNotEquals(first, seed.getChecksum());
  }

  @Test
  void skipsWithoutTouchingTheDatabaseWhenCsvIsMissing(@TempDir Path dir) throws Exception {
    System.setProperty(LocalRoyalMailCsvSeed.SEED_DIR_PROPERTY, dir.toString());
    Context context = mock(Context.class);

    new LocalIslRoyalMailCsvSeed().migrate(context);

    verifyNoInteractions(context);
  }

  @Test
  void rejectsCsvColumnsMissingFromTheTargetTable(@TempDir Path dir) throws Exception {
    System.setProperty(LocalRoyalMailCsvSeed.SEED_DIR_PROPERTY, dir.toString());
    Files.writeString(dir.resolve("add_gb_royalmailaddress.csv"), "udprn,unknown_col\n1,x\n");
    Context context = mock(Context.class);
    Connection connection = loadableConnection(27700, mock(Statement.class));
    when(context.getConnection()).thenReturn(connection);

    LocalGbRoyalMailCsvSeed seed = new LocalGbRoyalMailCsvSeed();
    assertThrows(IllegalStateException.class, () -> seed.migrate(context));
  }

  @Test
  void rejectsUnsafeCsvColumnNames(@TempDir Path dir) throws Exception {
    System.setProperty(LocalRoyalMailCsvSeed.SEED_DIR_PROPERTY, dir.toString());
    Files.writeString(dir.resolve("add_gb_royalmailaddress.csv"), "udprn;drop table x\n1\n");
    Context context = mock(Context.class);
    when(context.getConnection())
        .thenReturn(mock(Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));

    LocalGbRoyalMailCsvSeed seed = new LocalGbRoyalMailCsvSeed();
    assertThrows(IllegalStateException.class, () -> seed.migrate(context));
  }

  @Test
  void describesTheSeedAfterItsDataset() {
    assertEquals(
        "seed add gb royalmailaddress csv", new LocalGbRoyalMailCsvSeed().getDescription());
    assertTrue(new LocalIslRoyalMailCsvSeed().canExecuteInTransaction());
  }

  @Test
  void usesTheConfiguredSeedDirectory(@TempDir Path dir) {
    System.setProperty(LocalRoyalMailCsvSeed.SEED_DIR_PROPERTY, dir.toString());
    assertEquals(dir, LocalRoyalMailCsvSeed.seedDirectory());
  }

  @Test
  void fallsBackToTheLocalDataDirectoryWhenNothingIsConfigured() {
    System.setProperty(LocalRoyalMailCsvSeed.SEED_DIR_PROPERTY, " ");
    String path = LocalRoyalMailCsvSeed.seedDirectory().toString();
    assertTrue(path.endsWith(Path.of("database", "local", "data").toString()), path);
  }

  @Test
  void stagesTheCsvAndInsertsWithTypedColumnsAndGeometry(@TempDir Path dir) throws Exception {
    System.setProperty(LocalRoyalMailCsvSeed.SEED_DIR_PROPERTY, dir.toString());
    Files.writeString(
        dir.resolve("add_isl_royalmailaddress.csv"), "udprn,geometry\n1,POINT(1 2)\n");
    Context context = mock(Context.class);
    Statement statement = mock(Statement.class);
    Connection connection = loadableConnection(4258, statement);
    when(context.getConnection()).thenReturn(connection);
    CopyManager copyManager = connection.unwrap(PGConnection.class).getCopyAPI();
    when(copyManager.copyIn(anyString(), any(java.io.Reader.class))).thenReturn(1L);

    new LocalIslRoyalMailCsvSeed().migrate(context);

    verify(statement)
        .execute("DELETE FROM os_data.add_isl_royalmailaddress_v2 WHERE release_id = 'local'");
    verify(copyManager).copyIn(contains("FORCE_NULL (udprn, geometry)"), any(java.io.Reader.class));
    verify(statement).executeUpdate(contains("ST_GeomFromText(s.geometry, 4258)"));
    verify(statement).executeUpdate(contains("s.udprn::integer"));
    verify(statement).executeUpdate(contains("ON CONFLICT (udprn) DO NOTHING"));
  }

  @Test
  void failsWhenTheGeometryColumnHasNoSrid(@TempDir Path dir) throws Exception {
    System.setProperty(LocalRoyalMailCsvSeed.SEED_DIR_PROPERTY, dir.toString());
    Files.writeString(dir.resolve("add_gb_royalmailaddress.csv"), "udprn,geometry\n1,POINT(1 2)\n");

    assertThrows(IllegalStateException.class, this::seedWithMocks);
  }

  private Statement seedWithMocks() throws Exception {
    Context context = mock(Context.class);
    Statement statement = mock(Statement.class);
    Connection connection = loadableConnection(null, statement);
    when(context.getConnection()).thenReturn(connection);
    new LocalGbRoyalMailCsvSeed().migrate(context);
    return statement;
  }

  @Test
  void failsWhenAColumnTypeCannotBeResolved(@TempDir Path dir) throws Exception {
    System.setProperty(LocalRoyalMailCsvSeed.SEED_DIR_PROPERTY, dir.toString());
    Files.writeString(dir.resolve("add_gb_royalmailaddress.csv"), "udprn\n1\n");
    Context context = mock(Context.class);
    Connection connection = mock(Connection.class);
    PGConnection pgConnection = mock(PGConnection.class);
    CopyManager copyManager = mock(CopyManager.class);
    Statement statement = mock(Statement.class);
    ResultSet noColumns = mock(ResultSet.class);
    ResultSet failing = mock(ResultSet.class);
    when(context.getConnection()).thenReturn(connection);
    when(connection.createStatement()).thenReturn(statement);
    when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
    when(pgConnection.getCopyAPI()).thenReturn(copyManager);
    when(noColumns.next()).thenReturn(true, false);
    when(noColumns.getString(1)).thenReturn("udprn");
    when(failing.next()).thenThrow(new SQLException("boom"));
    PreparedStatement sridQuery = mock(PreparedStatement.class);
    ResultSet srid = mock(ResultSet.class);
    when(srid.next()).thenReturn(true);
    when(srid.getInt(1)).thenReturn(27700);
    when(sridQuery.executeQuery()).thenReturn(srid);
    when(connection.prepareStatement(anyString()))
        .thenAnswer(invocation -> preparedFor(invocation.getArgument(0), sridQuery));
    when(connection.createArrayOf(anyString(), any(Object[].class)))
        .thenAnswer(invocation -> {
          java.sql.Array array = mock(java.sql.Array.class);
          when(array.getArray()).thenReturn(invocation.getArgument(1));
          return array;
        });
    when(statement.executeQuery(anyString())).thenReturn(noColumns, failing);

    LocalGbRoyalMailCsvSeed seed = new LocalGbRoyalMailCsvSeed();
    assertThrows(IllegalStateException.class, () -> seed.migrate(context));
  }

  @Test
  void rejectsAnEmptyCsv(@TempDir Path dir) throws Exception {
    System.setProperty(LocalRoyalMailCsvSeed.SEED_DIR_PROPERTY, dir.toString());
    Files.writeString(dir.resolve("add_gb_royalmailaddress.csv"), "");
    Context context = mock(Context.class);
    when(context.getConnection())
        .thenReturn(mock(Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));

    LocalGbRoyalMailCsvSeed seed = new LocalGbRoyalMailCsvSeed();
    assertThrows(IllegalStateException.class, () -> seed.migrate(context));
  }

  @Test
  void progressStreamPassesDataThroughAndHandlesEmptyInput() throws Exception {
    byte[] data = new byte[2048];
    try (var in =
        new LocalRoyalMailCsvSeed.ProgressInputStream(
            new ByteArrayInputStream(data), data.length, "t")) {
      assertEquals(0, in.read());
      assertEquals(1024, in.read(new byte[1024], 0, 1024));
      assertEquals(1023, in.readAllBytes().length);
      assertEquals(-1, in.read());
      assertEquals(-1, in.read(new byte[8], 0, 8));
    }
    try (var empty =
        new LocalRoyalMailCsvSeed.ProgressInputStream(
            new ByteArrayInputStream(new byte[0]), 0, "t")) {
      assertEquals(-1, empty.read());
    }
  }

  @Test
  void loadsTheSiblingInParallelAndSkipsItsOwnMigration(@TempDir Path dir) throws Exception {
    writeBothCsvs(dir);
    Statement gbStatement = mock(Statement.class);
    Statement islStatement = mock(Statement.class);
    Connection islConnection = loadableConnection(4258, islStatement);
    Context context = contextWithSiblingConnection(gbStatement, 27700, islConnection);

    new LocalGbRoyalMailCsvSeed().migrate(context);
    new LocalIslRoyalMailCsvSeed().migrate(context);

    verify(gbStatement)
        .execute("DELETE FROM os_data.add_gb_royalmailaddress_v2 WHERE release_id = 'local'");
    verify(islStatement)
        .execute("DELETE FROM os_data.add_isl_royalmailaddress_v2 WHERE release_id = 'local'");
    verify(islConnection).setAutoCommit(false);
    verify(islConnection).commit();
    verify(islStatement).executeUpdate(contains("ST_GeomFromText(s.geometry, 4258)"));
  }

  @Test
  void reloadsTheSiblingWhenItsMarkerIsStale(@TempDir Path dir) throws Exception {
    writeBothCsvs(dir);
    Statement islStatement = mock(Statement.class);
    Connection islConnection = loadableConnection(4258, mock(Statement.class));
    Context context = contextWithSiblingConnection(mock(Statement.class), 27700, islConnection);
    new LocalGbRoyalMailCsvSeed().migrate(context);

    Files.writeString(dir.resolve("add_isl_royalmailaddress.csv.version"), "changed/2");
    Connection islOwn = loadableConnection(4258, islStatement);
    when(context.getConnection()).thenReturn(islOwn);
    new LocalIslRoyalMailCsvSeed().migrate(context);

    verify(islStatement).executeUpdate(contains("ST_GeomFromText(s.geometry, 4258)"));
  }

  @Test
  void rollsBackAndFailsWhenTheParallelSiblingFails(@TempDir Path dir) throws Exception {
    writeBothCsvs(dir);
    Statement islStatement = mock(Statement.class);
    Connection islConnection = loadableConnection(4258, islStatement);
    when(islStatement.executeUpdate(anyString())).thenThrow(new IllegalStateException("boom"));
    Context context = contextWithSiblingConnection(mock(Statement.class), 27700, islConnection);

    LocalGbRoyalMailCsvSeed seed = new LocalGbRoyalMailCsvSeed();
    assertThrows(IllegalStateException.class, () -> seed.migrate(context));

    verify(islConnection).rollback();
    verify(islConnection, never()).commit();
  }

  @Test
  void wrapsDatabaseFailuresOfTheParallelSibling(@TempDir Path dir) throws Exception {
    writeBothCsvs(dir);
    Context context = mock(Context.class);
    Configuration configuration = mock(Configuration.class);
    DataSource dataSource = mock(DataSource.class);
    when(context.getConfiguration()).thenReturn(configuration);
    when(configuration.getDataSource()).thenReturn(dataSource);
    when(dataSource.getConnection()).thenThrow(new SQLException("down"));
    Connection connection = loadableConnection(27700, mock(Statement.class));
    when(context.getConnection()).thenReturn(connection);

    LocalGbRoyalMailCsvSeed seed = new LocalGbRoyalMailCsvSeed();
    assertThrows(IllegalStateException.class, () -> seed.migrate(context));
  }

  private static void writeBothCsvs(Path dir) throws java.io.IOException {
    System.setProperty(LocalRoyalMailCsvSeed.SEED_DIR_PROPERTY, dir.toString());
    for (String dataset : new String[] {"add_gb_royalmailaddress", "add_isl_royalmailaddress"}) {
      Files.writeString(dir.resolve(dataset + ".csv"), "udprn,geometry\n1,POINT(1 2)\n");
    }
  }

  private static Context contextWithSiblingConnection(
      Statement statement, int srid, Connection siblingConnection) throws Exception {
    Context context = mock(Context.class);
    Configuration configuration = mock(Configuration.class);
    DataSource dataSource = mock(DataSource.class);
    when(context.getConfiguration()).thenReturn(configuration);
    when(configuration.getDataSource()).thenReturn(dataSource);
    when(dataSource.getConnection()).thenReturn(siblingConnection);
    Connection connection = loadableConnection(srid, statement);
    when(context.getConnection()).thenReturn(connection);
    return context;
  }

  /** A connection whose SRID lookup returns the given SRID, or no row when it is null. */
  private static Connection loadableConnection(Integer srid, Statement statement) throws Exception {
    Connection connection = mock(Connection.class);
    PGConnection pgConnection = mock(PGConnection.class);
    CopyManager copyManager = mock(CopyManager.class);
    PreparedStatement sridQuery = mock(PreparedStatement.class);
    ResultSet sridRow = mock(ResultSet.class);
    boolean hasSrid = srid != null;
    int sridValue = hasSrid ? srid : 0;
    when(sridRow.next()).thenReturn(hasSrid);
    when(sridRow.getInt(1)).thenReturn(sridValue);
    when(sridQuery.executeQuery()).thenReturn(sridRow);
    when(connection.prepareStatement(anyString()))
        .thenAnswer(invocation -> preparedFor(invocation.getArgument(0), sridQuery));
    when(connection.createArrayOf(anyString(), any(Object[].class)))
        .thenAnswer(invocation -> {
          java.sql.Array array = mock(java.sql.Array.class);
          when(array.getArray()).thenReturn(invocation.getArgument(1));
          return array;
        });
    when(connection.createStatement()).thenReturn(statement);
    when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
    when(pgConnection.getCopyAPI()).thenReturn(copyManager);
    when(statement.executeQuery(anyString())).thenAnswer(invocation -> columnRows());
    when(statement.executeUpdate(anyString())).thenReturn(1);
    return connection;
  }

  /** Serves the SRID query, or emulates PostgreSQL format() with %I, %L and %s. */
  private static PreparedStatement preparedFor(String sql, PreparedStatement sridQuery)
      throws SQLException {
    if (sql.contains("geometry_columns")) {
      return sridQuery;
    }
    PreparedStatement formatter = mock(PreparedStatement.class);
    String[] template = new String[1];
    Object[][] args = new Object[1][];
    doAnswer(
            invocation -> {
              template[0] = invocation.getArgument(1);
              return null;
            })
        .when(formatter)
        .setString(eq(1), anyString());
    doAnswer(
            invocation -> {
              args[0] = (Object[]) ((java.sql.Array) invocation.getArgument(1)).getArray();
              return null;
            })
        .when(formatter)
        .setArray(eq(2), any(java.sql.Array.class));
    when(formatter.executeQuery())
        .thenAnswer(invocation -> {
          ResultSet rs = mock(ResultSet.class);
          when(rs.getString(1)).thenReturn(applyFormat(template[0], args[0]));
          return rs;
        });
    return formatter;
  }

  private static String applyFormat(String template, Object[] args) {
    StringBuilder out = new StringBuilder();
    int next = 0;
    for (int i = 0; i < template.length(); i++) {
      char c = template.charAt(i);
      if (c != '%') {
        out.append(c);
        continue;
      }
      char kind = template.charAt(++i);
      String value = String.valueOf(args[next++]);
      out.append(kind == 'L' ? "'" + value + "'" : value);
    }
    return out.toString();
  }

  private static ResultSet columnRows() throws SQLException {
    ResultSet rows = mock(ResultSet.class);
    when(rows.next()).thenReturn(true, true, false);
    when(rows.getString(1)).thenReturn("udprn", "geometry");
    when(rows.getString(2)).thenReturn("integer", "geometry(Point,4258)");
    return rows;
  }
}
