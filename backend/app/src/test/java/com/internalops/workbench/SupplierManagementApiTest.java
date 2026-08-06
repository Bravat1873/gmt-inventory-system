package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = {"/auth-schema.sql", "/supplier-management-schema.sql"})
class SupplierManagementApiTest {
    @Autowired MockMvc mvc;

    @Test
    void listsSuppliersByNameAndReturnsConfiguredProductsForPurchasing() throws Exception {
        Cookie session = login();
        mvc.perform(get("/api/workbench/supplier")
                        .cookie(session).param("page", "1").param("keyword", "贝朗").param("sort", "").param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].supplierName").value("贝朗供应商"))
                .andExpect(jsonPath("$.data.items[0].productCount").value(1));

        mvc.perform(get("/api/suppliers/201/products").cookie(session).param("keyword", "P90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].skuCode").value("P90-001"))
                .andExpect(jsonPath("$.data[0].purchasePrice").value(220))
                .andExpect(jsonPath("$.data[0].moq").value(5))
                .andExpect(jsonPath("$.data[0].leadTimeDays").value(7));
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("OPS_SESSION");
    }
}
