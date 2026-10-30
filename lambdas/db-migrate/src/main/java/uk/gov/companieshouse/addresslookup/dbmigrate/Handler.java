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
    try {
      MigrationRequest request = MigrationRequest.parse(event);
      LOG.info(
          "db-migrate start command={} key={} versionId={} sha256={}",
          request.command().wireName(),
          request.key(),
          request.versionId(),
          request.sha256());
      Map<String, Object> result = service.handle(request);
      LOG.info(
          "db-migrate {} schemaVersion={} {} durationMs={}",
          result.get("status"),
          result.get("schemaVersion"),
          summary(result),
          result.get("durationMs"));
      return result;
    } catch (MigrationException e) {
      // The message already has the form CODE: detail, so the code is visible in CloudWatch.
      LOG.error("db-migrate failed {}", e.getMessage());
      throw e;
    } catch (RuntimeException e) {
      LOG.error("db-migrate failed unexpectedly", e);
      throw e;
    }
  }

  private static String summary(Map<String, Object> result) {
    for (String field : new String[] {"applied", "repaired", "pending"})
      if (result.get(field) instanceof List<?> migrations) return field + "=" + migrations;
    return "";
  }
}
