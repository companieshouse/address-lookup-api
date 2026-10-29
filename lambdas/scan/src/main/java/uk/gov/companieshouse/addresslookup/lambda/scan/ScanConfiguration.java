package uk.gov.companieshouse.addresslookup.lambda.scan;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionConfiguration;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;

@Configuration(proxyBeanMethods = false)
@Import(AcquisitionConfiguration.class)
public class ScanConfiguration {
  @Bean
  ScanResultsService scanResultsService(Commands commands, AcquisitionProperties properties) {
    return new ScanResultsService(
        commands, properties.ingestBucket(), properties.scannedBucket());
  }
}
