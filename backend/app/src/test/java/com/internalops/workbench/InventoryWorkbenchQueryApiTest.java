package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Sql(scripts = "/inventory-workbench-schema.sql", config = @SqlConfig(encoding = "UTF-8"))
class InventoryWorkbenchQueryApiTest {
    @Autowired MockMvc mvc;

    @Test
    void returnsEveryLockedAllocationInInventoryList() throws Exception {
        mvc.perform(get("/api/workbench/inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].lockedMingAiJunQiao").value(1))
                .andExpect(jsonPath("$.data.items[0].lockedBoLeLongMi").value(2))
                .andExpect(jsonPath("$.data.items[0].lockedLaos").value(3))
                .andExpect(jsonPath("$.data.items[0].lockedBeiLang").value(4))
                .andExpect(jsonPath("$.data.items[0].lockedMalaysia").value(5))
                .andExpect(jsonPath("$.data.items[0].movementCount").value(2))
                .andExpect(jsonPath("$.data.items[0].movementSummary").value("06/22 出库 7；08/04 入库 9"));
    }

    @Test
    void returnsDynamicInventoryMovementsByDate() throws Exception {
        mvc.perform(get("/api/workbench/inventory/1/movements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].date").value("2026-06-22"))
                .andExpect(jsonPath("$.data[0].direction").value("出库"))
                .andExpect(jsonPath("$.data[0].quantity").value(7))
                .andExpect(jsonPath("$.data[0].sourceColumn").value("T:0622出库"))
                .andExpect(jsonPath("$.data[1].date").value("2026-08-04"))
                .andExpect(jsonPath("$.data[1].direction").value("入库"));
    }
}
