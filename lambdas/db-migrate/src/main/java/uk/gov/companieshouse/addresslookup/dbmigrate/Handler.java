package uk.gov.companieshouse.addresslookup.dbmigrate;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Invoked synchronously by the DB pipeline. A thrown {@link MigrationException} surfaces to the
 * caller as {@code FunctionError}, with {@code errorMessage} of the form {@code CODE: detail}.
 */
public final class Handler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
  private static final Logger LOG = LoggerFactory.getLogger(Handler.class);

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
          "db-migration-lambda start command={} key={} versionId={} sha256={}",
          flywayCommandName,
          request.key(),
          request.versionId(),
          request.sha256());
      Map<String, Object> result = service.handle(request);
      String migrationSummary = summary(result);
      LOG.info(
          "db-migration-lambda command={} status={} schemaVersion={} {} durationMs={}",
          flywayCommandName,
          result.get("status"),
          result.get("schemaVersion"),
          migrationSummary,
          result.get("durationMs"));
      return result;
    } catch (RuntimeException e) {
      // A MigrationException already has the form CODE: detail, so it is surfaced unchanged.
      if (e instanceof MigrationException) throw e;
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
