package uk.gov.companieshouse.addresslookup.dbmigrate;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import java.util.Map;

/**
 * Invoked synchronously by the DB pipeline. A thrown {@link MigrationException} surfaces to the
 * caller as {@code FunctionError}, with {@code errorMessage} of the form {@code CODE: detail}.
 */
public final class Handler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
  private final MigrationService service;

  public Handler() {
    this(MigrationService.fromEnvironment());
  }

  Handler(MigrationService service) {
    this.service = service;
  }

  @Override
  public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
    MigrationRequest request = MigrationRequest.parse(event);
    log(context, "db-migrate start command=%s key=%s versionId=%s sha256=%s"
        .formatted(request.command().wireName(), request.key(), request.versionId(), request.sha256()));
    Map<String, Object> result = service.handle(request);
    log(context, "db-migrate %s schemaVersion=%s %s"
        .formatted(result.get("status"), result.get("schemaVersion"), summary(result)));
    return result;
  }

  private static String summary(Map<String, Object> result) {
    for (String field : new String[] {"applied", "repaired", "pending"})
      if (result.get(field) instanceof java.util.List<?> list) return field + "=" + list;
    return "";
  }

  private static void log(Context context, String message) {
    if (context != null && context.getLogger() != null) context.getLogger().log(message + "\n");
    else System.out.println(message);
  }
}
