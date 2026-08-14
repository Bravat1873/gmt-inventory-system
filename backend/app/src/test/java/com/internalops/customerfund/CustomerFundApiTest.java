package com.internalops.customerfund;

import com.internalops.auth.CurrentUser;
import com.internalops.auth.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerFundApiTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("customer-fund-api-" + System.nanoTime()).addScript("customer-fund-schema.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        CustomerFundService commands = new CustomerFundService(jdbc);
        CustomerFundQueryService queries = new CustomerFundQueryService(jdbc);
        mvc = MockMvcBuilders.standaloneSetup(new CustomerFundController(commands, queries)).build();
        CurrentUser.set(new CurrentUser(9, "finance", "财务", UserRole.FINANCE));
    }

    @AfterEach void clear() { CurrentUser.clear(); }

    @Test void summaryAcceptsMySqlLocalDateTimeValues() {
        assertThat(CustomerFundQueryService.localDateTime(java.time.LocalDateTime.of(2026,8,14,12,30))).isEqualTo(java.time.LocalDateTime.of(2026,8,14,12,30));
    }

    @Test
    void overviewShowsBalanceOutstandingCoverageAndPendingAmount() throws Exception {
        mvc.perform(post("/api/customers/1/funds/deposits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":30,\"paymentDate\":\"2026-08-14\",\"paymentMethod\":\"银行转账\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/customers/1/funds/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(0))
                .andExpect(jsonPath("$.data.orderOutstandingAmount").value(80))
                .andExpect(jsonPath("$.data.coverageRatio").value(0))
                .andExpect(jsonPath("$.data.pendingAmount").value(30));
    }

    @Test
    void reviewCreatesLedgerAndSummaryAndCsvUseSameApprovedData() throws Exception {
        String response = mvc.perform(post("/api/customers/1/funds/deposits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100,\"paymentDate\":\"2026-08-14\",\"paymentMethod\":\"银行转账\"}"))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(response.replaceAll(".*\\\"data\\\":([0-9]+).*", "$1"));
        mvc.perform(post("/api/customer-funds/requests/" + id + "/review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"comment\":\"确认到账\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/customers/1/funds/ledger"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].entryType").value("CUSTOMER_DEPOSIT"));
        mvc.perform(get("/api/customers/1/funds/summary").param("period", "MONTH").param("from", "2026-08-01").param("to", "2026-08-31"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].depositAmount").value(100));
        mvc.perform(get("/api/customers/1/funds/summary/export").param("period", "MONTH").param("from", "2026-08-01").param("to", "2026-08-31"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("客户打款")));
    }

    @Test
    void depositMayLinkCurrentCustomerOrderButRejectsInvalidLinks() throws Exception {
        mvc.perform(get("/api/customers/1/funds/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].orderNo").value("SO-001"))
                .andExpect(jsonPath("$.data.length()").value(1));

        String response = mvc.perform(post("/api/customers/1/funds/deposits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100,\"paymentDate\":\"2026-08-14\",\"paymentMethod\":\"银行转账\",\"orderId\":10}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(response.replaceAll(".*\\\"data\\\":([0-9]+).*", "$1"));
        mvc.perform(post("/api/customer-funds/requests/" + id + "/review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"comment\":\"确认到账\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/customers/1/funds/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sourceNo").value("SO-001"));

        mvc.perform(post("/api/customers/1/funds/deposits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10,\"paymentDate\":\"2026-08-14\",\"paymentMethod\":\"银行转账\",\"orderId\":30}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/customers/1/funds/deposits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10,\"paymentDate\":\"2026-08-14\",\"paymentMethod\":\"银行转账\",\"orderId\":31}"))
                .andExpect(status().isBadRequest());
    }
}