package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = {"/auth-schema.sql", "/supplier-management-schema.sql"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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

    @Test
    void supplierListExcludesDisabledSuppliersButHistoricalDetailRemainsAvailable() throws Exception {
        Cookie session = login();
        mvc.perform(get("/api/workbench/supplier").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(201));

        mvc.perform(get("/api/suppliers/202").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(202))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }
    @Test
    void createsSupplierAndPersistsTheSuppliedProductConfiguration() throws Exception {
        Cookie session = login();
        mvc.perform(post("/api/suppliers").cookie(session)
                        .contentType("application/json")
                        .content("""
                                {"supplierName":"新供应商","contactName":"李四","phone":"13800138001",
                                 "bankAccount":"6222-202","products":[{"skuId":101,"purchasePrice":230.5000,"moq":8,"leadTimeDays":12}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supplierName").value("新供应商"))
                .andExpect(jsonPath("$.data.products[0].skuId").value(101))
                .andExpect(jsonPath("$.data.products[0].purchasePrice").value(230.5))
                .andExpect(jsonPath("$.data.products[0].moq").value(8));
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("OPS_SESSION");
    }
}
