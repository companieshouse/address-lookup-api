package uk.gov.companieshouse.addresslookup.release.runtime;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import uk.gov.companieshouse.addresslookup.lamda.shared.runtime.AwsClientsConfiguration;
import uk.gov.companieshouse.addresslookup.release.importer.ImportService;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableConfigurationProperties(ImportProperties.class)
@Import({AwsClientsConfiguration.class, ImportService.class})
@PropertySource("classpath:importer.properties")
public class ImportApplication {
  public static ImportService start() {
    // The platform injects environment variables before the process starts.
    var context =
        new SpringApplicationBuilder(ImportApplication.class)
            .web(WebApplicationType.NONE)
            .run();
    return context.getBean(ImportService.class);
  }
}
