package com.internalops.workbench;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/master-command-schema.sql")
class MasterDataCommandApiTest {
    @Autowired MockMvc mvc;
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
    void rejectsARepeatedUsernameWithAnActionableMessage() throws Exception {
        String body = "{\"username\":\"operator\",\"displayName\":\"操作员\",\"phone\":\"13800000000\"}";
        mvc.perform(post("/api/workbench/user").cookie(session).contentType("application/json").content(body))
                .andExpect(status().isOk());

        mvc.perform(post("/api/workbench/user").cookie(session).contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户名已存在，请换一个"));
    }
}
