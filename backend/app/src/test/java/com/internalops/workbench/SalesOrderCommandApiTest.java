package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc(addFilters = false)
@Sql(scripts="/sales-command-schema.sql")
class SalesOrderCommandApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Test void createsCalculatesAndFreezesPaidOrder() throws Exception {
        String json="{\"customerId\":1,\"externalOrderNo\":\"KH-001\",\"orderDate\":\"2026-08-06\",\"orderType\":\"PROJECT\",\"status\":\"DRAFT\",\"salesperson\":\"Admin\",\"customerContact\":\"Zhang\",\"customerPhone\":\"13800138000\",\"remark\":\"Order note\",\"deliveryAddress\":\"Shenzhen\",\"deliveryContact\":\"Li\",\"deliveryPhone\":\"13900139000\",\"shippingMethod\":\"Logistics\",\"items\":[{\"skuId\":1,\"quantity\":2,\"salePrice\":12.50},{\"skuId\":2,\"quantity\":3,\"salePrice\":10}]}";
        String response=mvc.perform(post("/api/orders").contentType("application/json").content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalAmount").value(55.0))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        long id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").path("id").asLong();
        mvc.perform(get("/api/orders/{id}",id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderDate").value("2026-08-06"))
                .andExpect(jsonPath("$.data.salesperson").value("Admin"))
                .andExpect(jsonPath("$.data.deliveryAddress").value("Shenzhen"))
                .andExpect(jsonPath("$.data.items[0].lineNo").value(10000))
                .andExpect(jsonPath("$.data.items[0].shippedQuantity").value(0))
                .andExpect(jsonPath("$.data.items[0].remainingQuantity").value(2));
        mvc.perform(put("/api/orders/{id}",id).contentType("application/json").content(json.replace("KH-001","KH-002").replace("}]","}],\"version\":0}")))
                .andExpect(status().isOk());
        jdbc.update("UPDATE sales_order SET status='PENDING_SALES_INVOICE',version=version+1 WHERE id=?",id);
        mvc.perform(put("/api/orders/{id}",id).contentType("application/json").content(json.replace("}]","}],\"version\":1}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("确认收款后不可直接修改，只能走异常处理"));
    }

    @Test void refusesToCreateAnAlreadyShippedOrderBecauseShipmentMustUseTheOutboundWorkflow() throws Exception {
        String json="{\"customerId\":1,\"orderDate\":\"2026-08-06\",\"orderType\":\"PROJECT\",\"status\":\"SHIPPED\",\"salesperson\":\"Admin\",\"items\":[{\"skuId\":1,\"quantity\":1,\"salePrice\":12.50}]}";
        mvc.perform(post("/api/orders").contentType("application/json").content(json))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sales_order", Integer.class));
    }

    @Test void adjustsCumulativeShippedQuantityAndOnlyAppliesTheDifferenceToInventory() throws Exception {
        jdbc.update("INSERT INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,version) VALUES(1,1,10,0,0,0)");
        String order="{\"customerId\":1,\"orderDate\":\"2026-08-06\",\"orderType\":\"PROJECT\",\"status\":\"PENDING_CUSTOMER_PAYMENT\",\"salesperson\":\"Admin\",\"items\":[{\"lineNo\":10000,\"skuId\":1,\"quantity\":10,\"salePrice\":12.50}]}";
        String created=mvc.perform(post("/api/orders").contentType("application/json").content(order)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).path("data").path("id").asLong();
        mvc.perform(put("/api/orders/{id}/shipment-quantities",id).contentType("application/json").content("{\"items\":[{\"lineNo\":10000,\"shippedQuantity\":3}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY_TO_SHIP"));
        org.junit.jupiter.api.Assertions.assertEquals(7, jdbc.queryForObject("SELECT actual_quantity FROM inventory_balance WHERE warehouse_id=1 AND sku_id=1", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(7, jdbc.queryForObject("SELECT locked_quantity FROM inventory_balance WHERE warehouse_id=1 AND sku_id=1", Integer.class));
        mvc.perform(put("/api/orders/{id}/shipment-quantities",id).contentType("application/json").content("{\"items\":[{\"lineNo\":10000,\"shippedQuantity\":10}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SHIPPED"));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject("SELECT actual_quantity FROM inventory_balance WHERE warehouse_id=1 AND sku_id=1", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(10, jdbc.queryForObject("SELECT shipped_quantity FROM sales_order_item WHERE sales_order_id=?", Integer.class, id));
    }
}
