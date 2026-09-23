package uk.gov.companieshouse.release.model;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public final class DatasetCatalog {
  public record Column(String name, String type, boolean required) {}

  public record Table(
      String name, String version, String physical, String key, List<Column> columns) {
    public String columnList() {
      return columns.stream()
          .map(c -> quote(c.name()))
          .collect(java.util.stream.Collectors.joining(","));
    }

    public String header() {
      return columns.stream().map(Column::name).collect(java.util.stream.Collectors.joining(","));
    }

    public String data() {
      return "os_data." + physical;
    }
  }

  public static String quote(String name) {
    if (!name.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid identifier");
    return "\"" + name + "\"";
  }

  private static final List<Table> TABLES = load();

  private static List<Table> load() {
    try (var in = DatasetCatalog.class.getResourceAsStream("/catalog.json")) {
      if (in == null) throw new IllegalStateException("Missing pinned OS catalog");
      var tables = List.of(new ObjectMapper().readValue(in, Table[].class));
      if (tables.size() != Dataset.values().length
          || tables.stream().map(Table::name).distinct().count() != tables.size())
        throw new IllegalStateException("Expected four distinct OS datasets");
      for (var table : tables) Dataset.named(table.name());
      return tables;
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  public static List<Table> tables() {
    return TABLES;
  }
}
