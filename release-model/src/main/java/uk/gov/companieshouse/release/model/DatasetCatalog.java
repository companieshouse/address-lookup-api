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
            return columns.stream()
                    .map(Column::name)
                    .collect(java.util.stream.Collectors.joining(","));
        }

        public String data() {
            return "os_data." + physical;
        }
    }

    public static String quote(String name) {
        if (!name.matches("[a-z][a-z0-9_]*"))
            throw new IllegalArgumentException("Invalid identifier");
        return "\"" + name + "\"";
    }

    private static final List<Table> SCHEMAS = load();
    private static final List<Table> TABLES =
            SCHEMAS.stream()
                    .filter(table -> Dataset.named(table.name()).supportsDatabaseImport())
                    .toList();

    private static List<Table> load() {
        try (var in = DatasetCatalog.class.getResourceAsStream("/catalog.json")) {
            if (in == null) throw new IllegalStateException("Missing pinned OS catalog");
            var tables = List.of(new ObjectMapper().readValue(in, Table[].class));
            // Every dataset needs an entry: acquisition validates each CSV header against it.
            if (tables.size() != Dataset.values().length
                    || tables.stream().map(Table::name).distinct().count() != tables.size())
                throw new IllegalStateException(
                        "Expected one distinct catalogue entry per OS dataset");
            for (var table : tables) {
                var dataset = Dataset.named(table.name());
                if (table.columns() == null || table.columns().isEmpty()
                        || table.columns().stream().map(Column::name).distinct().count()
                                != table.columns().size()
                        || table.version() == null)
                    throw new IllegalStateException("Invalid pinned schema for " + table.name());
                if (dataset.supportsDatabaseImport()
                        ? table.physical() == null || table.key() == null
                        : table.physical() != null || table.key() != null)
                    throw new IllegalStateException("Invalid import mapping for " + table.name());
            }
            return tables;
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static List<Table> tables() {
        return TABLES;
    }

    /** The datasets the import Lambda loads; the FULL procedures use os_control.schema_contract. */
    public static List<Dataset> datasets() {
        return TABLES.stream()
                .map(table -> Dataset.named(table.name()))
                .filter(Dataset::supportsDatabaseImport)
                .toList();
    }

    public static Table table(Dataset dataset) {
        return DatasetCatalog.tables().stream()
                .filter(t -> t.name().equals(dataset.datasetName()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "No pinned import schema for " + dataset.datasetName()));
    }

    public static String extractionHeader(String name) {
        return schema(name).header();
    }

    static Table schema(String name) {
        return SCHEMAS.stream()
                .filter(schema -> schema.name().equals(name))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("No pinned extraction schema for " + name));
    }
}
