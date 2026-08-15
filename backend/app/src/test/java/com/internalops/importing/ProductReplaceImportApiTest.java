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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.import.product-replace-enabled=true")
@AutoConfigureMockMvc
@Sql(scripts = {"/auth-schema.sql", "/import-schema.sql", "/product-replace-import-schema.sql"})
class ProductReplaceImportApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ImportBatchRepository repository;

    @BeforeEach
    void users() {
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,enabled,role) VALUES('finance','123','财务',TRUE,'FINANCE')");
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,enabled,role) VALUES('user','123','用户',TRUE,'USER')");
    }

    @Test
    void onlyAdminCanCommitProductReplacement() throws Exception {
        long financeBatch = batch("finance-batch");
        mvc.perform(post("/api/imports/{batchId}/commit", financeBatch).cookie(login("finance"))
                        .contentType("application/json").content("{\"productConflictActions\":{}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("仅管理员可执行产品全量替换"));

        long userBatch = batch("user-batch");
        mvc.perform(post("/api/imports/{batchId}/commit", userBatch).cookie(login("user"))
                        .contentType("application/json").content("{\"productConflictActions\":{}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("仅管理员可执行产品全量替换"));

        long adminBatch = batch("admin-batch");
        mvc.perform(post("/api/imports/{batchId}/commit", adminBatch).cookie(login("admin"))
                        .contentType("application/json").content("{\"productConflictActions\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMMITTED"));
    }

    private long batch(String hash) {
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("brand", "BRAVAT"); data.put("series", "P90"); data.put("bodyColor", "宇宙黑");
        data.put("lockType", "7068"); data.put("connectivity", "WiFi"); data.put("salesChannel", "平面");
        data.put("operatingEntity", "珠海"); data.put("language", "中文版"); data.put("codeSuffix", "A");
        data.put("productCode", "BR_P90YZH70WPZC-A");
        data.put("_ruleFingerprint", "BRAND:1|SERIES:2|BODY_COLOR:3|LOCK_TYPE:4|CONNECTIVITY:5|SALES_CHANNEL:6|OPERATING_ENTITY:7|LANGUAGE:8");
        data.put("model", "P90"); data.put("supplierName", "测试供应商"); data.put("supplierTaxPrice", "88.5");
        return repository.create(ImportType.PRODUCT, "products.xlsx", hash,
                List.of(new ParsedImportRow("GMT库存产品清单", 9, ImportRowStatus.VALID, data, null)));
    }

    private Cookie login(String username) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("OPS_SESSION");
    }
}
