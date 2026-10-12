package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessTraceServiceTest {
    @Test
    void rejectsUnknownBusinessTraceTypeBeforeQueryingData() {
        BusinessTraceService service = new BusinessTraceService(mock(JdbcTemplate.class));

        assertThrows(IllegalArgumentException.class, () -> service.trace("unknown", 1L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void purchaseTraceIncludesCurrentSalesHeaderAndLineRequirements() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of());
        when(jdbc.queryForList(contains("sp.supplier_name AS counterparty"), eq(7L)))
                .thenReturn(List.of(new LinkedHashMap<>(Map.of("purchaseNo", "CG001", "remark", "采购备注"))));
        when(jdbc.queryForList(contains("poi.line_no AS lineNo"), eq(7L)))
                .thenReturn(new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("skuId", 101L, "productCode", "SKU001")))));
        when(jdbc.queryForList(contains("o.delivery_contact,o.delivery_phone"), eq(7L)))
                .thenReturn(List.of(Map.of("order_no", "DD001", "sku_id", 101L, "line_no", 1,
                        "delivery_contact", "张三", "delivery_phone", "13800000000", "delivery_address", "广州",
                        "order_remark", "标签中性", "item_remark", "OA编号XS001")));
        Map<String, Object> trace = new BusinessTraceService(jdbc).trace("purchase", 7L);
        var header = (Map<String, Object>) trace.get("header");
        assertEquals("DD001：张三 / 13800000000", header.get("salesDeliveryContact"));
        assertEquals("DD001：广州", header.get("salesDeliveryAddress"));
        assertEquals("DD001：标签中性", header.get("salesOrderRemark"));
        assertEquals("采购备注", header.get("remark"));
        var details = (List<Map<String, Object>>) trace.get("details");
        assertEquals("DD001 第1行：OA编号XS001", details.get(0).get("remark"));
    }
}
