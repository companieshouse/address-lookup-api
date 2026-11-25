package uk.gov.companieshouse.release.model;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
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

    private static final List<Table> TABLES = load();

    private static List<Table> load() {
        try (var in = DatasetCatalog.class.getResourceAsStream("/catalog.json")) {
            if (in == null) throw new IllegalStateException("Missing pinned OS catalog");
            var tables = List.of(new ObjectMapper().readValue(in, Table[].class));
            long importableDatasets =
                    Arrays.stream(Dataset.values()).filter(Dataset::supportsDatabaseImport).count();
            if (tables.size() != importableDatasets
                    || tables.stream().map(Table::name).distinct().count() != tables.size())
                throw new IllegalStateException(
                        "Expected one distinct catalogue entry per importable OS dataset");
            for (var table : tables) {
                if (!Dataset.named(table.name()).supportsDatabaseImport())
                    throw new IllegalStateException("No database mapping for " + table.name());
            }
            return tables;
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static List<Table> tables() {
        return TABLES;
    }

    public static List<Dataset> datasets() {
        return TABLES.stream().map(table -> Dataset.named(table.name())).toList();
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
}
