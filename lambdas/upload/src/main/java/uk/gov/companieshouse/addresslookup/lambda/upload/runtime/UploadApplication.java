package uk.gov.companieshouse.addresslookup.lambda.upload.runtime;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionConfiguration;
import uk.gov.companieshouse.addresslookup.lambda.upload.service.StagingLoader;
import uk.gov.companieshouse.addresslookup.lambda.upload.service.UploadService;
import uk.gov.companieshouse.addresslookup.releasecore.aurora.AuroraOperations;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableConfigurationProperties(UploadProperties.class)
@Import({
    AcquisitionConfiguration.class,
    DatabaseConfiguration.class,
    UploadService.class,
    StagingLoader.class,
    AuroraOperations.class
})
@PropertySource("classpath:upload.properties")
public class UploadApplication {
  public static UploadService start() {
    // The platform injects environment variables before the process starts.
    var context =
        new SpringApplicationBuilder(UploadApplication.class).web(WebApplicationType.NONE).run();
    return context.getBean(UploadService.class);
  }
}
