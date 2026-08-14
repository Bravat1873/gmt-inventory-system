package com.internalops.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = {"/auth-schema.sql", "/procurement-workflow-schema.sql"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProcurementWorkflowApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createsDraftSuggestionAndOnlyCreatesMoqPurchaseAfterConfirmation() throws Exception {
        Cookie session = login();
        String generated = mvc.perform(post("/api/procurement/generate").cookie(session).contentType("application/json").content("{}"))
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

        mvc.perform(post("/api/procurement/suggestions/{id}/confirm", suggestionId).cookie(session)
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
        Cookie session = login();
        mvc.perform(post("/api/procurement/generate").cookie(session).contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/workbench/purchase").cookie(session)
                        .param("page", "1")
                        .param("keyword", "")
                        .param("sort", "")
                        .param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].recordType").value("SUGGESTION"))
                .andExpect(jsonPath("$.data.items[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.data.items[0].productSummary").value("P90"))
                .andExpect(jsonPath("$.data.items[0].totalAmount").value(100.0));
    }

    @Test
    void manualPurchaseRejectsAProductThatIsNotConfiguredForTheSelectedSupplier() throws Exception {
        mvc.perform(post("/api/procurement/manual").cookie(login())
                        .contentType("application/json")
                        .content("""
                                {"supplierId":201,"skuId":102,"supplierPurchaseInfoId":1,"quantity":1,
                                 "expectedArrivalDate":"2026-08-20","remark":"关系校验"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("所选采购信息不属于该供应商和产品，或已停用"));
    }

    @Test
    void allowsMultiplePartialPaymentsUntilPurchaseIsSettled() throws Exception {
        Cookie session = login();
        long purchaseId = createManualPurchase(session, 10);

        mvc.perform(post("/api/procurement/purchases/{id}/payment", purchaseId).cookie(session)
                        .contentType("application/json")
                        .content("{\"amount\":30.00,\"paymentMethod\":\"银行转账\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paidAmount").value(30.0))
                .andExpect(jsonPath("$.data.outstandingAmount").value(70.0));

        mvc.perform(post("/api/procurement/purchases/{id}/payment", purchaseId).cookie(session)
                        .contentType("application/json")
                        .content("{\"amount\":70.00,\"paymentMethod\":\"银行转账\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paidAmount").value(100.0))
                .andExpect(jsonPath("$.data.outstandingAmount").value(0.0));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplier_payment WHERE purchase_order_id=?", Integer.class, purchaseId)).isEqualTo(2);
    }

    @Test
    void rejectsPaymentThatExceedsOutstandingAmount() throws Exception {
        Cookie session = login();
        long purchaseId = createManualPurchase(session, 10);
        mvc.perform(post("/api/procurement/purchases/{id}/payment", purchaseId).cookie(session)
                        .contentType("application/json").content("{\"amount\":80.00}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/procurement/purchases/{id}/payment", purchaseId).cookie(session)
                        .contentType("application/json").content("{\"amount\":21.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("本次付款金额不能超过未付金额"));
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM supplier_payment WHERE purchase_order_id=?", java.math.BigDecimal.class, purchaseId))
                .isEqualByComparingTo("80.00");
    }

    @Test
    void receivesPurchaseLineInMultipleIndependentInstallments() throws Exception {
        Cookie session = login();
        long purchaseId = createManualPurchase(session, 10);
        long itemId = jdbc.queryForObject("SELECT id FROM purchase_order_item WHERE purchase_order_id=?", Long.class, purchaseId);

        mvc.perform(post("/api/procurement/purchases/{id}/receive", purchaseId).cookie(session)
                        .contentType("application/json")
                        .content("{\"items\":[{\"purchaseOrderItemId\":" + itemId + ",\"receivedQuantity\":4}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receivedQuantity").value(4))
                .andExpect(jsonPath("$.data.remainingQuantity").value(6));
        assertThat(jdbc.queryForObject("SELECT actual_quantity FROM inventory_balance WHERE sku_id=101", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT in_transit_quantity FROM inventory_balance WHERE sku_id=101", Integer.class)).isEqualTo(6);

        mvc.perform(post("/api/procurement/purchases/{id}/receive", purchaseId).cookie(session)
                        .contentType("application/json")
                        .content("{\"items\":[{\"purchaseOrderItemId\":" + itemId + ",\"receivedQuantity\":6}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receivedQuantity").value(10))
                .andExpect(jsonPath("$.data.remainingQuantity").value(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM goods_receipt WHERE purchase_order_id=?", Integer.class, purchaseId)).isEqualTo(2);
    }

    @Test
    void regularUserReceivesStockWithoutChangingTheProductCost() throws Exception {
        Cookie session = loginAsRole("USER");
        jdbc.update("UPDATE sku SET current_cost=7.0000 WHERE id=101");
        long purchaseId = createManualPurchase(session, 10);
        long itemId = jdbc.queryForObject("SELECT id FROM purchase_order_item WHERE purchase_order_id=?", Long.class, purchaseId);

        mvc.perform(post("/api/procurement/purchases/{id}/receive", purchaseId).cookie(session)
                        .contentType("application/json")
                        .content("{\"items\":[{\"purchaseOrderItemId\":" + itemId + ",\"receivedQuantity\":4}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receivedQuantity").value(4))
                .andExpect(jsonPath("$.data.remainingQuantity").value(6));

        assertThat(jdbc.queryForObject("SELECT actual_quantity FROM inventory_balance WHERE sku_id=101", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT current_cost FROM sku WHERE id=101", java.math.BigDecimal.class)).isEqualByComparingTo("7.0000");
        assertThat(jdbc.queryForObject("SELECT version FROM sku WHERE id=101", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku_cost_history WHERE sku_id=101", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "FINANCE"})
    void financeAndAdminReceiptsUpdateTheProductCost(String role) throws Exception {
        Cookie session = loginAsRole(role);
        jdbc.update("UPDATE sku SET current_cost=7.0000 WHERE id=101");
        long purchaseId = createManualPurchase(session, 10);
        long itemId = jdbc.queryForObject("SELECT id FROM purchase_order_item WHERE purchase_order_id=?", Long.class, purchaseId);

        mvc.perform(post("/api/procurement/purchases/{id}/receive", purchaseId).cookie(session)
                        .contentType("application/json")
                        .content("{\"items\":[{\"purchaseOrderItemId\":" + itemId + ",\"receivedQuantity\":4}]}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT current_cost FROM sku WHERE id=101", java.math.BigDecimal.class)).isEqualByComparingTo("10.0000");
        assertThat(jdbc.queryForObject("SELECT version FROM sku WHERE id=101", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT old_cost,new_cost,source_type FROM sku_cost_history WHERE sku_id=101"))
                .containsEntry("old_cost", new java.math.BigDecimal("7.0000"))
                .containsEntry("new_cost", new java.math.BigDecimal("10.0000"))
                .containsEntry("source_type", "PURCHASE_RECEIPT");
    }

    @Test
    void rejectsZeroAndExcessReceiptWithoutChangingInventory() throws Exception {
        Cookie session = login();
        long purchaseId = createManualPurchase(session, 10);
        long itemId = jdbc.queryForObject("SELECT id FROM purchase_order_item WHERE purchase_order_id=?", Long.class, purchaseId);

        mvc.perform(post("/api/procurement/purchases/{id}/receive", purchaseId).cookie(session)
                        .contentType("application/json")
                        .content("{\"items\":[{\"purchaseOrderItemId\":" + itemId + ",\"receivedQuantity\":0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("本次至少填写一项实收数量"));
        mvc.perform(post("/api/procurement/purchases/{id}/receive", purchaseId).cookie(session)
                        .contentType("application/json")
                        .content("{\"items\":[{\"purchaseOrderItemId\":" + itemId + ",\"receivedQuantity\":11}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("本次实收数量不能超过剩余数量"));

        assertThat(jdbc.queryForObject("SELECT received_quantity FROM purchase_order_item WHERE id=?", Integer.class, itemId)).isZero();
        assertThat(jdbc.queryForObject("SELECT actual_quantity FROM inventory_balance WHERE sku_id=101", Integer.class)).isZero();
    }

    @Test
    void purchaseQueriesExposeIndependentPaymentAndReceiptProgress() throws Exception {
        Cookie session = login();
        long purchaseId = createManualPurchase(session, 10);
        long itemId = jdbc.queryForObject("SELECT id FROM purchase_order_item WHERE purchase_order_id=?", Long.class, purchaseId);
        mvc.perform(post("/api/procurement/purchases/{id}/payment", purchaseId).cookie(session)
                        .contentType("application/json").content("{\"amount\":25.00}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/procurement/purchases/{id}/receive", purchaseId).cookie(session)
                        .contentType("application/json")
                        .content("{\"items\":[{\"purchaseOrderItemId\":" + itemId + ",\"receivedQuantity\":4}]}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/workbench/purchase").cookie(session).param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].paidAmount").value(25.0))
                .andExpect(jsonPath("$.data.items[0].outstandingAmount").value(75.0))
                .andExpect(jsonPath("$.data.items[0].paymentStatus").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.data.items[0].orderedQuantity").value(10))
                .andExpect(jsonPath("$.data.items[0].receivedQuantity").value(4))
                .andExpect(jsonPath("$.data.items[0].remainingQuantity").value(6))
                .andExpect(jsonPath("$.data.items[0].receiptStatus").value("PARTIALLY_RECEIVED"));

        mvc.perform(get("/api/procurement/purchases/{id}", purchaseId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(purchaseId))
                .andExpect(jsonPath("$.data.items[0].id").value(itemId))
                .andExpect(jsonPath("$.data.items[0].quantity").value(10))
                .andExpect(jsonPath("$.data.items[0].receivedQuantity").value(4))
                .andExpect(jsonPath("$.data.items[0].remainingQuantity").value(6));
    }

    @Test
    void recommendsSupplierWithLowestEstimatedTotalInsteadOfLatestQuote() throws Exception {
        jdbc.update("INSERT INTO supplier(id,supplier_name,enabled) VALUES(202,'供应商二',TRUE)");
        jdbc.update("INSERT INTO sku_supplier_config(id,sku_id,supplier_id,purchase_price,moq,lead_time_days,enabled) VALUES(2,101,202,50.0000,3,2,TRUE)");
        jdbc.update("INSERT INTO sku_supplier_purchase_info(id,supplier_product_config_id,purchase_price,moq,lead_time_days,enabled,updated_at,version) VALUES(2,2,50.0000,3,2,TRUE,CURRENT_TIMESTAMP,0)");
        String body = mvc.perform(post("/api/procurement/generate").cookie(login()).contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long suggestionId = mapper.readTree(body).path("data").path("suggestionIds").get(0).asLong();
        assertThat(jdbc.queryForMap("SELECT supplier_id,suggested_quantity,purchase_price FROM procurement_suggestion_item WHERE suggestion_id=?", suggestionId))
                .containsEntry("supplier_id", 201L)
                .containsEntry("suggested_quantity", 10)
                .containsEntry("purchase_price", new java.math.BigDecimal("10.0000"));
    }
    @Test
    void createsManualPurchaseWithCgMonthlyNumber() throws Exception {
        createManualPurchase(login(), 10);
        assertThat(jdbc.queryForObject("SELECT purchase_no FROM purchase_order ORDER BY id DESC LIMIT 1", String.class))
                .matches("CG\\d{6}00001");
    }
    private long createManualPurchase(Cookie session, int quantity) throws Exception {
        String body = mvc.perform(post("/api/procurement/manual").cookie(session)
                        .contentType("application/json")
                        .content("{\"supplierId\":201,\"skuId\":101,\"supplierPurchaseInfoId\":1,\"quantity\":" + quantity + ",\"expectedArrivalDate\":\"2026-08-20\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("purchaseId").asLong();
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("OPS_SESSION");
    }

    private Cookie loginAsRole(String role) throws Exception {
        jdbc.update("UPDATE sys_user SET role=? WHERE username='admin'", role);
        return login();
    }
}
