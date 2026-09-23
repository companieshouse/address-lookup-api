package uk.gov.companieshouse.addresslookup.lamda.shared.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.s3.S3Client;
import uk.gov.companieshouse.release.model.ReleaseJson;

/** Clients are reused for the lifetime of a Lambda execution environment. */
@Configuration(proxyBeanMethods = false)
@Lazy
public class AwsClientsConfiguration {
  @Bean(destroyMethod = "close")
  S3Client s3Client() {
    return S3Client.create();
  }

  @Bean(destroyMethod = "close")
  EventBridgeClient eventBridgeClient() {
    return EventBridgeClient.create();
  }

  @Bean
  ObjectMapper releaseObjectMapper() {
    return ReleaseJson.MAPPER;
  }
}
