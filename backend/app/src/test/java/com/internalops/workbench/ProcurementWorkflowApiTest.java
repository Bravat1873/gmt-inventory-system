package com.internalops.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/procurement-workflow-schema.sql")
class ProcurementWorkflowApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createsDraftSuggestionAndOnlyCreatesMoqPurchaseAfterConfirmation() throws Exception {
        String generated = mvc.perform(post("/api/procurement/generate").contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.suggestionIds.length()").value(1))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = mapper.readTree(generated).path("data");
        long suggestionId = data.path("suggestionIds").get(0).asLong();
        assertThat(jdbc.queryForObject("SELECT status FROM procurement_suggestion WHERE id=?", String.class, suggestionId))
                .isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT suggested_quantity FROM procurement_suggestion_item WHERE suggestion_id=?", Integer.class, suggestionId))
                .isEqualTo(10);

        mvc.perform(post("/api/procurement/suggestions/{id}/confirm", suggestionId)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_SUPPLIER_PAYMENT"))
                .andExpect(jsonPath("$.data.purchaseId").isNumber());

        assertThat(jdbc.queryForObject("SELECT status FROM procurement_suggestion WHERE id=?", String.class, suggestionId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT quantity FROM purchase_order_item", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT in_transit_quantity FROM inventory_balance WHERE sku_id=101", Integer.class))
                .isEqualTo(10);
    }

    @Test
    void draftSuggestionRemainsVisibleInPurchaseWorkbenchUntilConfirmed() throws Exception {
        mvc.perform(post("/api/procurement/generate").contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/workbench/purchase")
                        .param("page", "1")
                        .param("keyword", "")
                        .param("sort", "")
                        .param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].recordType").value("SUGGESTION"))
                .andExpect(jsonPath("$.data.items[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.data.items[0].totalAmount").value(100.0));
    }
}
