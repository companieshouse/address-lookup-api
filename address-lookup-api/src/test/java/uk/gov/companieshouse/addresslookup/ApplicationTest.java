package uk.gov.companieshouse.addresslookup;

import static org.mockito.Mockito.mockStatic;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

class ApplicationTest {

    @Test
    void applicationCanBeConstructed() {
        assertNotNull(new Application());
    }

    @Test
    void mainStartsSpringApplication() {
        String[] arguments = {};
        try (MockedStatic<SpringApplication> springApplication = mockStatic(SpringApplication.class)) {
            Application.main(arguments);

            springApplication.verify(() -> SpringApplication.run(Application.class, arguments));
        }
    }
}
