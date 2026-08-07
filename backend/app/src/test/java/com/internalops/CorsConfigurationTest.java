package com.internalops;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CorsConfigurationTest {
    @Autowired MockMvc mvc;

    @Test
    void allowsLocalViteFrontendToCallApi() throws Exception {
        mvc.perform(options("/api/workbench/order")
                        .header("Origin", "http://localhost:5175")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5175"));
    }

    @Test
    void allowsProductionDomainToCallApiWithCredentials() throws Exception {
        mvc.perform(options("/api/auth/login")
                        .header("Origin", "https://testit.bravat.com")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://testit.bravat.com"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }
}
