package uk.gov.companieshouse.addresslookup.dbmigrate;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Invoked synchronously by the DB pipeline. A thrown {@link MigrationException} surfaces to the
 * caller as {@code FunctionError}, with {@code errorMessage} of the form {@code CODE: detail}.
 */
public final class Handler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
  private static final Logger LOG = LoggerFactory.getLogger(Handler.class.getName());

  private final MigrationService service;

  public Handler() {
    this(MigrationService.fromEnvironment());
  }

  Handler(MigrationService service) {
    this.service = service;
  }

  @Override
  public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
    // Stays "unknown" when the request cannot be parsed, so the failure log shows command=unknown.
    String flywayCommandName = "unknown";
    try {
      MigrationRequest request = MigrationRequest.parse(event);
      flywayCommandName = request.command().wireName();
      LOG.info(
          "db-migration-lambda start",
          Map.of(
              "command", flywayCommandName,
              "key", request.key(),
              "versionId", request.versionId(),
              "sha256", request.sha256()));
      Map<String, Object> result = service.handle(request);
      String migrationSummary = summary(result);
      LOG.info(
          "db-migration-lambda finished",
          Map.of(
              "command", flywayCommandName,
              "status", String.valueOf(result.get("status")),
              "schemaVersion", String.valueOf(result.get("schemaVersion")),
              "summary", migrationSummary,
              "durationMs", String.valueOf(result.get("durationMs"))));
      return result;
    } catch (MigrationException e) {
      // The message already has the form CODE: detail, so it is surfaced unchanged.
      throw e;
    } catch (RuntimeException e) {
      throw new IllegalStateException(
          "db-migration-lambda command=" + flywayCommandName + " failed unexpectedly", e);
    }
  }

  private static String summary(Map<String, Object> result) {
    for (String field : new String[] {"applied", "repaired", "pending"})
      if (result.get(field) instanceof List<?> migrations) return field + "=" + migrations;
    return "";
  }
}
