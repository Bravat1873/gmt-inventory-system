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
@Sql(scripts = "/workbench-query-schema.sql", config = @SqlConfig(encoding = "UTF-8"))
class WorkbenchQueryApiTest {
    @Autowired
    MockMvc mvc;

    @Test
    void fixesPageSizeAtTenAndReturnsSecondPage() throws Exception {
        mvc.perform(get("/api/workbench/customer").param("page", "1").param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.total").value(13))
                .andExpect(jsonPath("$.data.items.length()").value(10));

        mvc.perform(get("/api/workbench/customer").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3));
    }

    @Test
    void supportsKeywordAndWhitelistedSorting() throws Exception {
        mvc.perform(get("/api/workbench/customer").characterEncoding("UTF-8")
                        .param("keyword", "C01").param("sort", "customerName").param("direction", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].customerName").value("客户01"));

        mvc.perform(get("/api/workbench/customer")
                        .param("sort", "customerName").param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].customerName").value("客户13"));
    }

    @Test
    void rejectsUnknownSortFieldWithChineseMessage() throws Exception {
        mvc.perform(get("/api/workbench/customer").param("sort", "dropTable"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("不支持的排序字段"));
    }
}
