package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/finance-workbench-schema.sql", config = @SqlConfig(encoding = "UTF-8"))
class FinanceWorkbenchQueryApiTest {
    @Autowired
    MockMvc mvc;

    @Test
    void listsAutomaticallyLinkedReceivablesAndPayablesWithBalances() throws Exception {
        mvc.perform(get("/api/workbench/finance").cookie(new Cookie("OPS_SESSION", "finance-test-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].cashDirection").value("PAYABLE"))
                .andExpect(jsonPath("$.data.items[0].businessNo").value("PO-F-002"))
                .andExpect(jsonPath("$.data.items[0].amount").value(100))
                .andExpect(jsonPath("$.data.items[0].settledAmount").value(40))
                .andExpect(jsonPath("$.data.items[0].outstandingAmount").value(60))
                .andExpect(jsonPath("$.data.items[0].status").value("待付款"))
                .andExpect(jsonPath("$.data.items[1].cashDirection").value("RECEIVABLE"))
                .andExpect(jsonPath("$.data.items[1].businessNo").value("SO-F-001"))
                .andExpect(jsonPath("$.data.items[1].amount").value(80))
                .andExpect(jsonPath("$.data.items[1].settledAmount").value(30))
                .andExpect(jsonPath("$.data.items[1].outstandingAmount").value(50))
                .andExpect(jsonPath("$.data.items[1].status").value("待收款"));
    }
}
