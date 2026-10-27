package uk.gov.companieshouse.addresslookup.lambda.scan;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public final class ScanApplication {
  private ScanApplication() {}

  public static ScanResultsService start() {
    var context = new AnnotationConfigApplicationContext(ScanConfiguration.class);
    context.registerShutdownHook();
    return context.getBean(ScanResultsService.class);
  }
}
