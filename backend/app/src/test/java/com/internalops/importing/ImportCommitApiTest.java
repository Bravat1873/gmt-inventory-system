package com.internalops.importing;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = {"/auth-schema.sql", "/import-schema.sql", "/import-commit-schema.sql"})
class ImportCommitApiTest {
    private static final String PASSWORD_HASH = "$2b$12$budbHP4blgVi7rbdpNMA5eYa/D83VYzeh/I7RcK7dLLn/qSVwmu7O";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ImportBatchRepository repository;

    @BeforeEach
    void addRoleUsers() {
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,enabled,role) VALUES(?,?,?,?,?)",
                "finance", PASSWORD_HASH, "财务", true, "FINANCE");
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,enabled,role) VALUES(?,?,?,?,?)",
                "regular-user", PASSWORD_HASH, "操作员", true, "USER");
    }

    @Test
    void regularUserCannotCommitCostAndFinanceCommitsTheWholeBatch() throws Exception {
        jdbc.update("INSERT INTO sku(sku_code,product_name,current_cost,factory_price,enabled) VALUES('COST-1','成本一',10,20,TRUE)");
        jdbc.update("INSERT INTO sku(sku_code,product_name,current_cost,factory_price,enabled) VALUES('COST-2','成本二',30,40,TRUE)");
        long batchId = repository.create(ImportType.COST, "cost.xlsx", "api-cost-permission", List.of(
                row(Map.of("skuCode", "COST-1", "cost", new BigDecimal("100"), "factoryPrice", new BigDecimal("200"))),
                row(Map.of("skuCode", "COST-2", "cost", new BigDecimal("300"), "factoryPrice", new BigDecimal("400")))));

        mvc.perform(post("/api/imports/{batchId}/commit", batchId).cookie(loginAs("regular-user")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));

        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?", String.class, batchId)).isEqualTo("PREVIEW");
        assertThat(jdbc.queryForObject("SELECT committed_rows FROM import_batch WHERE id=?", Integer.class, batchId)).isZero();
        assertThat(jdbc.queryForList("SELECT current_cost FROM sku ORDER BY sku_code", BigDecimal.class))
                .containsExactly(new BigDecimal("10.0000"), new BigDecimal("30.0000"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku_cost_history", Integer.class)).isZero();

        mvc.perform(post("/api/imports/{batchId}/commit", batchId).cookie(loginAs("finance")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMMITTED"))
                .andExpect(jsonPath("$.data.committedRows").value(2));

        assertThat(jdbc.queryForList("SELECT current_cost FROM sku ORDER BY sku_code", BigDecimal.class))
                .containsExactly(new BigDecimal("100.0000"), new BigDecimal("300.0000"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku_cost_history", Integer.class)).isEqualTo(2);
    }

    @Test
    void regularUserCanStillCommitCustomerAndInventoryBatches() throws Exception {
        Cookie userSession = loginAs("regular-user");
        long customerBatch = repository.create(ImportType.CUSTOMER, "customer.xlsx", "api-customer-user", List.of(
                new ParsedImportRow("Sheet1", 1, ImportRowStatus.VALID, Map.of("customerName", "普通客户"), null)));
        long inventoryBatch = repository.create(ImportType.INVENTORY, "inventory.xlsx", "api-inventory-user", List.of(
                new ParsedImportRow("Sheet1", 1, ImportRowStatus.VALID, Map.of(
                        "skuCode", "INV-USER", "model", "P90", "actualQuantity", 1,
                        "lockedQuantity", 0, "inTransitQuantity", 0), null)));

        mvc.perform(post("/api/imports/{batchId}/commit", customerBatch).cookie(userSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMMITTED"));
        mvc.perform(post("/api/imports/{batchId}/commit", inventoryBatch).cookie(userSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMMITTED"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE customer_name='普通客户'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_balance", Integer.class)).isOne();
    }

    private Cookie loginAs(String username) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("OPS_SESSION");
    }

    private ParsedImportRow row(Map<String, Object> data) {
        return new ParsedImportRow("Sheet1", 1, ImportRowStatus.VALID, data, null);
    }
}
