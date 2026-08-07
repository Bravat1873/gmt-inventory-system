package com.internalops.workbench;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/master-command-schema.sql")
class MasterDataCommandApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private Cookie session;

    @BeforeEach
    void login() throws Exception {
        session = mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("OPS_SESSION");
    }

    @Test
    void createsAndUpdatesCustomerWithOptimisticLocking() throws Exception {
        String body = mvc.perform(post("/api/workbench/customer").cookie(session).contentType("application/json")
                        .content("{\"customerName\":\"新客户\",\"contactName\":\"张三\",\"phone\":\"13800000000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerCode").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data").path("id").asLong();

        mvc.perform(put("/api/workbench/customer/{id}", id).cookie(session).contentType("application/json")
                        .content("{\"customerName\":\"已修改客户\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));

        mvc.perform(put("/api/workbench/customer/{id}", id).cookie(session).contentType("application/json")
                        .content("{\"customerName\":\"冲突修改\",\"version\":0}"))
                .andExpect(status().isConflict());
    }

    @Test
    void adjustsInventoryAndWritesTransaction() throws Exception {
        mvc.perform(put("/api/workbench/inventory/1").cookie(session).contentType("application/json")
                        .content("{\"actualQuantity\":12,\"lockedQuantity\":2,\"inTransitQuantity\":3,\"reason\":\"盘点调整\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actualQuantity").value(12))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    void keepsTheEnteredInventoryTotalWhenSavingInboundDetails() throws Exception {
        mvc.perform(put("/api/workbench/inventory/1").cookie(session).contentType("application/json")
                        .content("{\"actualQuantity\":6,\"lockedQuantity\":3,\"inTransitQuantity\":0,"
                                + "\"inventoryMovements\":[{\"date\":\"2026-08-07\",\"direction\":\"INBOUND\",\"quantity\":2}],\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actualQuantity").value(6))
                .andExpect(jsonPath("$.data.lockedQuantity").value(3));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transaction WHERE sku_id=1", Integer.class)).isEqualTo(2);
    }

    @Test
    void createsInventoryBySkuCodeAndUsesTheFiveLockedSources() throws Exception {
        mvc.perform(post("/api/workbench/inventory").cookie(session).contentType("application/json")
                        .content("{\"skuCode\":\"SKU002\",\"actualQuantity\":10,\"inTransitQuantity\":1,\"inventoryMovements\":[{\"date\":\"2026-08-06\",\"direction\":\"INBOUND\",\"quantity\":10},{\"date\":\"2026-08-06\",\"direction\":\"OUTBOUND\",\"quantity\":2}],"
                                + "\"lockedMingAiJunQiao\":2,\"lockedBoLeLongMi\":3,\"lockedLaos\":1,"
                                + "\"lockedBeiLang\":0,\"lockedMalaysia\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skuId").value(2))
                .andExpect(jsonPath("$.data.actualQuantity").value(18))
                .andExpect(jsonPath("$.data.lockedQuantity").value(8));
    }

    @Test
    void savesEditableInventoryProductFieldsAndAcceptsAnAvailableQuantity() throws Exception {
        mvc.perform(post("/api/workbench/inventory").cookie(session).contentType("application/json")
                        .content("{\"skuId\":2,\"actualQuantity\":10,\"availableQuantity\":10,\"lockedQuantity\":1,\"inTransitQuantity\":1,"
                                + "\"model\":\"P90\",\"configuration\":\"可视对讲\",\"productVersion\":\"工程款\","
                                + "\"color\":\"宇宙黑\",\"lockBody\":\"6068\",\"unit\":\"套\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actualQuantity").value(10));

        var sku = jdbc.queryForMap("SELECT model,configuration,product_version,color,lock_body,unit FROM sku WHERE id=2");
        assertThat(sku).containsEntry("model", "P90")
                .containsEntry("configuration", "可视对讲")
                .containsEntry("product_version", "工程款")
                .containsEntry("color", "宇宙黑")
                .containsEntry("lock_body", "6068")
                .containsEntry("unit", "套");
    }

    @Test
    void rejectsARepeatedUsernameWithAnActionableMessage() throws Exception {
        String body = "{\"username\":\"operator\",\"displayName\":\"操作员\",\"phone\":\"13800000000\"}";
        mvc.perform(post("/api/workbench/user").cookie(session).contentType("application/json").content(body))
                .andExpect(status().isOk());

        mvc.perform(post("/api/workbench/user").cookie(session).contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户名已存在，请换一个"));
    }
}
