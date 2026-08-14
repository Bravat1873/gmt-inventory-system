package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc(addFilters = false)
@Sql(scripts="/sales-command-schema.sql")
class SalesOrderCommandApiTest {
    @Autowired MockMvc mvc;
    @SpyBean JdbcTemplate jdbc;
    @Test void listsEachSkuOnceWithPrimaryImageAndAggregatedInventory() throws Exception {
        jdbc.update("INSERT INTO warehouse VALUES(2,'SECOND','Second',FALSE,TRUE)");
        jdbc.update("INSERT INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,version) VALUES(1,1,10,3,2,0)");
        jdbc.update("INSERT INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,version) VALUES(2,1,5,1,4,0)");
        jdbc.update("INSERT INTO sales_order(order_no,customer_id,status,total_amount,order_date) VALUES('SO-DEMAND',1,'WAITING_STOCK',0,CURRENT_DATE)");
        jdbc.update("INSERT INTO sales_order_item(sales_order_id,line_no,sku_id,quantity,shipped_quantity,locked_quantity,uncovered_quantity,sale_price) SELECT id,10000,1,8,2,0,6,1 FROM sales_order WHERE order_no='SO-DEMAND'");
        jdbc.update("INSERT INTO product_image(id,product_id,is_primary,sort_order) VALUES(91,1,TRUE,0)");

        mvc.perform(get("/api/orders/skus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].primaryImageId").value(91))
                .andExpect(jsonPath("$.data[0].actualQuantity").value(10))
                .andExpect(jsonPath("$.data[0].availableQuantity").value(7))
                .andExpect(jsonPath("$.data[0].inTransitQuantity").value(2))
                .andExpect(jsonPath("$.data[0].pendingDeliveryQuantity").value(6))
                .andExpect(jsonPath("$.data[0].supplyDemandBalance").value(6))
                .andExpect(jsonPath("$.data[0].purchaseShortageQuantity").value(0))
                .andExpect(jsonPath("$.data[1].primaryImageId").doesNotExist())
                .andExpect(jsonPath("$.data[1].actualQuantity").value(0))
                .andExpect(jsonPath("$.data[1].availableQuantity").value(0))
                .andExpect(jsonPath("$.data[1].inTransitQuantity").value(0))
                .andExpect(jsonPath("$.data[1].pendingDeliveryQuantity").value(0))
                .andExpect(jsonPath("$.data[1].supplyDemandBalance").value(0));
    }
    @Test void manuallyReducesAnAutomaticAllocationAndReleasesInventory() throws Exception {
        jdbc.update("INSERT INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,version) VALUES(1,1,10,0,0,0)");
        String order="{\"customerId\":1,\"orderDate\":\"2026-08-06\",\"orderType\":\"工程订单\",\"status\":\"PENDING_CUSTOMER_PAYMENT\",\"salesperson\":\"Admin\",\"items\":[{\"lineNo\":10000,\"skuId\":1,\"quantity\":5,\"salePrice\":12.50}]}";
        String created=mvc.perform(post("/api/orders").contentType("application/json").content(order)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).path("data").path("id").asLong();
        String allocation=mvc.perform(get("/api/orders/{id}/allocations",id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].lockedQuantity").value(5)).andReturn().getResponse().getContentAsString();
        int version=new com.fasterxml.jackson.databind.ObjectMapper().readTree(allocation).path("data").path("version").asInt();

        mvc.perform(put("/api/orders/{id}/allocations",id).contentType("application/json")
                        .content("{\"version\":"+version+",\"items\":[{\"lineNo\":10000,\"lockedQuantity\":2}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("WAITING_STOCK"))
                .andExpect(jsonPath("$.data.items[0].lockedQuantity").value(2))
                .andExpect(jsonPath("$.data.items[0].uncoveredQuantity").value(3));
        org.junit.jupiter.api.Assertions.assertEquals(2,jdbc.queryForObject("SELECT locked_quantity FROM inventory_balance WHERE warehouse_id=1 AND sku_id=1",Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transaction WHERE business_no=? AND transaction_type='MANUAL_RELEASE'",Integer.class,String.valueOf(id)));
    }
    @Test void reallocatesOnlyTheUnshippedQuantityAfterAPartialShipment() throws Exception {
        jdbc.update("INSERT INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,version) VALUES(1,1,10,5,0,0)");
        jdbc.update("INSERT INTO sales_order(order_no,customer_id,status,total_amount,order_date,version) VALUES('SO-PARTIAL-ALLOCATE',1,'READY_TO_SHIP',0,CURRENT_DATE,0)");
        long id=jdbc.queryForObject("SELECT id FROM sales_order WHERE order_no='SO-PARTIAL-ALLOCATE'",Long.class);
        jdbc.update("INSERT INTO sales_order_item(sales_order_id,line_no,sku_id,quantity,shipped_quantity,locked_quantity,uncovered_quantity,sale_price) VALUES(?,10000,1,10,4,5,1,1)",id);

        mvc.perform(get("/api/orders/{id}/allocations",id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.adjustable").value(true));

        mvc.perform(put("/api/orders/{id}/allocations",id).contentType("application/json")
                        .content("{\"version\":0,\"items\":[{\"lineNo\":10000,\"lockedQuantity\":4}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].shippedQuantity").value(4))
                .andExpect(jsonPath("$.data.items[0].lockedQuantity").value(4))
                .andExpect(jsonPath("$.data.items[0].uncoveredQuantity").value(2));
        org.junit.jupiter.api.Assertions.assertEquals(4,jdbc.queryForObject("SELECT locked_quantity FROM inventory_balance WHERE warehouse_id=1 AND sku_id=1",Integer.class));
    }
    @Test void editsAnUnshippedLockedOrderAndReallocatesInventory() throws Exception {
        jdbc.update("INSERT INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,version) VALUES(1,1,10,0,0,0)");
        String order="{\"customerId\":1,\"orderDate\":\"2026-08-06\",\"orderType\":\"工程订单\",\"status\":\"PENDING_CUSTOMER_PAYMENT\",\"salesperson\":\"Admin\",\"items\":[{\"lineNo\":10000,\"skuId\":1,\"quantity\":5,\"salePrice\":12.50}]}";
        String created=mvc.perform(post("/api/orders").contentType("application/json").content(order)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var data=new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).path("data");
        long id=data.path("id").asLong(); int version=data.path("version").asInt();
        String changed=order.replace("\"quantity\":5", "\"quantity\":3").replace("}]}", "}],\"version\":"+version+"}");
        mvc.perform(put("/api/orders/{id}",id).contentType("application/json").content(changed))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY_TO_SHIP"))
                .andExpect(jsonPath("$.data.items[0].lockedQuantity").value(3));
        org.junit.jupiter.api.Assertions.assertEquals(3,jdbc.queryForObject("SELECT locked_quantity FROM inventory_balance WHERE warehouse_id=1 AND sku_id=1",Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transaction WHERE business_no=? AND transaction_type='ORDER_EDIT_RELEASE'",Integer.class,String.valueOf(id)));
    }    @Test void createsCalculatesAndFreezesPaidOrder() throws Exception {
        String json="{\"customerId\":1,\"externalOrderNo\":\"KH-001\",\"orderDate\":\"2026-08-06\",\"orderType\":\"工程订单\",\"status\":\"DRAFT\",\"salesperson\":\"Admin\",\"customerContact\":\"Zhang\",\"customerPhone\":\"13800138000\",\"remark\":\"Order note\",\"deliveryAddress\":\"Shenzhen\",\"deliveryContact\":\"Li\",\"deliveryPhone\":\"13900139000\",\"shippingMethod\":\"Logistics\",\"items\":[{\"skuId\":1,\"quantity\":2,\"salePrice\":12.50},{\"skuId\":2,\"quantity\":3,\"salePrice\":10}]}";
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
        String json="{\"customerId\":1,\"orderDate\":\"2026-08-06\",\"orderType\":\"工程订单\",\"status\":\"SHIPPED\",\"salesperson\":\"Admin\",\"items\":[{\"skuId\":1,\"quantity\":1,\"salePrice\":12.50}]}";
        mvc.perform(post("/api/orders").contentType("application/json").content(json))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sales_order", Integer.class));
    }

    @Test void exposesThreeCustomerContactsAndMapsNullValuesToEmptyStrings() throws Exception {
        jdbc.update("INSERT INTO customer(id,customer_code,customer_name,enabled) VALUES(2,'C2','Null Customer',TRUE)");

        mvc.perform(get("/api/orders/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].customerCode").value("C1"))
                .andExpect(jsonPath("$.data[0].businessContactName").value("Business Contact"))
                .andExpect(jsonPath("$.data[0].businessContactPhone").value("13700137000"))
                .andExpect(jsonPath("$.data[0].orderContactName").value("Order Contact"))
                .andExpect(jsonPath("$.data[0].orderContactPhone").value("13800138000"))
                .andExpect(jsonPath("$.data[0].financeContactName").value("Finance Contact"))
                .andExpect(jsonPath("$.data[0].financeContactPhone").value("13900139000"))
                .andExpect(jsonPath("$.data[0].address").value("Customer Address"))
                .andExpect(jsonPath("$.data[1].businessContactName").value(""))
                .andExpect(jsonPath("$.data[1].businessContactPhone").value(""))
                .andExpect(jsonPath("$.data[1].orderContactName").value(""))
                .andExpect(jsonPath("$.data[1].orderContactPhone").value(""))
                .andExpect(jsonPath("$.data[1].financeContactName").value(""))
                .andExpect(jsonPath("$.data[1].financeContactPhone").value(""))
                .andExpect(jsonPath("$.data[1].address").value(""));
    }

    @Test void createsAndUpdatesThreeContactSnapshotsWhileKeepingLegacyOrderContactFields() throws Exception {
        String createJson="""
                {"customerId":1,"orderDate":"2026-08-06","orderType":"\u5de5\u7a0b\u8ba2\u5355","status":"DRAFT","salesperson":"Admin",
                 "customerContact":"Legacy ignored","customerPhone":"10086",
                 "businessContactName":"Business One","businessContactPhone":"13100000001",
                 "orderContactName":"Order One","orderContactPhone":"13200000001",
                 "financeContactName":"Finance One","financeContactPhone":"13300000001",
                 "items":[{"skuId":1,"quantity":1,"salePrice":12.50}]}
                """;
        String response=mvc.perform(post("/api/orders").contentType("application/json").content(createJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businessContactName").value("Business One"))
                .andExpect(jsonPath("$.data.businessContactPhone").value("13100000001"))
                .andExpect(jsonPath("$.data.orderContactName").value("Order One"))
                .andExpect(jsonPath("$.data.orderContactPhone").value("13200000001"))
                .andExpect(jsonPath("$.data.financeContactName").value("Finance One"))
                .andExpect(jsonPath("$.data.financeContactPhone").value("13300000001"))
                .andExpect(jsonPath("$.data.customerContact").value("Order One"))
                .andExpect(jsonPath("$.data.customerPhone").value("13200000001"))
                .andReturn().getResponse().getContentAsString();
        long id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").path("id").asLong();

        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of("Business One","13100000001","Order One","13200000001","Finance One","13300000001","Order One","13200000001"),
                jdbc.queryForList("SELECT business_contact_name,business_contact_phone,order_contact_name,order_contact_phone,finance_contact_name,finance_contact_phone,customer_contact,customer_phone FROM sales_order WHERE id=?",id)
                        .get(0).values().stream().toList());

        String updateJson="""
                {"customerId":1,"orderDate":"2026-08-07","orderType":"\u5de5\u7a0b\u8ba2\u5355","status":"DRAFT","salesperson":"Admin","version":0,
                 "businessContactName":"Business Two","businessContactPhone":"13100000002",
                 "orderContactName":"Order Two","orderContactPhone":"13200000002",
                 "financeContactName":"Finance Two","financeContactPhone":"13300000002",
                 "items":[{"skuId":1,"quantity":1,"salePrice":12.50}]}
                """;
        mvc.perform(put("/api/orders/{id}",id).contentType("application/json").content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businessContactName").value("Business Two"))
                .andExpect(jsonPath("$.data.orderContactName").value("Order Two"))
                .andExpect(jsonPath("$.data.financeContactName").value("Finance Two"))
                .andExpect(jsonPath("$.data.customerContact").value("Order Two"))
                .andExpect(jsonPath("$.data.customerPhone").value("13200000002"));

        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of("Business Two","13100000002","Order Two","13200000002","Finance Two","13300000002","Order Two","13200000002"),
                jdbc.queryForList("SELECT business_contact_name,business_contact_phone,order_contact_name,order_contact_phone,finance_contact_name,finance_contact_phone,customer_contact,customer_phone FROM sales_order WHERE id=?",id)
                        .get(0).values().stream().toList());
    }

    @Test void fallsBackToLegacyOrderContactForRequestsAndOldRowsOnly() throws Exception {
        String json="""
                {"customerId":1,"orderDate":"2026-08-06","orderType":"\u5de5\u7a0b\u8ba2\u5355","status":"DRAFT","salesperson":"Admin",
                 "customerContact":"Legacy Order","customerPhone":"13900000000",
                 "items":[{"skuId":1,"quantity":1,"salePrice":12.50}]}
                """;
        String response=mvc.perform(post("/api/orders").contentType("application/json").content(json))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").path("id").asLong();
        org.junit.jupiter.api.Assertions.assertEquals("Legacy Order", jdbc.queryForObject("SELECT order_contact_name FROM sales_order WHERE id=?",String.class,id));
        org.junit.jupiter.api.Assertions.assertEquals("13900000000", jdbc.queryForObject("SELECT order_contact_phone FROM sales_order WHERE id=?",String.class,id));

        jdbc.update("UPDATE sales_order SET order_contact_name=NULL,order_contact_phone=NULL WHERE id=?",id);

        String detail=mvc.perform(get("/api/orders/{id}",id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderContactName").value("Legacy Order"))
                .andExpect(jsonPath("$.data.orderContactPhone").value("13900000000"))
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode data=new com.fasterxml.jackson.databind.ObjectMapper().readTree(detail).path("data");
        for (String key : java.util.List.of("businessContactName","businessContactPhone","financeContactName","financeContactPhone")) {
            org.junit.jupiter.api.Assertions.assertTrue(data.has(key), "旧行详情缺少键 " + key);
            org.junit.jupiter.api.Assertions.assertTrue(data.get(key).isNull(), "旧行详情的 " + key + " 应为 null");
        }
    }

    @Test void preservesLegacyCustomerPhoneUpToSixtyCharactersWhenNewPhoneIsMissing() throws Exception {
        String createPhone="1".repeat(60);
        String createJson="""
                {"customerId":1,"orderDate":"2026-08-06","orderType":"\u5de5\u7a0b\u8ba2\u5355","status":"DRAFT","salesperson":"Admin",
                 "customerContact":"Legacy Create","customerPhone":"%s",
                 "items":[{"skuId":1,"quantity":1,"salePrice":12.50}]}
                """.formatted(createPhone);
        org.springframework.test.web.servlet.MvcResult created=org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                mvc.perform(post("/api/orders").contentType("application/json").content(createJson))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.orderContactPhone").value(createPhone))
                        .andReturn());
        long id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();
        org.junit.jupiter.api.Assertions.assertEquals(createPhone,jdbc.queryForObject("SELECT order_contact_phone FROM sales_order WHERE id=?",String.class,id));

        String updatePhone="2".repeat(41);
        String updateJson="""
                {"customerId":1,"orderDate":"2026-08-07","orderType":"\u5de5\u7a0b\u8ba2\u5355","status":"DRAFT","salesperson":"Admin","version":0,
                 "customerContact":"Legacy Update","customerPhone":"%s",
                 "items":[{"skuId":1,"quantity":1,"salePrice":12.50}]}
                """.formatted(updatePhone);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                mvc.perform(put("/api/orders/{id}",id).contentType("application/json").content(updateJson))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.orderContactPhone").value(updatePhone))
                        .andReturn());
        org.junit.jupiter.api.Assertions.assertEquals(updatePhone,jdbc.queryForObject("SELECT order_contact_phone FROM sales_order WHERE id=?",String.class,id));
    }

    @Test void adjustsCumulativeShippedQuantityAndOnlyAppliesTheDifferenceToInventory() throws Exception {
        jdbc.update("INSERT INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,version) VALUES(1,1,10,0,0,0)");
        String order="{\"customerId\":1,\"orderDate\":\"2026-08-06\",\"orderType\":\"工程订单\",\"status\":\"PENDING_CUSTOMER_PAYMENT\",\"salesperson\":\"Admin\",\"items\":[{\"lineNo\":10000,\"skuId\":1,\"quantity\":10,\"salePrice\":12.50}]}";
        String created=mvc.perform(post("/api/orders").contentType("application/json").content(order)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).path("data").path("id").asLong();
        mvc.perform(put("/api/orders/{id}/shipment-quantities",id).contentType("application/json").content("{\"deliveryAddress\":\"Shenzhen\",\"items\":[{\"lineNo\":10000,\"shippedQuantity\":3}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY_TO_SHIP"));
        org.junit.jupiter.api.Assertions.assertEquals(7, jdbc.queryForObject("SELECT actual_quantity FROM inventory_balance WHERE warehouse_id=1 AND sku_id=1", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(7, jdbc.queryForObject("SELECT locked_quantity FROM inventory_balance WHERE warehouse_id=1 AND sku_id=1", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(7, jdbc.queryForObject("SELECT locked_quantity FROM sales_order_item WHERE sales_order_id=?", Integer.class, id));
        mvc.perform(put("/api/orders/{id}/shipment-quantities",id).contentType("application/json").content("{\"deliveryAddress\":\"Shenzhen\",\"items\":[{\"lineNo\":10000,\"shippedQuantity\":10}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SHIPPED"));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject("SELECT actual_quantity FROM inventory_balance WHERE warehouse_id=1 AND sku_id=1", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(10, jdbc.queryForObject("SELECT shipped_quantity FROM sales_order_item WHERE sales_order_id=?", Integer.class, id));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject("SELECT locked_quantity FROM sales_order_item WHERE sales_order_id=?", Integer.class, id));
    }

    @Test void rejectsBlankDeliveryAddressWhenShipmentIncreases() throws Exception {
        long id=createReadyOrder("", "[{\"lineNo\":10000,\"skuId\":1,\"quantity\":5,\"salePrice\":12.50}]");

        mvc.perform(put("/api/orders/{id}/shipment-quantities",id).contentType("application/json")
                        .content("{\"deliveryAddress\":\"   \",\"items\":[{\"lineNo\":10000,\"shippedQuantity\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("发货地址不能为空"));

        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sales_shipment", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject("SELECT shipped_quantity FROM sales_order_item WHERE sales_order_id=?", Integer.class, id));
    }

    @Test void rejectsUnicodeBlankDeliveryAddressWhenShipmentIncreases() throws Exception {
        long id=createReadyOrder("", "[{\"lineNo\":10000,\"skuId\":1,\"quantity\":5,\"salePrice\":12.50}]");

        mvc.perform(put("/api/orders/{id}/shipment-quantities",id).contentType("application/json")
                        .content("{\"deliveryAddress\":\"　\",\"items\":[{\"lineNo\":10000,\"shippedQuantity\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("发货地址不能为空"));

        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sales_shipment", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject("SELECT shipped_quantity FROM sales_order_item WHERE sales_order_id=?", Integer.class, id));
    }

    @Test void createsOneShipmentWithOnlyThePositiveDeltasForTwoLines() throws Exception {
        long id=createReadyOrder("", "[{\"lineNo\":10000,\"skuId\":1,\"quantity\":5,\"salePrice\":12.50},{\"lineNo\":20000,\"skuId\":2,\"quantity\":6,\"salePrice\":10}]");

        mvc.perform(put("/api/orders/{id}/shipment-quantities",id).contentType("application/json")
                        .content("{\"deliveryAddress\":\"  Shenzhen Nanshan  \",\"remark\":\" First batch \",\"items\":[{\"lineNo\":10000,\"shippedQuantity\":2},{\"lineNo\":20000,\"shippedQuantity\":3}]}"))
                .andExpect(status().isOk());

        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sales_shipment WHERE sales_order_id=?", Integer.class, id));
        org.junit.jupiter.api.Assertions.assertTrue(jdbc.queryForObject("SELECT shipment_no FROM sales_shipment WHERE sales_order_id=?", String.class, id).matches("SH\\d{8}[A-Z0-9]{12}"));
        org.junit.jupiter.api.Assertions.assertEquals("Shenzhen Nanshan", jdbc.queryForObject("SELECT delivery_address FROM sales_shipment WHERE sales_order_id=?", String.class, id));
        org.junit.jupiter.api.Assertions.assertEquals("First batch", jdbc.queryForObject("SELECT remark FROM sales_shipment WHERE sales_order_id=?", String.class, id));
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM sales_shipment_item si JOIN sales_shipment s ON s.id=si.sales_shipment_id WHERE s.sales_order_id=?", Integer.class, id));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(2,3), jdbc.queryForList("SELECT si.quantity FROM sales_shipment_item si JOIN sales_order_item oi ON oi.id=si.sales_order_item_id JOIN sales_shipment s ON s.id=si.sales_shipment_id WHERE s.sales_order_id=? ORDER BY oi.line_no", Integer.class, id));
    }

    @Test void storesCurrentOperatorAndReturnsItInShipmentHistory() throws Exception {
        long id=createReadyOrder("", "[{\"lineNo\":10000,\"skuId\":1,\"quantity\":5,\"salePrice\":12.50}]");
        com.internalops.auth.CurrentUser.set(new com.internalops.auth.CurrentUser(42L,"operator-user","Operator Zhang"));
        try {
            mvc.perform(put("/api/orders/{id}/shipment-quantities",id).contentType("application/json")
                            .content("{\"deliveryAddress\":\"Shenzhen\",\"items\":[{\"lineNo\":10000,\"shippedQuantity\":2}]}"))
                    .andExpect(status().isOk());
        } finally {
            com.internalops.auth.CurrentUser.clear();
        }

        mvc.perform(get("/api/orders/{id}",id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipments[0].operatorName").value("Operator Zhang"));
        org.junit.jupiter.api.Assertions.assertEquals("Operator Zhang",
                jdbc.queryForObject("SELECT operator_name FROM sales_shipment WHERE sales_order_id=?",String.class,id));
    }

    @Test void usesSystemOperatorWhenNoAuthenticatedUserExists() throws Exception {
        com.internalops.auth.CurrentUser.clear();
        long id=createReadyOrder("", "[{\"lineNo\":10000,\"skuId\":1,\"quantity\":5,\"salePrice\":12.50}]");

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                mvc.perform(put("/api/orders/{id}/shipment-quantities",id).contentType("application/json")
                                .content("{\"deliveryAddress\":\"Shenzhen\",\"items\":[{\"lineNo\":10000,\"shippedQuantity\":2}]}"))
                        .andExpect(status().isOk()));

        org.junit.jupiter.api.Assertions.assertEquals("system",
                jdbc.queryForObject("SELECT operator_name FROM sales_shipment WHERE sales_order_id=?",String.class,id));
        mvc.perform(get("/api/orders/{id}",id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipments[0].operatorName").value("system"));
    }

    @Test void rejectsDeliveryAddressLongerThanFiveHundredCharacters() throws Exception {
        long id=createReadyOrder("", "[{\"lineNo\":10000,\"skuId\":1,\"quantity\":5,\"salePrice\":12.50}]");

        mvc.perform(put("/api/orders/{id}/shipment-quantities",id).contentType("application/json")
                        .content("{\"deliveryAddress\":\""+"A".repeat(501)+"\",\"items\":[{\"lineNo\":10000,\"shippedQuantity\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("发货地址不能超过500个字符"));

        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sales_shipment", Integer.class));
    }

    @Test void pureDecreaseDoesNotCreateAnotherShipment() throws Exception {
        long id=createReadyOrder("", "[{\"lineNo\":10000,\"skuId\":1,\"quantity\":5,\"salePrice\":12.50}]");
        mvc.perform(put("/api/orders/{id}/shipment-quantities",id).contentType("application/json")
                        .content("{\"deliveryAddress\":\"Shenzhen\",\"items\":[{\"lineNo\":10000,\"shippedQuantity\":3}]}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/orders/{id}/shipment-quantities",id).contentType("application/json")
                        .content("{\"deliveryAddress\":\"\",\"items\":[{\"lineNo\":10000,\"shippedQuantity\":1}]}"))
                .andExpect(status().isOk());

        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sales_shipment WHERE sales_order_id=?", Integer.class, id));
        org.junit.jupiter.api.Assertions.assertEquals(9, jdbc.queryForObject("SELECT actual_quantity FROM inventory_balance WHERE warehouse_id=1 AND sku_id=1", Integer.class));
    }

    @Test void defaultShipmentAddressPrefersOrderAddressAndFallsBackToCustomer() throws Exception {
        long orderWithAddress=createReadyOrder("Order Address", "[{\"lineNo\":10000,\"skuId\":1,\"quantity\":1,\"salePrice\":12.50}]");
        long orderWithoutAddress=createReadyOrder("", "[{\"lineNo\":10000,\"skuId\":1,\"quantity\":1,\"salePrice\":12.50}]");

        mvc.perform(get("/api/orders/{id}",orderWithAddress)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultShipmentAddress").value("Order Address"));
        mvc.perform(get("/api/orders/{id}",orderWithoutAddress)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultShipmentAddress").value("Customer Address"));
    }

    @Test void defaultShipmentAddressIsEmptyStringWhenOrderAndCustomerAddressesAreBlank() throws Exception {
        jdbc.update("UPDATE customer SET address=NULL WHERE id=1");
        long id=createReadyOrder("", "[{\"lineNo\":10000,\"skuId\":1,\"quantity\":1,\"salePrice\":12.50}]");

        mvc.perform(get("/api/orders/{id}",id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultShipmentAddress").value(""));
    }

    @Test void returnsShipmentHistoryNewestFirstWithTotalsAndItems() throws Exception {
        long id=createReadyOrder("Order Address", "[{\"lineNo\":10000,\"skuId\":1,\"quantity\":5,\"salePrice\":12.50},{\"lineNo\":20000,\"skuId\":2,\"quantity\":6,\"salePrice\":10}]");
        long first=insertShipment(id,"SH20260807000001","Old Address","2026-08-07 09:00:00","old");
        long second=insertShipment(id,"SH20260807000002","New Address","2026-08-07 10:00:00","new");
        java.util.List<Long> itemIds=jdbc.queryForList("SELECT id FROM sales_order_item WHERE sales_order_id=? ORDER BY line_no", Long.class, id);
        jdbc.update("INSERT INTO sales_shipment_item(sales_shipment_id,sales_order_item_id,quantity) VALUES(?,?,?)",first,itemIds.get(0),1);
        jdbc.update("INSERT INTO sales_shipment_item(sales_shipment_id,sales_order_item_id,quantity) VALUES(?,?,?),(?,?,?)",second,itemIds.get(0),2,second,itemIds.get(1),3);

        mvc.perform(get("/api/orders/{id}",id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipments[0].shipmentNo").value("SH20260807000002"))
                .andExpect(jsonPath("$.data.shipments[0].deliveryAddress").value("New Address"))
                .andExpect(jsonPath("$.data.shipments[0].remark").value("new"))
                .andExpect(jsonPath("$.data.shipments[0].totalQuantity").value(5))
                .andExpect(jsonPath("$.data.shipments[0].items[0].lineNo").value(10000))
                .andExpect(jsonPath("$.data.shipments[0].items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.shipments[0].items[1].lineNo").value(20000))
                .andExpect(jsonPath("$.data.shipments[1].shipmentNo").value("SH20260807000001"))
                .andExpect(jsonPath("$.data.shipments[1].totalQuantity").value(1));
    }

    @Test void loadsAllShipmentItemsWithOneBatchQuery() throws Exception {
        long id=createReadyOrder("Order Address", "[{\"lineNo\":10000,\"skuId\":1,\"quantity\":5,\"salePrice\":12.50},{\"lineNo\":20000,\"skuId\":2,\"quantity\":6,\"salePrice\":10}]");
        long first=insertShipment(id,"SH20260807000011","Old Address","2026-08-07 09:00:00","old");
        long second=insertShipment(id,"SH20260807000012","New Address","2026-08-07 10:00:00","new");
        java.util.List<Long> itemIds=jdbc.queryForList("SELECT id FROM sales_order_item WHERE sales_order_id=? ORDER BY line_no",Long.class,id);
        jdbc.update("INSERT INTO sales_shipment_item(sales_shipment_id,sales_order_item_id,quantity) VALUES(?,?,?),(?,?,?)",first,itemIds.get(0),1,second,itemIds.get(1),2);
        org.mockito.Mockito.clearInvocations(jdbc);

        mvc.perform(get("/api/orders/{id}",id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipments[0].items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.shipments[1].items[0].quantity").value(1));

        long detailQueryCount=org.mockito.Mockito.mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("query"))
                .filter(invocation -> invocation.getArguments().length > 1
                        && invocation.getArgument(0) instanceof String sql
                        && invocation.getArgument(1) instanceof org.springframework.jdbc.core.RowMapper<?>
                        && sql.contains("FROM sales_shipment_item"))
                .count();
        org.junit.jupiter.api.Assertions.assertEquals(1,detailQueryCount);
    }

    @Test void rollsBackEarlierInventoryAndShipmentHeadWhenLaterShipmentItemFails() throws Exception {
        long id=createReadyOrder("", "[{\"lineNo\":10000,\"skuId\":1,\"quantity\":5,\"salePrice\":12.50},{\"lineNo\":20000,\"skuId\":2,\"quantity\":6,\"salePrice\":10}]");
        jdbc.execute("ALTER TABLE sales_shipment_item ADD CONSTRAINT ck_test_later_item_failure CHECK (quantity < 3)");

        jakarta.servlet.ServletException failure=org.junit.jupiter.api.Assertions.assertThrows(jakarta.servlet.ServletException.class,() ->
                mvc.perform(put("/api/orders/{id}/shipment-quantities",id).contentType("application/json")
                        .content("{\"deliveryAddress\":\"Shenzhen\",\"items\":[{\"lineNo\":10000,\"shippedQuantity\":2},{\"lineNo\":20000,\"shippedQuantity\":3}]}")));
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                org.springframework.dao.DataIntegrityViolationException.class,failure.getCause());

        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(10,10),
                jdbc.queryForList("SELECT actual_quantity FROM inventory_balance WHERE sku_id IN (1,2) ORDER BY sku_id",Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(5,6),
                jdbc.queryForList("SELECT locked_quantity FROM inventory_balance WHERE sku_id IN (1,2) ORDER BY sku_id",Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(0,0),
                jdbc.queryForList("SELECT shipped_quantity FROM sales_order_item WHERE sales_order_id=? ORDER BY line_no",Integer.class,id));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transaction WHERE transaction_type='SALES_SHIPMENT'",Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM sales_shipment WHERE sales_order_id=?",Integer.class,id));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM sales_shipment_item",Integer.class));
    }

    private long createReadyOrder(String deliveryAddress,String items) throws Exception {
        jdbc.update("MERGE INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,version) KEY(warehouse_id,sku_id) VALUES(1,1,10,0,0,0)");
        jdbc.update("MERGE INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,version) KEY(warehouse_id,sku_id) VALUES(1,2,10,0,0,0)");
        String order="{\"customerId\":1,\"orderDate\":\"2026-08-06\",\"orderType\":\"工程订单\",\"status\":\"PENDING_CUSTOMER_PAYMENT\",\"salesperson\":\"Admin\",\"deliveryAddress\":\""+deliveryAddress+"\",\"items\":"+items+"}";
        String created=mvc.perform(post("/api/orders").contentType("application/json").content(order)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).path("data").path("id").asLong();
    }

    private long insertShipment(long orderId,String shipmentNo,String address,String shippedAt,String remark) {
        org.springframework.jdbc.support.GeneratedKeyHolder keys=new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            java.sql.PreparedStatement statement=connection.prepareStatement("INSERT INTO sales_shipment(shipment_no,sales_order_id,delivery_address,operator_name,shipped_at,remark) VALUES(?,?,?,?,?,?)",new String[]{"id"});
            statement.setString(1,shipmentNo); statement.setLong(2,orderId); statement.setString(3,address); statement.setString(4,"seed"); statement.setTimestamp(5,java.sql.Timestamp.valueOf(shippedAt)); statement.setString(6,remark);
            return statement;
        },keys);
        return java.util.Objects.requireNonNull(keys.getKey()).longValue();
    }
    @Test void createsSalesOrderWithDdMonthlyNumber() throws Exception {
        String order="{\"customerId\":1,\"orderDate\":\"2026-08-14\",\"orderType\":\"工程订单\",\"status\":\"PENDING_CUSTOMER_PAYMENT\",\"salesperson\":\"Admin\",\"items\":[{\"lineNo\":10000,\"skuId\":1,\"quantity\":1,\"salePrice\":12.50}]}";
        mvc.perform(post("/api/orders").contentType("application/json").content(order)).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT order_no FROM sales_order ORDER BY id DESC LIMIT 1",String.class)).isEqualTo("DD20260800001");
    }    @Test void automaticallyCreatesQrWhenNewOrderHasPurchaseShortage() throws Exception {
        String order="{\"customerId\":1,\"orderDate\":\"2026-08-14\",\"orderType\":\"工程订单\",\"status\":\"PENDING_CUSTOMER_PAYMENT\",\"salesperson\":\"Admin\",\"items\":[{\"lineNo\":10000,\"skuId\":1,\"quantity\":5,\"salePrice\":12.50}]}";
        mvc.perform(post("/api/orders").contentType("application/json").content(order)).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM procurement_suggestion WHERE suggestion_no LIKE 'QR%'",Integer.class)).isEqualTo(1);
    }
}
