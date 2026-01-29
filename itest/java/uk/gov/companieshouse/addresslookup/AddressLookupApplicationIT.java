package uk.gov.companieshouse.addresslookup;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.companieshouse.logging.util.LogContextProperties.REQUEST_ID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AddressLookupApplicationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Test
    void shouldLoadApplicationContext() {
        assertNotNull(context);
    }

    @Test
    void shouldReturn200FromGetHealthEndpoint() throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/healthcheck")
                        .header(REQUEST_ID.value(), "request_id"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string("{\"status\":\"UP\"}"));
    }
}
