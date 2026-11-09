package uk.gov.companieshouse.addresslookup.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.apache.commons.csv.CSVFormat;

/** Offline fixture extraction and explicit, authenticated FULL-source acquisition. */
public final class AddressSeeds {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String API = "https://api.os.uk/downloads/v1/dataPackages";
  private static final List<String> DATASETS =
      List.of("add_gb_royalmailaddress", "add_isl_royalmailaddress");
  private static final Pattern POSTCODE = Pattern.compile("[A-Z]{1,2}[0-9][A-Z0-9]?[0-9][A-Z]{2}");
  private static final Pattern POINT =
      Pattern.compile("POINT\\(-?[0-9]+(?:\\.[0-9]+)? -?[0-9]+(?:\\.[0-9]+)?\\)");
  private static final Pattern SQL_POSTCODE =
      Pattern.compile("'([A-Z]{1,2}[0-9][A-Z0-9]? [0-9][A-Z]{2})'");

  private AddressSeeds() {}

  /** Runs authenticated download or offline add/refresh without changing a database. */
  public static void main(String[] args) throws Exception {
    try {
      if (args.length == 0)
        throw new IllegalArgumentException("Expected download, add or refresh.");
      if (args[0].equals("download") && args.length == 2) {
        System.out.println(download(Path.of(args[1])));
      } else if ((args[0].equals("add") || args[0].equals("refresh")) && args.length >= 4) {
        generate(
            args[0],
            Path.of(args[1]),
            Path.of(args[2]),
            Path.of(args[3]),
            List.of(args).subList(4, args.length));
      } else {
        throw new IllegalArgumentException("Invalid seed tool arguments.");
      }
    } catch (IllegalArgumentException | IOException error) {
      System.err.println("OS seed error: " + error.getMessage());
      System.exit(1);
    }
  }

  static URI safeUri(String value) {
    URI uri = URI.create(value);
    if (!"https".equals(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getFragment() != null
        || (uri.getPort() != -1 && uri.getPort() != 443)) {
      throw new IllegalArgumentException("Download links must be HTTPS without credentials.");
    }
    return uri;
  }

  static boolean osHost(URI uri) {
    return "api.os.uk".equals(uri.getHost());
  }

  private static InputStream fetch(String url, String key, long limit)
      throws IOException, InterruptedException {
    URI uri = safeUri(url);
    if (!osHost(uri))
      throw new IllegalArgumentException("Initial download URL must use api.os.uk.");
    Path directory = Files.createTempDirectory("os-download-");
    Path body = directory.resolve("body");
    Path headers = directory.resolve("headers");
    boolean returned = false;
    try {
      for (int attempt = 0; attempt < 6; attempt++) {
        var command =
            new ArrayList<>(
                List.of(
                    "curl",
                    "--silent",
                    "--show-error",
                    "--fail",
                    "--proto",
                    "=https",
                    "--connect-timeout",
                    "15",
                    "--max-time",
                    "1800",
                    "--max-filesize",
                    Long.toString(limit),
                    "--output",
                    body.toString(),
                    "--dump-header",
                    headers.toString(),
                    "--write-out",
                    "%{http_code}",
                    "--header",
                    "@-",
                    uri.toString()));
        Process process =
            new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var input = process.getOutputStream()) {
          if (osHost(uri)) input.write(("key: " + key + "\n").getBytes(StandardCharsets.UTF_8));
        }
        String status =
            new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        if (process.waitFor() != 0)
          throw new IOException("OS download failed (HTTP " + status + ").");
        if (Files.size(body) > limit) throw new IOException("Download exceeds advertised size.");
        if (status.equals("200")) {
          var result =
              new FilterInputStream(Files.newInputStream(body)) {
                @Override
                public void close() throws IOException {
                  try {
                    super.close();
                  } finally {
                    Files.deleteIfExists(body);
                    Files.deleteIfExists(headers);
                    Files.deleteIfExists(directory);
                  }
                }
              };
          returned = true;
          return result;
        }
        if (!Set.of("301", "302", "303", "307", "308").contains(status)) {
          throw new IOException("OS download HTTP " + status);
        }
        String location =
            Files.readAllLines(headers).stream()
                .filter(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith("location:"))
                .reduce((first, last) -> last)
                .map(line -> line.substring(9).trim())
                .orElseThrow(() -> new IOException("Missing download redirect."));
        uri = safeUri(uri.resolve(location).toString());
      }
      throw new IOException("Too many download redirects.");
    } finally {
      if (!returned) {
        Files.deleteIfExists(body);
        Files.deleteIfExists(headers);
        Files.deleteIfExists(directory);
      }
    }
  }

  private static JsonNode metadata(String url, String key) throws Exception {
    try (var stream = fetch(url, key, 8 * 1024 * 1024)) {
      byte[] bytes = stream.readNBytes(8 * 1024 * 1024 + 1);
      if (bytes.length > 8 * 1024 * 1024) throw new IOException("OS metadata exceeds size limit.");
      return JSON.readTree(bytes);
    }
  }

  static JsonNode fullVersion(JsonNode packages, String packageId, String versionId) {
    if (!packages.isArray()) throw new IllegalArgumentException("Expected OS package array.");
    List<JsonNode> candidates = new ArrayList<>();
    for (var item : packages) {
      if (!packageId.equals(item.path("id").asText())) continue;
      for (var version : item.path("versions")) {
        if ("FULL".equalsIgnoreCase(version.path("supplyType").asText())
            && (versionId.isEmpty() || versionId.equals(version.path("id").asText()))) {
          candidates.add(version);
        }
      }
    }
    return candidates.stream()
        .max(Comparator.comparing(v -> v.path("createdOn").asText()))
        .orElseThrow(
            () -> new IllegalArgumentException("No matching FULL version for OS package."));
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String digest(Path file, String algorithm) throws Exception {
    var hash = MessageDigest.getInstance(algorithm);
    try (var stream = new DigestInputStream(Files.newInputStream(file), hash)) {
      stream.transferTo(java.io.OutputStream.nullOutputStream());
    }
    return HexFormat.of().formatHex(hash.digest());
  }

  private static Path download(Path cache) throws Exception {
    String key = env("OS_API_KEY", "");
    if (key.isEmpty())
      throw new IllegalArgumentException("OS_API_KEY is required for downloading.");
    if (key.contains("\n") || key.contains("\r")) {
      throw new IllegalArgumentException("OS_API_KEY must not contain line breaks.");
    }
    String packageId = env("OS_PACKAGE_ID", "24833");
    String versionId = env("OS_FULL_VERSION", "");
    if (!packageId.matches("[0-9]+") || !versionId.matches("[0-9]*")) {
      throw new IllegalArgumentException("OS package/version identifiers must be numeric.");
    }
    var selected = fullVersion(metadata(API, key), packageId, versionId);
    var version = metadata(selected.path("url").asText(), key);
    if (!"FULL".equalsIgnoreCase(version.path("supplyType").asText())
        || !selected.path("id").asText().equals(version.path("id").asText())) {
      throw new IllegalArgumentException("OS version identity or FULL supply type mismatch.");
    }
    String id = version.path("id").asText();
    if (!id.matches("[0-9]+")) throw new IllegalArgumentException("Invalid OS version identifier.");
    Path directory = cache.resolve(packageId).resolve(id).toAbsolutePath();
    Files.createDirectories(directory);
    ObjectNode provenance = JSON.createObjectNode();
    provenance
        .put("packageId", packageId)
        .put("versionId", id)
        .put("supplyType", "FULL")
        .put("createdOn", version.path("createdOn").asText());
    var files = provenance.putObject("files");
    for (String dataset : DATASETS) {
      var matches = new ArrayList<JsonNode>();
      for (var item : version.path("downloads")) {
        if ((dataset + ".zip").equals(item.path("fileName").asText())) matches.add(item);
      }
      if (matches.size() != 1)
        throw new IllegalArgumentException("Expected one " + dataset + ".zip");
      var item = matches.getFirst();
      String md5 = item.path("md5").asText();
      long size = item.path("size").asLong(-1);
      if (!md5.matches("[0-9a-fA-F]{32}") || size <= 0) {
        throw new IllegalArgumentException("Missing archive integrity metadata.");
      }
      Path zip = directory.resolve(dataset + ".zip");
      if (Files.exists(zip)) {
        if (Files.size(zip) != size || !digest(zip, "MD5").equalsIgnoreCase(md5)) {
          throw new IOException("Cached archive mismatch; remove only " + zip + " and retry.");
        }
      } else {
        System.err.println("Downloading FULL " + dataset + " (" + size + " bytes)...");
        Path partial = Files.createTempFile(directory, dataset, ".part");
        try {
          try (var source = fetch(item.path("url").asText(), key, size);
              var output = Files.newOutputStream(partial)) {
            long count = 0;
            byte[] buffer = new byte[1024 * 1024];
            for (int length; (length = source.read(buffer)) != -1; ) {
              count += length;
              if (count > size) throw new IOException("Archive exceeds advertised size.");
              output.write(buffer, 0, length);
            }
          }
          if (Files.size(partial) != size || !digest(partial, "MD5").equalsIgnoreCase(md5)) {
            throw new IOException("Archive size/checksum mismatch.");
          }
          Files.move(partial, zip, StandardCopyOption.ATOMIC_MOVE);
        } finally {
          Files.deleteIfExists(partial);
        }
      }
      Path csv = directory.resolve(dataset + ".csv");
      // Re-extraction verifies ZIP CRC and avoids trusting a stale/edited cached CSV.
      extract(zip, dataset + ".csv", csv);
      files
          .putObject(dataset)
          .put("zipMd5", md5)
          .put("zipBytes", size)
          .put("csvSha256", digest(csv, "SHA-256"))
          .put("csvBytes", Files.size(csv));
    }
    writeAtomic(
        directory.resolve("source.json"),
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(provenance) + "\n",
        true);
    return directory;
  }

  static void extract(Path zip, String primary, Path target) throws IOException {
    Path partial = Files.createTempFile(target.getParent(), "extract-", ".part");
    try (var archive = new ZipFile(zip.toFile())) {
      var entries = archive.stream().filter(e -> primary.equals(e.getName())).toList();
      if (entries.size() != 1 || entries.getFirst().isDirectory()) {
        throw new IOException("Expected exactly one primary CSV " + primary);
      }
      var entry = entries.getFirst();
      if (entry.getSize() < 0 || entry.getSize() > 32L * 1024 * 1024 * 1024) {
        throw new IOException("Primary CSV exceeds extraction bounds.");
      }
      var crc = new java.util.zip.CRC32();
      long count = 0;
      try (var input = archive.getInputStream(entry);
          var output = Files.newOutputStream(partial)) {
        byte[] buffer = new byte[1024 * 1024];
        for (int length; (length = input.read(buffer)) != -1; ) {
          count += length;
          if (count > entry.getSize()) throw new IOException("CSV exceeds declared size.");
          crc.update(buffer, 0, length);
          output.write(buffer, 0, length);
        }
      }
      if (count != entry.getSize() || crc.getValue() != entry.getCrc()) {
        throw new IOException("CSV size/CRC mismatch.");
      }
      Files.move(
          partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(partial);
    }
  }

  private static String sql(String value) {
    return value == null || value.isEmpty() ? "NULL" : "'" + value.replace("'", "''") + "'";
  }

  static String render(
      Path csv, List<String> headers, List<String> postcodes, String release, boolean island)
      throws IOException {
    if (postcodes.isEmpty()) return "";
    var wanted = new TreeSet<>(postcodes);
    var counts = new LinkedHashMap<String, Integer>();
    var ids = new HashSet<String>();
    var rows = new ArrayList<List<String>>();
    try (var reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8);
        var parser =
            CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(false)
                .get()
                .parse(reader)) {
      if (!headers.equals(parser.getHeaderNames())) {
        throw new IllegalArgumentException(
            "CSV header does not match pinned catalog: " + csv.getFileName());
      }
      for (var record : parser) {
        if (!record.isConsistent())
          throw new IllegalArgumentException("Malformed CSV row " + record.getRecordNumber());
        String postcode =
            record.get("postcode").replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        if (!wanted.contains(postcode)) continue;
        String id = record.get("udprn");
        if (!id.matches("[0-9]{1,8}") || !ids.add(id)) {
          throw new IllegalArgumentException("Invalid/duplicate source UDPRN for " + postcode);
        }
        List<String> values = new ArrayList<>();
        for (String header : headers) {
          String value = record.get(header);
          if (header.equals("geometry")) {
            if (!POINT.matcher(value).matches())
              throw new IllegalArgumentException("Invalid source POINT geometry.");
            values.add("ST_GeomFromText(" + sql(value) + ", " + (island ? 4258 : 27700) + ")");
          } else {
            values.add(sql(value));
          }
        }
        values.add(sql(release));
        rows.add(values);
        counts.merge(postcode, 1, Integer::sum);
      }
    }
    var missing = new TreeSet<>(wanted);
    missing.removeAll(counts.keySet());
    if (!missing.isEmpty())
      throw new IllegalArgumentException("Postcodes absent from FULL source: " + missing);
    rows.sort(Comparator.comparingLong(row -> Long.parseLong(row.getFirst().replace("'", ""))));
    var result = new StringBuilder("-- Source row counts: " + counts + "\n");
    result
        .append("INSERT INTO os_data.add_")
        .append(island ? "isl" : "gb")
        .append("_royalmailaddress_v2 (\n    ")
        .append(String.join(", ", headers))
        .append(", release_id\n) VALUES\n");
    result.append(
        String.join(
            ",\n", rows.stream().map(row -> "    (" + String.join(", ", row) + ")").toList()));
    return result.append(";\n").toString();
  }

  private static List<String> normalize(List<String> values) {
    var result = new TreeSet<String>();
    for (String value : values) {
      String postcode = value.replace(" ", "").toUpperCase(java.util.Locale.ROOT);
      if (!POSTCODE.matcher(postcode).matches())
        throw new IllegalArgumentException("Invalid postcode: " + value);
      result.add(postcode);
    }
    if (result.isEmpty()) throw new IllegalArgumentException("At least one postcode is required.");
    return List.copyOf(result);
  }

  private static boolean island(String postcode) {
    return postcode.matches("^(BT|GY|JE|IM).*");
  }

  private static List<String> seededPostcodes(Path file) throws IOException {
    var text = Files.readString(file);
    var result = new TreeSet<String>();
    for (String line : text.lines().toList()) {
      if (line.startsWith("-- Postcodes: ")) {
        result.addAll(normalize(List.of(line.substring(14).split(","))));
      }
    }
    var matcher = SQL_POSTCODE.matcher(text);
    while (matcher.find()) result.add(matcher.group(1).replace(" ", ""));
    return List.copyOf(result);
  }

  static void generate(String action, Path source, Path seeds, Path catalog, List<String> input)
      throws Exception {
    var manifest = JSON.readTree(source.resolve("source.json").toFile());
    if (!"FULL".equals(manifest.path("supplyType").asText())) {
      throw new IllegalArgumentException("Source provenance must identify a FULL download.");
    }
    if (!manifest.path("packageId").asText().matches("[0-9]+")
        || !manifest.path("versionId").asText().matches("[0-9]+")) {
      throw new IllegalArgumentException(
          "Source provenance needs numeric package/version identifiers.");
    }
    var columns = JSON.readTree(catalog.toFile()).get(0).path("columns");
    List<String> headers = new ArrayList<>();
    for (var column : columns) headers.add(column.path("name").asText());
    var files = new ArrayList<Path>();
    try (var stream = Files.list(seeds)) {
      files.addAll(
          stream.filter(p -> p.getFileName().toString().matches("R__.*\\.sql")).sorted().toList());
    }
    var plans = new LinkedHashMap<Path, List<String>>();
    if (action.equals("refresh")) {
      for (Path file : files) {
        String text = Files.readString(file);
        if (file.getFileName().toString().equals("R__seed_synthetic_cardiff_addresses.sql")
            || text.startsWith("-- Generated postcode fixtures:")
            || text.startsWith("-- OS FULL postcode fixtures")) {
          var selected = new ArrayList<>(seededPostcodes(file));
          // This terminated postcode is intentionally verified as a no-result case.
          if (file.getFileName().toString().equals("R__seed_synthetic_cardiff_addresses.sql"))
            selected.remove("CF101PX");
          plans.put(file, normalize(selected));
        }
      }
      if (plans.isEmpty())
        throw new IllegalArgumentException("No managed postcode seed files found.");
    } else {
      var selected = new TreeSet<>(normalize(input));
      for (Path file : files) selected.removeAll(seededPostcodes(file));
      if (selected.isEmpty()) {
        System.out.println("All requested postcodes are already seeded; no file changed.");
        return;
      }
      String name = String.join(",", selected);
      if (name.length() > 243)
        throw new IllegalArgumentException("Selection exceeds seed filename limit.");
      plans.put(seeds.resolve("R__seed_" + name + ".sql"), List.copyOf(selected));
    }
    var outputs = new LinkedHashMap<Path, String>();
    for (var plan : plans.entrySet()) {
      String release =
          plan.getKey().getFileName().toString().equals("R__seed_synthetic_cardiff_addresses.sql")
              ? "local-synthetic-cardiff"
              : "local-generated-"
                  + plan.getKey()
                      .getFileName()
                      .toString()
                      .replaceFirst("^R__seed_", "")
                      .replaceFirst("\\.sql$", "");
      var result =
          new StringBuilder(
              "-- OS FULL postcode fixtures; all matching source records retained.\n");
      result.append("-- Postcodes: ").append(String.join(",", plan.getValue())).append("\n");
      result
          .append("-- Source: package ")
          .append(manifest.path("packageId").asText())
          .append("; FULL version ")
          .append(manifest.path("versionId").asText())
          .append("\n");
      for (String dataset : DATASETS) {
        boolean isl = dataset.contains("_isl_");
        var selected = plan.getValue().stream().filter(pc -> island(pc) == isl).toList();
        if (!selected.isEmpty()) {
          var info = manifest.path("files").path(dataset);
          Path csv = source.resolve(dataset + ".csv");
          if (!digest(csv, "SHA-256").equals(info.path("csvSha256").asText())) {
            throw new IOException("Source CSV checksum mismatch: " + csv.getFileName());
          }
          result
              .append("-- ")
              .append(dataset)
              .append(".csv SHA-256: ")
              .append(info.path("csvSha256").asText())
              .append("\n");
        }
        result
            .append("DELETE FROM os_data.")
            .append(dataset)
            .append("_v2 WHERE release_id = ")
            .append(sql(release))
            .append(";\n");
      }
      for (String dataset : DATASETS) {
        boolean isl = dataset.contains("_isl_");
        var selected = plan.getValue().stream().filter(pc -> island(pc) == isl).toList();
        result.append(render(source.resolve(dataset + ".csv"), headers, selected, release, isl));
      }
      outputs.put(plan.getKey(), result.toString());
    }
    // Validate/render every managed file before publishing any refresh.
    for (var output : outputs.entrySet()) {
      writeAtomic(output.getKey(), output.getValue(), action.equals("refresh"));
      System.out.println("Created " + output.getKey().getFileName() + " from FULL source records.");
    }
  }

  private static void writeAtomic(Path target, String content, boolean replace) throws IOException {
    Path partial = Files.createTempFile(target.getParent(), ".seed-", ".part");
    try {
      Files.writeString(partial, content);
      if (replace) {
        Files.move(
            partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } else {
        Files.createLink(target, partial);
      }
    } finally {
      Files.deleteIfExists(partial);
    }
  }
}
