package uk.gov.companieshouse.addresslookup.lambda.shared.runtime;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionConfiguration;

public final class AcquisitionApplication {
  private AcquisitionApplication() {}

  public static <T> T start(Class<T> entryService) {
    var context = new AnnotationConfigApplicationContext(AcquisitionConfiguration.class);
    context.registerShutdownHook();
    return context.getBean(entryService);
  }
}
