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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        session = loginAs("admin");
    }

    private Cookie loginAs(String username) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"123\"}"))
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

    @Test
    void financeCanUpdateBothProductPrices() throws Exception {
        Cookie financeSession = loginAs("finance");

        mvc.perform(put("/api/workbench/product/1").cookie(financeSession).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"currentCost\":12.34,\"factoryPrice\":23.45,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentCost").value(12.34))
                .andExpect(jsonPath("$.data.factoryPrice").value(23.45));
    }

    @Test
    void regularUserCannotSubmitEitherProductPrice() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(put("/api/workbench/product/1").cookie(userSession).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"currentCost\":12.34,\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));

        mvc.perform(put("/api/workbench/product/1").cookie(userSession).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"factoryPrice\":23.45,\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));
    }

    @Test
    void regularUserPricePermissionPrecedesProductNameValidation() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(post("/api/workbench/product").cookie(userSession).contentType("application/json")
                        .content("{\"currentCost\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));
    }

    @Test
    void regularUserPricePermissionPrecedesNegativePriceValidation() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(post("/api/workbench/product").cookie(userSession).contentType("application/json")
                        .content("{\"productName\":\"越权产品\",\"factoryPrice\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));
    }

    @Test
    void regularUserPricePermissionPrecedesPriceDeserialization() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(put("/api/workbench/product/1").cookie(userSession).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"currentCost\":\"not-a-number\",\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));
    }

    @Test
    void regularUserPricePermissionPrecedesMissingVersionValidation() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(put("/api/workbench/product/1").cookie(userSession).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"factoryPrice\":23.45}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));
    }

    @Test
    void regularUserCanUpdateProductModelWithoutClearingPrices() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(put("/api/workbench/product/1").cookie(userSession).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"model\":\"P90\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.model").value("P90"))
                .andExpect(jsonPath("$.data.currentCost").value(10.00))
                .andExpect(jsonPath("$.data.factoryPrice").value(20.00));

        assertThat(jdbc.queryForMap("SELECT current_cost,factory_price FROM sku WHERE id=1"))
                .containsEntry("current_cost", new java.math.BigDecimal("10.0000"))
                .containsEntry("factory_price", new java.math.BigDecimal("20.0000"));
    }

    @Test
    void regularUserCanMaintainUserDetailsButCannotAssignRoles() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(put("/api/workbench/user/3").cookie(userSession).contentType("application/json")
                        .content("{\"displayName\":\"新操作员\",\"phone\":\"13900000000\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("新操作员"))
                .andExpect(jsonPath("$.data.role").value("USER"));

        mvc.perform(put("/api/workbench/user/3").cookie(userSession).contentType("application/json")
                        .content("{\"displayName\":\"新操作员\",\"role\":\"FINANCE\",\"version\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅管理员可分配用户角色"));
    }

    @Test
    void newUsersDefaultToUserAndAdminCanAssignAValidRole() throws Exception {
        mvc.perform(post("/api/workbench/user").cookie(session).contentType("application/json")
                        .content("{\"username\":\"new-user\",\"displayName\":\"新用户\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("USER"));

        mvc.perform(post("/api/workbench/user").cookie(session).contentType("application/json")
                        .content("{\"username\":\"new-finance\",\"displayName\":\"新财务\",\"role\":\"FINANCE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("FINANCE"));

        mvc.perform(get("/api/workbench/user").cookie(session).param("keyword", "new-finance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].role").value("FINANCE"));
    }

    @Test
    void adminCannotAssignAnInvalidRole() throws Exception {
        mvc.perform(post("/api/workbench/user").cookie(session).contentType("application/json")
                        .content("{\"username\":\"bad-role\",\"displayName\":\"非法角色\",\"role\":\"OWNER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户角色无效"));
    }

    @Test
    void regularUserCannotSubmitExplicitNullProductPrices() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(put("/api/workbench/product/1").cookie(userSession).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"currentCost\":null,\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));
        mvc.perform(put("/api/workbench/product/1").cookie(userSession).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"factoryPrice\":null,\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));

        assertThat(jdbc.queryForMap("SELECT current_cost,factory_price FROM sku WHERE id=1"))
                .containsEntry("current_cost", new java.math.BigDecimal("10.0000"))
                .containsEntry("factory_price", new java.math.BigDecimal("20.0000"));
    }

    @Test
    void authorizedUsersCanClearAnExplicitNullPriceWhilePreservingMissingPrices() throws Exception {
        Cookie financeSession = loginAs("finance");

        mvc.perform(put("/api/workbench/product/1").cookie(financeSession).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"currentCost\":null,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentCost").doesNotExist())
                .andExpect(jsonPath("$.data.factoryPrice").value(20.00));

        mvc.perform(put("/api/workbench/product/1").cookie(session).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"factoryPrice\":null,\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentCost").doesNotExist())
                .andExpect(jsonPath("$.data.factoryPrice").doesNotExist());

        assertThat(jdbc.queryForMap("SELECT current_cost,factory_price FROM sku WHERE id=1"))
                .containsEntry("current_cost", null)
                .containsEntry("factory_price", null);
    }

    @Test
    void regularUserCannotSubmitExplicitNullRole() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(put("/api/workbench/user/3").cookie(userSession).contentType("application/json")
                        .content("{\"displayName\":\"操作员\",\"role\":null,\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅管理员可分配用户角色"));
    }

    @Test
    void regularUserCannotSetPricesOrRolesOnCreateAndAdminCanUpdateARole() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(post("/api/workbench/product").cookie(userSession).contentType("application/json")
                        .content("{\"skuCode\":\"SKU-USER\",\"productName\":\"越权产品\",\"currentCost\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));
        mvc.perform(post("/api/workbench/user").cookie(userSession).contentType("application/json")
                        .content("{\"username\":\"forbidden-finance\",\"displayName\":\"越权财务\",\"role\":\"FINANCE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅管理员可分配用户角色"));

        mvc.perform(put("/api/workbench/user/3").cookie(session).contentType("application/json")
                        .content("{\"displayName\":\"操作员\",\"role\":\"FINANCE\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("FINANCE"));
        assertThat(jdbc.queryForObject("SELECT role FROM sys_user WHERE id=3", String.class)).isEqualTo("FINANCE");
    }

    @Test
    void updatingAMissingUserKeepsTheConflictResponse() throws Exception {
        mvc.perform(put("/api/workbench/user/999").cookie(session).contentType("application/json")
                        .content("{\"displayName\":\"不存在\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("数据已被其他操作修改，请重新打开后再试"));
    }
}
