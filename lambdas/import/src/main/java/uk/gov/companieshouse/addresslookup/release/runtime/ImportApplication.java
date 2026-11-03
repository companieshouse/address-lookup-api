package uk.gov.companieshouse.addresslookup.release.runtime;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AwsClientsConfiguration;
import uk.gov.companieshouse.addresslookup.release.importer.ImportService;
import uk.gov.companieshouse.addresslookup.releasecore.aurora.AuroraOperations;
import uk.gov.companieshouse.addresslookup.releasecore.service.DatasetImportService;
import uk.gov.companieshouse.addresslookup.releasecore.service.PromotionService;
import uk.gov.companieshouse.addresslookup.releasecore.service.ReleaseRegistration;
import uk.gov.companieshouse.addresslookup.releasecore.state.Release;
import uk.gov.companieshouse.addresslookup.releasecore.state.ReleaseRepository;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableConfigurationProperties(ImportProperties.class)
@EntityScan(basePackageClasses = Release.class)
@EnableJpaRepositories(basePackageClasses = ReleaseRepository.class)
@Import({
    AwsClientsConfiguration.class,
    ImportService.class,
    DatasetImportService.class,
    PromotionService.class,
    ReleaseRegistration.class,
    AuroraOperations.class
})
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
