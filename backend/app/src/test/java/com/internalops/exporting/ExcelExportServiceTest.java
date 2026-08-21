package com.internalops.exporting;

import com.internalops.aftersales.AfterSalesQueryService;
import com.internalops.workbench.ProcurementWorkflowService;
import com.internalops.workbench.SalesOrderCommandService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;

class ExcelExportServiceTest {
    @Test
    void purchaseSummaryUsesTheChineseTemplateColumnsAndChineseStatuses() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.ofEntries(
                Map.entry("purchase_no", "CG20260800001"), Map.entry("supplier_name", "示例供应商"), Map.entry("status", "EXECUTING"),
                Map.entry("product_code", "SKU-001"), Map.entry("customer_part_number", "CP-001"), Map.entry("product_name", "示例产品"),
                Map.entry("model", "D51"), Map.entry("quantity", 5), Map.entry("received_quantity", 0), Map.entry("in_transit_quantity", 5),
                Map.entry("purchase_price", new BigDecimal("390.00")), Map.entry("amount", new BigDecimal("1950.00")),
                Map.entry("expected_arrival_date", "2026-09-10"), Map.entry("order_date", "2026-08-20"), Map.entry("purchase_remark", "采购备注")
        )));
        ExcelExportService service = new ExcelExportService(null, null, null, null, jdbc);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(service.summary("purchase")))) {
            var sheet = workbook.getSheet("采购汇总数据");
            assertEquals("采购单号", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("要求交期", sheet.getRow(0).getCell(12).getStringCellValue());
            assertEquals("CG20260800001", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("执行中", sheet.getRow(1).getCell(2).getStringCellValue());
        }
    }

    @Test
    void productInventorySummaryIncludesBothInventoryAndProductRemarks() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.ofEntries(
                Map.entry("product_code", "BR_A71YZH60WPSE"), Map.entry("customer_part_number", "G8A71HS001"), Map.entry("model", "A71"),
                Map.entry("brand", "BRAVAT"), Map.entry("product_type", "SMART_LOCK"), Map.entry("material_type", "FINISHED_PRODUCT"),
                Map.entry("product_configuration", "可视对讲"), Map.entry("configuration", "YZH / 60"), Map.entry("unit", "件"),
                Map.entry("actual_quantity", 2), Map.entry("available_quantity", 2), Map.entry("locked_quantity", 0), Map.entry("in_transit_quantity", 0),
                Map.entry("pending_delivery_quantity", 0), Map.entry("supply_demand_surplus", 2), Map.entry("source_supplier_name", "示例供应商"),
                Map.entry("inventory_remark", "库存备注"), Map.entry("product_remark", "产品备注"), Map.entry("updated_at", "2026-08-20 09:46:41")
        )));
        ExcelExportService service = new ExcelExportService(null, null, null, null, jdbc);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(service.summary("inventory")))) {
            var sheet = workbook.getSheet("产品库存汇总数据");
            assertEquals("品牌", sheet.getRow(0).getCell(3).getStringCellValue());
            assertEquals("库存备注", sheet.getRow(0).getCell(16).getStringCellValue());
            assertEquals("产品备注", sheet.getRow(0).getCell(17).getStringCellValue());
            assertEquals("BRAVAT", sheet.getRow(1).getCell(3).getStringCellValue());
            assertEquals("产品备注", sheet.getRow(1).getCell(17).getStringCellValue());
        }
    }

    @Test
    void orderSummaryUsesChineseStatusAndDoesNotPopulateBlankShippingOrRemarks() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.ofEntries(
                Map.entry("business_type", "销售订单"), Map.entry("order_no", "DD20260800001"), Map.entry("order_date", "2026-08-20"),
                Map.entry("status", "WAITING_STOCK"), Map.entry("customer_name", "示例客户"), Map.entry("product_code", "SKU-001"),
                Map.entry("customer_part_number", "CP-001"), Map.entry("product_name", "示例产品"), Map.entry("model", "D51"),
                Map.entry("quantity", 1), Map.entry("shipped_quantity", 0), Map.entry("sale_price", BigDecimal.ZERO),
                Map.entry("amount", BigDecimal.ZERO), Map.entry("delivery_date", ""), Map.entry("salesperson", "Excel导入"), Map.entry("order_remark", "")
        )));
        ExcelExportService service = new ExcelExportService(null, null, null, null, jdbc);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(service.summary("order")))) {
            var row = workbook.getSheet("订单汇总数据").getRow(1);
            assertEquals("等待齐货", row.getCell(3).getStringCellValue());
            assertEquals("", row.getCell(13).getStringCellValue());
            assertEquals("", row.getCell(15).getStringCellValue());
        }
    }

    @Test
    void afterSalesSummaryUsesItsOwnChineseTemplateAndOnlyAfterSalesRows() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> afterSalesRow = new LinkedHashMap<>();
        afterSalesRow.put("business_type", "售后订单"); afterSalesRow.put("order_no", "SH20260820001"); afterSalesRow.put("order_date", "2026-08-20");
        afterSalesRow.put("status", "WAITING_RETURN"); afterSalesRow.put("customer_name", "示例客户"); afterSalesRow.put("product_code", "SKU-001");
        afterSalesRow.put("customer_part_number", "CP-001"); afterSalesRow.put("product_name", "示例产品"); afterSalesRow.put("model", "D51");
        afterSalesRow.put("quantity", 2); afterSalesRow.put("shipped_quantity", 0); afterSalesRow.put("sale_price", null);
        afterSalesRow.put("amount", null); afterSalesRow.put("delivery_date", null); afterSalesRow.put("salesperson", null); afterSalesRow.put("order_remark", "售后备注");
        when(jdbc.queryForList(anyString())).thenReturn(List.of(afterSalesRow));
        ExcelExportService service = new ExcelExportService(null, null, null, null, jdbc);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(service.summary("afterSales")))) {
            var sheet = workbook.getSheet("售后汇总数据");
            assertEquals("业务类型", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("售后订单", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("SH20260820001", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("等待退货", sheet.getRow(1).getCell(3).getStringCellValue());
            assertEquals("售后备注", sheet.getRow(1).getCell(15).getStringCellValue());
        }
        verify(jdbc).queryForList(argThat(sql -> sql.contains("FROM after_sales_order") && !sql.contains("FROM sales_order ")));
    }

    @Test
    void salesDocumentUsesConfirmedPrintTemplateAndUsesActualDetailRowCount() throws Exception {
        SalesOrderCommandService salesOrders = mock(SalesOrderCommandService.class);
        when(salesOrders.get(9L)).thenReturn(Map.of(
                "orderNo", "DD20260800001", "orderDate", "2026-08-20", "customerCode", "C0001",
                "customerName", "示例客户", "orderType", "工程订单", "deliveryContact", "张三",
                "deliveryPhone", "13800000000", "deliveryAddress", "珠海市斗门区示例地址",
                "remark", "示例备注", "items", List.of(Map.of("productCode", "SXSEL_D51YZH70WPSE-A",
                        "productName", "智能门锁", "model", "D51", "color", "宇宙黑", "quantity", 10,
                        "unit", "件", "salePrice", new BigDecimal("1038.80")))
        ));
        ExcelExportService service = new ExcelExportService(null, salesOrders, null, null, null);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(service.document("order", 9L)))) {
            var sheet = workbook.getSheet("销售订单单据");
            assertEquals("珠海吉门第销售订单", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("珠海市斗门区珠峰大道南3211号16号厂房3层  TEL：0755-86168089", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("客户编码：C0001", sheet.getRow(4).getCell(0).getStringCellValue());
            assertEquals("颜色", sheet.getRow(10).getCell(4).getStringCellValue());
            assertEquals("宇宙黑", sheet.getRow(11).getCell(4).getStringCellValue());
            assertEquals("订单备注：示例备注", sheet.getRow(12).getCell(0).getStringCellValue());
            assertEquals("销售/商务确认", sheet.getRow(16).getCell(0).getStringCellValue());
            assertEquals("签字/盖章：", sheet.getRow(16).getCell(7).getStringCellValue());
            assertEquals("付款方式", sheet.getRow(14).getCell(0).getStringCellValue());
            assertEquals("公司名称：珠海吉门第科技有限公司", sheet.getRow(14).getCell(3).getStringCellValue().split("\\n")[2]);
        }
    }

    @Test
    void purchaseDocumentUsesPurchaseTemplateAndUsesActualDetailRowCount() throws Exception {
        ProcurementWorkflowService procurement = mock(ProcurementWorkflowService.class);
        Map<String, Object> item = Map.of("productCode", "SKU-001", "customerPartNumber", "CP-001", "productName", "示例产品", "model", "D51",
                "color", "宇宙黑", "lockBody", "6068", "productVersion", "中文版", "quantity", 5, "unit", "件", "purchasePrice", new BigDecimal("390.00"));
        when(procurement.purchase(7L)).thenReturn(Map.of("purchaseNo", "CG20260800001", "orderDate", "2026-08-20", "supplierName", "示例供应商",
                "status", "EXECUTING", "expectedArrivalDate", "2026-09-10", "remark", "采购备注", "items", List.of(item)));
        ExcelExportService service = new ExcelExportService(null, null, procurement, null, null);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(service.document("purchase", 7L)))) {
            var sheet = workbook.getSheet("采购订单单据");
            assertEquals("珠海吉门第采购订单", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("珠海市斗门区珠峰大道南3211号16号厂房3层  TEL：0755-86168089", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("下单日期：2026-08-20", sheet.getRow(3).getCell(0).getStringCellValue());
            assertEquals("颜色", sheet.getRow(9).getCell(5).getStringCellValue());
            assertEquals("SKU-001", sheet.getRow(10).getCell(1).getStringCellValue());
            assertEquals("采购备注：采购备注", sheet.getRow(11).getCell(0).getStringCellValue());
        }
    }

    @Test
    void afterSalesDocumentUsesAfterSalesTemplateAndUsesActualDetailRowCount() throws Exception {
        AfterSalesQueryService afterSales = mock(AfterSalesQueryService.class);
        when(afterSales.get(3L)).thenReturn(Map.ofEntries(
                Map.entry("afterSalesNo", "SH20260820001"), Map.entry("applicationDate", "2026-08-20"),
                Map.entry("orderNo", "DD20260700001"), Map.entry("customerName", "示例客户"), Map.entry("afterSalesType", "换货"),
                Map.entry("status", "待收货"), Map.entry("issueDescription", "门锁异常"), Map.entry("contactName", "张三"),
                Map.entry("contactPhone", "13800000000"), Map.entry("remark", "处理备注"),
                Map.entry("returnLines", List.of(Map.of("productCode", "SKU-001", "customerPartNumber", "CP-001", "productName", "示例产品",
                        "model", "D51", "requestedQuantity", 2, "receivedQuantity", 0)))));
        ExcelExportService service = new ExcelExportService(null, null, null, afterSales, null);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(service.document("afterSales", 3L)))) {
            var sheet = workbook.getSheet("售后订单单据");
            assertEquals("珠海吉门第售后服务单", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("珠海市斗门区珠峰大道南3211号16号厂房3层  TEL：0755-86168089", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("申请数量", sheet.getRow(10).getCell(5).getStringCellValue());
            assertEquals("SKU-001", sheet.getRow(11).getCell(1).getStringCellValue());
            assertEquals("处理备注：处理备注", sheet.getRow(12).getCell(0).getStringCellValue());
        }
    }
}
