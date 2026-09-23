package uk.gov.companieshouse.addresslookup.lamda.shared.acquisition;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.s3.S3Client;
import uk.gov.companieshouse.addresslookup.lamda.shared.os.OsClient;
import uk.gov.companieshouse.addresslookup.lamda.shared.runtime.AwsClientsConfiguration;

/**
 * Each red-zone handler creates only its own entry service and dependencies. No database wiring.
 */
@Configuration(proxyBeanMethods = false)
@Lazy
@PropertySource("classpath:acquisition.properties")
@Import(AwsClientsConfiguration.class)
@EnableConfigurationProperties(AcquisitionProperties.class)
public class AcquisitionConfiguration {
  @Bean
  OsClient osClient(AcquisitionProperties properties, ObjectMapper json) {
    return new OsClient(
        AcquisitionProperties.required(properties.osApiKey(), "OS_API_KEY"),
        properties.connectTimeout(),
        properties.requestTimeout(),
        json);
  }

  @Bean
  Commands commands(EventBridgeClient events, AcquisitionProperties properties, ObjectMapper json) {
    return new Commands(
        events, AcquisitionProperties.required(properties.eventBus(), "EVENT_BUS"), json);
  }

  @Bean
  DiscoveryService discoveryService(
      S3Client s3, OsClient os, Commands commands, AcquisitionProperties properties) {
    return new DiscoveryService(s3, os, commands, properties);
  }

  @Bean
  DownloadService downloadService(S3Client s3, OsClient os, AcquisitionProperties properties) {
    return new DownloadService(s3, os, properties);
  }

  @Bean
  ScanResultsService scanResultsService(
      S3Client s3, Commands commands, AcquisitionProperties properties) {
    return new ScanResultsService(s3, commands, properties);
  }

  @Bean
  ExtractionService extractionService(S3Client s3, AcquisitionProperties properties) {
    return new ExtractionService(s3, properties);
  }
}
