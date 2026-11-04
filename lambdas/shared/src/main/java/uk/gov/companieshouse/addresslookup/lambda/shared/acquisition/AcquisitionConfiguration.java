package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.PropertySource;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import uk.gov.companieshouse.addresslookup.lambda.shared.os.OsClient;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AwsClientsConfiguration;

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
  OsClient osClient(AcquisitionProperties properties) {
    return new OsClient(
        AcquisitionProperties.required(properties.osApiKey(), "OS_API_KEY"),
        properties.connectTimeout(),
        properties.requestTimeout());
  }

  @Bean
  Commands commands(EventBridgeClient events, AcquisitionProperties properties) {
    return new Commands(
        events, AcquisitionProperties.required(properties.eventBus(), "EVENT_BUS"), JSON);
  }
}
