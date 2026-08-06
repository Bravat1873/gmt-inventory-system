package com.internalops.dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(statements = {
        "CREATE TABLE IF NOT EXISTS sales_order (id BIGINT PRIMARY KEY, status VARCHAR(40) NOT NULL)",
        "CREATE TABLE IF NOT EXISTS purchase_order (id BIGINT PRIMARY KEY, status VARCHAR(40) NOT NULL)"
})
class DashboardApiTest {
    @Autowired
    MockMvc mvc;

    @Test
    void exposesDashboardSummary() throws Exception {
        mvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pendingReceipt").isNumber())
                .andExpect(jsonPath("$.data.pendingStock").isNumber())
                .andExpect(jsonPath("$.data.pendingPurchasePayment").isNumber())
                .andExpect(jsonPath("$.data.pendingShipment").isNumber());
    }

    @Test
    void checksDatabaseHealth() throws Exception {
        mvc.perform(get("/api/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.database").value("正常"));
    }
}
