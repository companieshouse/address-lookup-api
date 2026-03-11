package uk.gov.companieshouse.addresslookup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

    public static final String NAMESPACE = "address-lookup-api";
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
