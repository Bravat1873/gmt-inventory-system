package com.internalops.productcode;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = {"/auth-schema.sql", "/product-code-rule-api-schema.sql"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductCodeRuleApiTest {
    @Autowired MockMvc mvc;

    @Test
    void listsByCategoryAndReportsReferences() throws Exception {
        mvc.perform(get("/api/product-code-rules").cookie(login("admin"))
                        .param("category", "BRAND").param("includeDisabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("BR"))
                .andExpect(jsonPath("$.data[0].referenced").value(true))
                .andExpect(jsonPath("$.data[0].updatedAt").exists())
                .andExpect(jsonPath("$.data[1].code").value("OLD"));
    }

    @Test
    void ordinaryUserCanCreateAndCodesAreUppercase() throws Exception {
        mvc.perform(post("/api/product-code-rules").cookie(login("writer"))
                        .contentType("application/json")
                        .content("{\"category\":\"SERIES\",\"displayName\":\"P90\",\"code\":\" p90 \",\"enabled\":true,\"sortOrder\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("P90"));
    }

    @Test
    void rejectsInvalidAndDuplicateCodes() throws Exception {
        Cookie session = login("admin");
        mvc.perform(post("/api/product-code-rules").cookie(session).contentType("application/json")
                        .content("{\"category\":\"BRAND\",\"displayName\":\"重复\",\"code\":\"br\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/product-code-rules").cookie(session).contentType("application/json")
                        .content("{\"category\":\"SERIES\",\"displayName\":\"非法\",\"code\":\"P_90\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void referencedRuleCannotBeRecodedOrDeletedAndAlwaysRemainsEnabled() throws Exception {
        Cookie session = login("admin");
        mvc.perform(put("/api/product-code-rules/1").cookie(session).contentType("application/json")
                        .content("{\"category\":\"BRAND\",\"displayName\":\"STANLEY 新名称\",\"code\":\"BR\",\"enabled\":false,\"sortOrder\":5,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
        mvc.perform(put("/api/product-code-rules/1").cookie(session).contentType("application/json")
                        .content("{\"category\":\"BRAND\",\"displayName\":\"STANLEY\",\"code\":\"ST\",\"version\":1}"))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/product-code-rules/1").cookie(session))
                .andExpect(status().isConflict());
    }

    private Cookie login(String username) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("OPS_SESSION");
    }
}
