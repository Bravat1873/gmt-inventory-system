package com.internalops.exporting;

import com.internalops.aftersales.AfterSalesQueryService;
import com.internalops.workbench.ListQuery;
import com.internalops.workbench.ProcurementWorkflowService;
import com.internalops.workbench.SalesOrderCommandService;
import com.internalops.workbench.WorkbenchQueryService;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExcelExportService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String COMPANY_ADDRESS_AND_PHONE = "珠海市斗门区珠峰大道南3211号16号厂房3层  TEL：0755-86168089";
    private static final String SALES_PAYMENT_INSTRUCTIONS = "1、付款时间：订单确认无误后 3 个工作日内通过银行转账一次付清；\n"
            + "2、收款账户：\n"
            + "公司名称：珠海吉门第科技有限公司\n"
            + "开户银行：中国银行股份有限公司珠海斗门支行\n"
            + "账号：705570264475";
    private final WorkbenchQueryService workbench;
    private final SalesOrderCommandService salesOrders;
    private final ProcurementWorkflowService procurement;
    private final AfterSalesQueryService afterSales;
    private final JdbcTemplate jdbc;

    public ExcelExportService(WorkbenchQueryService workbench, SalesOrderCommandService salesOrders,
                              ProcurementWorkflowService procurement, AfterSalesQueryService afterSales, JdbcTemplate jdbc) {
        this.workbench = workbench;
        this.salesOrders = salesOrders;
        this.procurement = procurement;
        this.afterSales = afterSales;
        this.jdbc = jdbc;
    }

    public byte[] summary(String module) {
        validateModule(module);
        return switch (module) {
            case "order" -> orderSummaryWorkbook();
            case "afterSales" -> afterSalesSummaryWorkbook();
            case "purchase" -> purchaseSummaryWorkbook();
            case "product", "inventory" -> productInventorySummaryWorkbook();
            case "finance" -> financeSummaryWorkbook();
            default -> throw new IllegalArgumentException("该模块不支持 Excel 导出");
        };
    }

    private byte[] financeSummaryWorkbook() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT f.business_no,f.business_type,f.cash_direction,f.counterparty,f.amount,f.settled_amount,
                       GREATEST(f.amount-f.settled_amount,0) AS outstanding_amount,
                       CASE WHEN f.settled_amount>=f.amount THEN CASE WHEN f.cash_direction='RECEIVABLE' THEN '已收清' ELSE '已付清' END
                            WHEN f.cash_direction='RECEIVABLE' THEN '待收款' ELSE '待付款' END AS status,
                       f.created_at,f.updated_at
                FROM (
                    SELECT o.order_no AS business_no,'销售订单' AS business_type,'RECEIVABLE' AS cash_direction,c.customer_name AS counterparty,
                           COALESCE((SELECT SUM((i.quantity-i.shipped_quantity)*i.sale_price) FROM sales_order_item i WHERE i.sales_order_id=o.id),0) AS amount,
                           COALESCE((SELECT SUM(cr.amount) FROM customer_receipt cr WHERE cr.sales_order_id=o.id),0) AS settled_amount,
                           o.created_at,o.updated_at
                    FROM sales_order o
                    JOIN customer c ON c.id=o.customer_id
                    WHERE o.status<>'DRAFT'
                    UNION ALL
                    SELECT p.purchase_no,'采购订单','PAYABLE',sp.supplier_name,p.total_amount,
                           COALESCE((SELECT SUM(pay.amount) FROM supplier_payment pay WHERE pay.purchase_order_id=p.id),0),
                           p.created_at,p.updated_at
                    FROM purchase_order p
                    JOIN supplier sp ON sp.id=p.supplier_id
                ) f
                ORDER BY f.updated_at DESC,f.business_no
                """);
        List<Map<String, Object>> localized = rows.stream().map(row -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("业务单号", row.get("business_no")); out.put("业务类型", row.get("business_type"));
            out.put("收支方向", "RECEIVABLE".equals(row.get("cash_direction")) ? "应收" : "应付");
            out.put("往来单位", row.get("counterparty")); out.put("应收/应付", row.get("amount"));
            out.put("已收/已付", row.get("settled_amount")); out.put("未收/未付", row.get("outstanding_amount"));
            out.put("状态", row.get("status")); out.put("创建时间", row.get("created_at")); out.put("修改时间", row.get("updated_at"));
            return out;
        }).toList();
        return workbook("财务汇总数据", localized);
    }

    private byte[] orderSummaryWorkbook() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT '销售订单' AS business_type,o.order_no,o.order_date,o.status,c.customer_name,
                       s.product_code,s.customer_part_number,s.product_name,s.model,i.quantity,i.shipped_quantity,
                       i.sale_price,i.quantity*i.sale_price AS amount,DATE(o.shipped_at) AS delivery_date,
                       o.salesperson AS salesperson,o.order_remark AS order_remark
                FROM sales_order o
                JOIN customer c ON c.id=o.customer_id
                JOIN sales_order_item i ON i.sales_order_id=o.id
                JOIN sku s ON s.id=i.sku_id
                ORDER BY order_date,order_no
                """);
        return businessSummaryWorkbook("订单汇总数据", rows);
    }

    private byte[] afterSalesSummaryWorkbook() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT '售后订单' AS business_type,a.after_sales_no AS order_no,a.application_date AS order_date,a.status,c.customer_name,
                       s.product_code,s.customer_part_number,COALESCE(s.product_name,''),s.model,
                       r.requested_quantity AS quantity,r.received_quantity AS shipped_quantity,
                       NULL AS sale_price,NULL AS amount,NULL AS delivery_date,NULL AS salesperson,a.remark AS order_remark
                FROM after_sales_order a
                JOIN customer c ON c.id=a.customer_id
                JOIN after_sales_return_line r ON r.after_sales_order_id=a.id
                JOIN sku s ON s.id=r.sku_id
                ORDER BY order_date,order_no
                """);
        return businessSummaryWorkbook("售后汇总数据", rows);
    }

    private byte[] businessSummaryWorkbook(String sheetName, List<Map<String, Object>> rows) {
        List<Map<String, Object>> localized = rows.stream().map(row -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("业务类型", row.get("business_type")); out.put("单号", row.get("order_no")); out.put("订单日期", row.get("order_date"));
            out.put("状态", displayStatus(row.get("status"))); out.put("客户/供应商", row.get("customer_name")); out.put("产品编号", row.get("product_code"));
            out.put("客户料号", row.get("customer_part_number")); out.put("产品名称", row.get("product_name")); out.put("型号", row.get("model"));
            out.put("数量", row.get("quantity")); out.put("已处理数量", row.get("shipped_quantity")); out.put("含税单价", row.get("sale_price"));
            out.put("含税金额", row.get("amount")); out.put("交期/发货日期", row.get("delivery_date")); out.put("销售员/经办人", row.get("salesperson")); out.put("备注", row.get("order_remark"));
            return out;
        }).toList();
        return workbook(sheetName, localized);
    }

    private byte[] purchaseSummaryWorkbook() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT po.purchase_no,sp.supplier_name,po.status,s.product_code,s.customer_part_number,s.product_name,s.model,
                       poi.quantity,poi.received_quantity,GREATEST(poi.quantity-poi.received_quantity,0) AS in_transit_quantity,
                       poi.purchase_price,poi.quantity*poi.purchase_price AS amount,po.expected_arrival_date,
                       DATE(po.created_at) AS order_date,po.purchase_remark
                FROM purchase_order po
                JOIN supplier sp ON sp.id=po.supplier_id
                JOIN purchase_order_item poi ON poi.purchase_order_id=po.id
                JOIN sku s ON s.id=poi.sku_id
                UNION ALL
                SELECT ps.suggestion_no,sp.supplier_name,ps.status,s.product_code,s.customer_part_number,s.product_name,s.model,
                       psi.suggested_quantity,0,0,psi.purchase_price,psi.suggested_quantity*psi.purchase_price,
                       psi.expected_arrival_date,DATE(ps.created_at),ps.review_reason
                FROM procurement_suggestion ps
                JOIN procurement_suggestion_item psi ON psi.suggestion_id=ps.id
                JOIN supplier sp ON sp.id=psi.supplier_id
                JOIN sku s ON s.id=psi.sku_id
                WHERE ps.status='DRAFT'
                ORDER BY order_date,purchase_no
                """);
        List<Map<String, Object>> localized = rows.stream().map(row -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("采购单号", row.get("purchase_no")); out.put("供应商", row.get("supplier_name")); out.put("状态", displayStatus(row.get("status")));
            out.put("产品编号", row.get("product_code")); out.put("客户料号", row.get("customer_part_number")); out.put("产品名称", row.get("product_name"));
            out.put("型号", row.get("model")); out.put("采购数量", row.get("quantity")); out.put("已到货数量", row.get("received_quantity"));
            out.put("在途数量", row.get("in_transit_quantity")); out.put("含税单价", row.get("purchase_price")); out.put("含税金额", row.get("amount"));
            out.put("要求交期", row.get("expected_arrival_date")); out.put("下单日期", row.get("order_date")); out.put("备注", row.get("purchase_remark"));
            return out;
        }).toList();
        return workbook("采购汇总数据", localized);
    }

    private byte[] productInventorySummaryWorkbook() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.product_code,s.customer_part_number,s.model,brand.display_name AS brand,s.product_type,s.material_type,
                       s.product_configuration,s.configuration,s.unit,
                       COALESCE(SUM(balance.actual_quantity),0) AS actual_quantity,
                       COALESCE(SUM(balance.actual_quantity-balance.locked_quantity),0) AS available_quantity,
                       COALESCE(SUM(balance.locked_quantity),0) AS locked_quantity,
                       COALESCE(SUM(balance.in_transit_quantity),0) AS in_transit_quantity,
                       COALESCE((SELECT SUM(GREATEST(item.quantity-item.shipped_quantity,0))
                           FROM sales_order_item item JOIN sales_order orders ON orders.id=item.sales_order_id
                           WHERE orders.status<>'CANCELLED' AND item.sku_id=s.id),0) AS pending_delivery_quantity,
                       COALESCE(SUM(balance.actual_quantity),0)+COALESCE(SUM(balance.in_transit_quantity),0)-
                       COALESCE((SELECT SUM(GREATEST(item.quantity-item.shipped_quantity,0))
                           FROM sales_order_item item JOIN sales_order orders ON orders.id=item.sales_order_id
                           WHERE orders.status<>'CANCELLED' AND item.sku_id=s.id),0) AS supply_demand_surplus,
                       MAX(balance.source_supplier_name) AS source_supplier_name,MAX(balance.inventory_remark) AS inventory_remark,
                       s.product_remark,MAX(balance.updated_at) AS updated_at
                FROM sku s
                LEFT JOIN product_code_rule brand ON brand.id=s.brand_rule_id
                LEFT JOIN inventory_balance balance ON balance.sku_id=s.id
                WHERE s.enabled=TRUE
                GROUP BY s.id,s.product_code,s.customer_part_number,s.model,brand.display_name,s.product_type,s.material_type,
                         s.product_configuration,s.configuration,s.unit,s.product_remark
                ORDER BY s.product_code
                """);
        List<Map<String, Object>> localized = rows.stream().map(row -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("产品编号", row.get("product_code")); out.put("客户料号", row.get("customer_part_number")); out.put("型号", row.get("model"));
            out.put("品牌", row.get("brand")); out.put("产品分类", displayProductType(row.get("product_type"))); out.put("物料类型", displayMaterialType(row.get("material_type")));
            out.put("产品配置", row.get("product_configuration")); out.put("物料规格", row.get("configuration")); out.put("单位", row.get("unit"));
            out.put("实际库存数量", row.get("actual_quantity")); out.put("未锁定库存数量", row.get("available_quantity")); out.put("已锁定数量", row.get("locked_quantity"));
            out.put("在途数量", row.get("in_transit_quantity")); out.put("未发货数量", row.get("pending_delivery_quantity")); out.put("供需余量", row.get("supply_demand_surplus"));
            out.put("供应商", row.get("source_supplier_name")); out.put("库存备注", row.get("inventory_remark")); out.put("产品备注", row.get("product_remark")); out.put("修改时间", row.get("updated_at"));
            return out;
        }).toList();
        return workbook("产品库存汇总数据", localized);
    }

    public byte[] document(String module, long id) {
        Map<String, Object> record = switch (module) {
            case "order" -> salesOrders.get(id);
            case "purchase" -> procurement.purchase(id);
            case "afterSales" -> afterSales.get(id);
            default -> throw new IllegalArgumentException("该模块不支持单据导出");
        };
        return switch (module) {
            case "order" -> salesDocument(record);
            case "purchase" -> purchaseDocument(record);
            case "afterSales" -> afterSalesDocument(record);
            default -> throw new IllegalArgumentException("该模块不支持单据导出");
        };
    }

    public byte[] document(String module, long id, String type, Long shipmentId) {
        if ("order".equals(module) && "shipment".equals(type)) {
            if (shipmentId == null) throw new IllegalArgumentException("请选择需要导出的发货批次");
            return shipmentDocument(id, shipmentId);
        }
        return document(module, id);
    }

    public String documentFilename(String module, long id, String type, Long shipmentId) {
        if ("order".equals(module) && "shipment".equals(type) && shipmentId != null) {
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT shipment.shipment_no,customer.customer_name
                    FROM sales_shipment shipment
                    JOIN sales_order orders ON orders.id=shipment.sales_order_id
                    JOIN customer ON customer.id=orders.customer_id
                    WHERE shipment.id=? AND shipment.sales_order_id=?
                    """, shipmentId, id);
            return "销售出库单-" + string(row.get("shipment_no")) + "-" + string(row.get("customer_name")) + ".xlsx";
        }
        return module + "-单据-" + id + ".xlsx";
    }

    private byte[] shipmentDocument(long orderId, long shipmentId) {
        List<Map<String, Object>> headers = jdbc.queryForList("""
                SELECT shipment.shipment_no,shipment.delivery_address,shipment.operator_name,shipment.shipped_at,shipment.remark,
                       orders.order_no,orders.delivery_contact,orders.delivery_phone,customer.customer_name
                FROM sales_shipment shipment
                JOIN sales_order orders ON orders.id=shipment.sales_order_id
                JOIN customer ON customer.id=orders.customer_id
                WHERE shipment.id=? AND shipment.sales_order_id=?
                """, shipmentId, orderId);
        if (headers.isEmpty()) throw new IllegalArgumentException("发货批次不存在或不属于当前订单");
        Map<String, Object> header = headers.get(0);
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT sku.product_code,sku.product_type,sku.customer_part_number,sku.model,sku.unit,sku.configuration,
                       shipment_item.quantity
                FROM sales_shipment_item shipment_item
                JOIN sales_order_item order_item ON order_item.id=shipment_item.sales_order_item_id
                JOIN sku ON sku.id=order_item.sku_id
                WHERE shipment_item.sales_shipment_id=?
                ORDER BY order_item.line_no,shipment_item.id
                """, shipmentId);
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("销售出库单");
            sheet.setDisplayGridlines(false);
            CellStyle cell = style(workbook, false, HorizontalAlignment.LEFT);
            CellStyle center = style(workbook, false, HorizontalAlignment.CENTER);
            CellStyle headerStyle = style(workbook, true, HorizontalAlignment.CENTER);
            CellStyle title = style(workbook, true, HorizontalAlignment.CENTER);
            Font font = workbook.createFont(); font.setFontName("宋体"); font.setFontHeightInPoints((short) 18); font.setBold(true); title.setFont(font);
            int[] widths = {6, 19, 12, 20, 13, 8, 10, 34};
            for (int column = 0; column < widths.length; column++) sheet.setColumnWidth(column, widths[column] * 256);
            mergedRow(sheet, 0, 0, 7, "珠海吉门第科技有限公司", title, 28);
            mergedRow(sheet, 1, 0, 7, "销售出库单", title, 28);
            mergedRow(sheet, 2, 0, 3, "订单编号：" + string(header.get("order_no")), cell, 24);
            mergedRow(sheet, 2, 4, 7, "发货日期：" + string(header.get("shipped_at")), cell, 24);
            mergedRow(sheet, 3, 0, 3, "客户名称：" + string(header.get("customer_name")), cell, 24);
            mergedRow(sheet, 3, 4, 7, "出库单号：" + string(header.get("shipment_no")), cell, 24);
            String contact = firstNonBlank(string(header.get("delivery_contact")), "");
            String phone = firstNonBlank(string(header.get("delivery_phone")), "");
            mergedRow(sheet, 4, 0, 3, "客户联系人：" + (contact.isBlank() && phone.isBlank() ? "" : contact + " " + phone), cell, 40);
            mergedRow(sheet, 4, 4, 7, "收货地址：" + string(header.get("delivery_address")), cell, 40);
            String[] headings = {"序号", "产品编号", "产品分类", "客户料号", "型号", "单位", "数量", "物料规格"};
            for (int column = 0; column < headings.length; column++) set(sheet, 5, column, headings[column], headerStyle);
            sheet.getRow(5).setHeightInPoints(28);
            int row = 6;
            for (int index = 0; index < items.size(); index++, row++) {
                Map<String, Object> item = items.get(index);
                set(sheet, row, 0, index + 1, center); set(sheet, row, 1, string(item.get("product_code")), cell);
                set(sheet, row, 2, displayProductType(item.get("product_type")), cell); set(sheet, row, 3, string(item.get("customer_part_number")), cell);
                set(sheet, row, 4, string(item.get("model")), cell); set(sheet, row, 5, string(item.get("unit")), center);
                set(sheet, row, 6, item.get("quantity"), center); set(sheet, row, 7, string(item.get("configuration")), cell);
                sheet.getRow(row).setHeightInPoints(44);
            }
            mergedRow(sheet, row + 1, 0, 7, "说明：1、请收货方在收到货后现场检查，核对无误后签字确认。\n2、如对本单所列物品有异议，请在收货之日起三日内通知送货方。\n3、发货备注：" + string(header.get("remark")), cell, 72);
            mergedRow(sheet, row + 3, 0, 1, "制单人：" + string(header.get("operator_name")), cell, 24);
            mergedRow(sheet, row + 3, 2, 3, "发货人：", cell, 24);
            mergedRow(sheet, row + 3, 4, 5, "仓库：", cell, 24);
            mergedRow(sheet, row + 3, 6, 7, "收货人：", cell, 24);
            sheet.setFitToPage(true); sheet.getPrintSetup().setLandscape(true); workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) { throw new IllegalStateException("销售出库单模板生成失败", exception); }
    }

    /**
     * Sales documents intentionally use the confirmed print layout instead of the
     * generic document renderer.  These coordinates match the business template
     * and remain stable when blank detail lines are reserved for handwriting.
     */
    private byte[] salesDocument(Map<String, Object> record) {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("销售订单单据");
            sheet.setDisplayGridlines(false);
            CellStyle cell = style(workbook, false, HorizontalAlignment.LEFT);
            CellStyle center = style(workbook, false, HorizontalAlignment.CENTER);
            CellStyle header = style(workbook, true, HorizontalAlignment.CENTER);
            CellStyle titleStyle = style(workbook, true, HorizontalAlignment.CENTER);
            Font titleFont = workbook.createFont();
            titleFont.setFontName("宋体"); titleFont.setFontHeightInPoints((short) 18); titleFont.setBold(true);
            titleStyle.setFont(titleFont);
            CellStyle amount = style(workbook, false, HorizontalAlignment.RIGHT);
            amount.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));

            int[] widths = {6, 18, 12, 18, 30, 32, 11, 12, 14, 14, 18};
            for (int column = 0; column < widths.length; column++) sheet.setColumnWidth(column, widths[column] * 256);
            mergedRow(sheet, 0, 0, 10, "珠海吉门第销售订单", titleStyle, 34);
            mergedRow(sheet, 1, 0, 10, COMPANY_ADDRESS_AND_PHONE, center, 20);

            String contact = firstNonBlank(string(record.get("deliveryContact")), string(record.get("orderContactName")));
            String phone = firstNonBlank(string(record.get("deliveryPhone")), string(record.get("orderContactPhone")));
            String contactAndPhone = contact.isBlank() && phone.isBlank() ? "" : contact + " / " + phone;
            String[] meta = {
                    "订单编号：" + string(record.get("orderNo")),
                    "下单日期：" + string(record.get("orderDate")),
                    "客户编码：" + string(record.get("customerCode")),
                    "客户名称：" + string(record.get("customerName")),
                    "订单类型：" + string(record.get("orderType")),
                    "收货联系人及电话：" + contactAndPhone,
                    "收货地址：" + firstNonBlank(string(record.get("deliveryAddress")), string(record.get("defaultShipmentAddress")))
            };
            for (int index = 0; index < meta.length; index++) mergedRow(sheet, index + 2, 0, 10, meta[index], cell, index == 6 ? 30 : 23);
            sheet.createRow(9).setHeightInPoints(8);

            String[] headings = {"序号", "产品编号", "产品分类", "客户料号", "物料规格", "产品配置", "数量", "单位", "含税单价", "含税金额", "备注"};
            for (int column = 0; column < headings.length; column++) set(sheet, 10, column, headings[column], header);
            sheet.getRow(10).setHeightInPoints(28);

            List<Map<String, Object>> items = detailLines("order", record);
            int detailRows = Math.max(1, items.size());
            int row = 11;
            for (int index = 0; index < detailRows; index++) {
                Map<String, Object> item = index < items.size() ? items.get(index) : Map.of();
                boolean hasItem = index < items.size();
                set(sheet, row, 0, index + 1, center);
                set(sheet, row, 1, string(item.get("productCode")), cell);
                set(sheet, row, 2, displayProductType(item.get("productType")), cell);
                set(sheet, row, 3, string(item.get("customerPartNumber")), cell);
                set(sheet, row, 4, string(item.get("configuration")), cell);
                set(sheet, row, 5, string(item.get("productConfiguration")), cell);
                set(sheet, row, 6, hasItem ? item.get("quantity") : "", center);
                set(sheet, row, 7, string(item.get("unit")), center);
                set(sheet, row, 8, hasItem ? item.get("salePrice") : "", amount);
                if (hasItem) {
                    setFormula(sheet, row, 9, "G" + (row + 1) + "*I" + (row + 1), amount);
                } else set(sheet, row, 9, "", amount);
                set(sheet, row, 10, string(item.get("remark")), cell);
                sheet.getRow(row).setHeightInPoints(44);
                row++;
            }

            int totalRow = row;
            mergedRow(sheet, totalRow, 0, 8, "订单备注：" + string(record.get("remark")), cell, 26);
            setFormula(sheet, totalRow, 9, "SUM(J12:J" + totalRow + ")", amount);
            set(sheet, totalRow, 10, "合计金额", header);
            sheet.createRow(totalRow + 1).setHeightInPoints(8);
            int paymentRow = totalRow + 2;
            mergedRow(sheet, paymentRow, 0, 2, "付款方式", header, 82);
            mergedRow(sheet, paymentRow, 3, 10, SALES_PAYMENT_INSTRUCTIONS, cell, 82);

            int approvalRow = paymentRow + 2;
            for (String label : List.of("销售/商务确认", "客户确认", "营销总监审批", "财务审核")) {
                approvalBlock(sheet, approvalRow, label, 10, 7, 8, cell, header);
                approvalRow += 2;
            }
            sheet.setFitToPage(true);
            sheet.getPrintSetup().setLandscape(true);
            workbook.setForceFormulaRecalculation(true);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("销售订单单据模板生成失败", exception);
        }
    }

    private void approvalBlock(Sheet sheet, int row, String label, int lastColumn, int sealEnd, int promptColumn, CellStyle cell, CellStyle header) {
        fill(sheet, row, row + 1, 0, lastColumn, cell);
        sheet.addMergedRegion(new CellRangeAddress(row, row + 1, 0, 2));
        sheet.addMergedRegion(new CellRangeAddress(row, row + 1, 3, sealEnd));
        sheet.addMergedRegion(new CellRangeAddress(row, row, promptColumn + 1, lastColumn));
        sheet.addMergedRegion(new CellRangeAddress(row + 1, row + 1, promptColumn + 1, lastColumn));
        set(sheet, row, 0, label, header);
        set(sheet, row, promptColumn, "签字/盖章：", cell);
        set(sheet, row + 1, promptColumn, "日期：", cell);
        sheet.getRow(row).setHeightInPoints(34);
        sheet.getRow(row + 1).setHeightInPoints(34);
    }

    private void mergedRow(Sheet sheet, int row, int firstColumn, int lastColumn, String value, CellStyle style, int height) {
        fill(sheet, row, row, firstColumn, lastColumn, style);
        sheet.addMergedRegion(new CellRangeAddress(row, row, firstColumn, lastColumn));
        set(sheet, row, firstColumn, value, style);
        sheet.getRow(row).setHeightInPoints(height);
    }

    private void fill(Sheet sheet, int firstRow, int lastRow, int firstColumn, int lastColumn, CellStyle style) {
        for (int row = firstRow; row <= lastRow; row++) for (int column = firstColumn; column <= lastColumn; column++) set(sheet, row, column, "", style);
    }

    private void setFormula(Sheet sheet, int row, int column, String formula, CellStyle style) {
        var target = sheet.getRow(row) == null ? sheet.createRow(row) : sheet.getRow(row);
        var cell = target.getCell(column) == null ? target.createCell(column) : target.getCell(column);
        cell.setCellFormula(formula);
        cell.setCellStyle(style);
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? (fallback == null ? "" : fallback) : primary;
    }

    private String documentTitle(Object party, String suffix, String fallback) {
        String name = string(party).trim();
        return name.isBlank() ? fallback : name + suffix;
    }

    private String displayStatus(Object value) {
        return switch (string(value)) {
            case "DRAFT" -> "草稿";
            case "PENDING_SUPPLIER_PAYMENT" -> "待供应商付款";
            case "WAITING_STOCK" -> "等待齐货";
            case "READY_TO_SHIP" -> "等待发货";
            case "SHIPPED" -> "已发货";
            case "EXECUTING" -> "执行中";
            case "COMPLETED" -> "已完成";
            case "WAITING_RETURN" -> "等待退货";
            case "RETURN_RECEIVED" -> "已收到退货";
            case "WAITING_REPLACEMENT" -> "等待换货发出";
            case "CANCELLED" -> "已取消";
            default -> string(value);
        };
    }

    private String displayProductType(Object value) {
        return switch (string(value)) {
            case "SMART_LOCK" -> "智能锁";
            case "ENTRY_DOOR" -> "入户门";
            case "MECHANICAL_LOCK" -> "机械锁";
            case "ACCESSORY" -> "配件";
            case "UNCLASSIFIED" -> "未分类";
            default -> string(value);
        };
    }

    private String displayMaterialType(Object value) {
        return switch (string(value)) {
            case "FINISHED_PRODUCT" -> "成品";
            case "SEMI_FINISHED_PRODUCT" -> "半成品";
            case "RAW_MATERIAL" -> "原材料";
            case "PACKAGING_MATERIAL" -> "包装材料";
            default -> string(value);
        };
    }

    private String displayAfterSalesType(Object value) {
        return switch (string(value)) {
            case "EXCHANGE" -> "换货";
            case "RETURN" -> "退货";
            default -> string(value);
        };
    }

    private byte[] purchaseDocument(Map<String, Object> record) {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("采购订单单据"); sheet.setDisplayGridlines(false);
            CellStyle cell = style(workbook, false, HorizontalAlignment.LEFT);
            CellStyle center = style(workbook, false, HorizontalAlignment.CENTER);
            CellStyle header = style(workbook, true, HorizontalAlignment.CENTER);
            CellStyle amount = style(workbook, false, HorizontalAlignment.RIGHT);
            amount.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            CellStyle titleStyle = style(workbook, true, HorizontalAlignment.CENTER);
            Font titleFont = workbook.createFont(); titleFont.setFontName("宋体"); titleFont.setFontHeightInPoints((short) 18); titleFont.setBold(true); titleStyle.setFont(titleFont);
            int[] widths = {6,18,12,18,28,30,11,11,14,14,14,20};
            for (int column = 0; column < widths.length; column++) sheet.setColumnWidth(column, widths[column] * 256);
            mergedRow(sheet, 0, 0, 11, "珠海吉门第采购订单", titleStyle, 34);
            mergedRow(sheet, 1, 0, 11, COMPANY_ADDRESS_AND_PHONE, center, 20);
            String[] meta = {"采购单号：" + string(record.get("purchaseNo")), "下单日期：" + string(record.get("orderDate")),
                    "供应商：" + string(record.get("supplierName")), "联系人及电话：", "要求交期：" + string(record.get("expectedArrivalDate")), "交货地址：" + string(record.get("deliveryAddress"))};
            for (int index = 0; index < meta.length; index++) mergedRow(sheet, index + 2, 0, 11, meta[index], cell, index == 5 ? 30 : 23);
            sheet.createRow(8).setHeightInPoints(8);
            String[] headings = {"序号", "产品编号", "产品分类", "客户料号", "物料规格", "产品配置", "数量", "单位", "含税单价", "含税金额", "交期", "备注"};
            for (int column = 0; column < headings.length; column++) set(sheet, 9, column, headings[column], header);
            sheet.getRow(9).setHeightInPoints(28);
            List<Map<String, Object>> items = detailLines("purchase", record); int detailRows = Math.max(1, items.size()); int row = 10;
            for (int index = 0; index < detailRows; index++) {
                Map<String, Object> item = index < items.size() ? items.get(index) : Map.of(); boolean hasItem = index < items.size();
                set(sheet,row,0,index + 1,center); set(sheet,row,1,string(item.get("productCode")),cell); set(sheet,row,2,displayProductType(item.get("productType")),cell);
                set(sheet,row,3,string(item.get("customerPartNumber")),cell); set(sheet,row,4,string(item.get("configuration")),cell); set(sheet,row,5,string(item.get("productConfiguration")),cell); set(sheet,row,6,hasItem ? item.get("quantity") : "",center);
                set(sheet,row,7,string(item.get("unit")),center); set(sheet,row,8,hasItem ? item.get("purchasePrice") : "",amount);
                if (hasItem) setFormula(sheet,row,9,"G" + (row + 1) + "*I" + (row + 1),amount); else set(sheet,row,9,"",amount);
                set(sheet,row,10,string(record.get("expectedArrivalDate")),center); set(sheet,row,11,string(item.get("remark")),cell); sheet.getRow(row).setHeightInPoints(44);
                row++;
            }
            int totalRow = row; mergedRow(sheet,totalRow,0,8,"采购备注：" + string(record.get("remark")),cell,26);
            setFormula(sheet,totalRow,9,"SUM(J11:J" + totalRow + ")",amount); set(sheet,totalRow,10,"合计金额",header); set(sheet,totalRow,11,"",cell);
            sheet.createRow(totalRow + 1).setHeightInPoints(8); int noteRow = totalRow + 2;
            mergedRow(sheet,noteRow,0,11,"注意事项：供应商确认交期、随货文件与到货要求。",cell,38);
            int approvalRow = noteRow + 2; for (String label : List.of("采购确认", "供应商确认")) { approvalBlock(sheet, approvalRow, label, 11, 8, 9, cell, header); approvalRow += 2; }
            sheet.setFitToPage(true); sheet.getPrintSetup().setLandscape(true); workbook.setForceFormulaRecalculation(true); workbook.write(output); return output.toByteArray();
        } catch (IOException exception) { throw new IllegalStateException("采购订单单据模板生成失败", exception); }
    }

    private byte[] afterSalesDocument(Map<String, Object> record) {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("售后订单单据"); sheet.setDisplayGridlines(false);
            CellStyle cell = style(workbook, false, HorizontalAlignment.LEFT); CellStyle center = style(workbook, false, HorizontalAlignment.CENTER); CellStyle header = style(workbook, true, HorizontalAlignment.CENTER);
            CellStyle titleStyle = style(workbook, true, HorizontalAlignment.CENTER); Font titleFont = workbook.createFont(); titleFont.setFontName("宋体"); titleFont.setFontHeightInPoints((short) 18); titleFont.setBold(true); titleStyle.setFont(titleFont);
            int[] widths = {6,18,12,18,28,30,11,11,12,14,14,20}; for (int column = 0; column < widths.length; column++) sheet.setColumnWidth(column, widths[column] * 256);
            mergedRow(sheet,0,0,11,"珠海吉门第售后服务单",titleStyle,34);
            mergedRow(sheet,1,0,11,COMPANY_ADDRESS_AND_PHONE,center,20);
            String contact = string(record.get("contactName")); String phone = string(record.get("contactPhone"));
            String[] meta = {"售后单号：" + string(record.get("afterSalesNo")), "申请日期：" + string(record.get("applicationDate")), "关联订单：" + string(record.get("orderNo")),
                    "客户：" + string(record.get("customerName")), "售后类型：" + displayAfterSalesType(record.get("afterSalesType")), "收货联系人及电话：" + (contact.isBlank() && phone.isBlank() ? "" : contact + " / " + phone),
                    "问题描述：" + string(record.get("issueDescription"))};
            for (int index = 0; index < meta.length; index++) mergedRow(sheet,index + 2,0,11,meta[index],cell,index == 6 ? 32 : 23);
            sheet.createRow(9).setHeightInPoints(8);
            String[] headings = {"序号", "产品编号", "产品分类", "客户料号", "物料规格", "产品配置", "申请数量", "已处理数量", "处理方式", "物流单号", "处理状态", "备注"};
            for (int column = 0; column < headings.length; column++) set(sheet,10,column,headings[column],header); sheet.getRow(10).setHeightInPoints(28);
            List<Map<String, Object>> items = detailLines("afterSales", record); int detailRows = Math.max(1, items.size()); int row = 11;
            for (int index = 0; index < detailRows; index++) {
                Map<String, Object> item = index < items.size() ? items.get(index) : Map.of(); boolean hasItem = index < items.size();
                set(sheet,row,0,index+1,center); set(sheet,row,1,string(item.get("productCode")),cell); set(sheet,row,2,displayProductType(item.get("productType")),cell);
                set(sheet,row,3,string(item.get("customerPartNumber")),cell); set(sheet,row,4,string(item.get("configuration")),cell); set(sheet,row,5,string(item.get("productConfiguration")),cell); set(sheet,row,6,hasItem ? item.get("requestedQuantity") : "",center);
                set(sheet,row,7,hasItem ? item.get("receivedQuantity") : "",center); set(sheet,row,8,displayAfterSalesType(record.get("afterSalesType")),center); set(sheet,row,9,"",cell);
                set(sheet,row,10,displayStatus(record.get("status")),center); set(sheet,row,11,string(item.get("remark")),cell); sheet.getRow(row).setHeightInPoints(44);
                row++;
            }
            int noteRow = row; mergedRow(sheet,noteRow,0,11,"处理备注：" + string(record.get("remark")),cell,40); sheet.createRow(noteRow + 1).setHeightInPoints(8);
            int approvalRow = noteRow + 2; for (String label : List.of("申请人确认", "售后负责人确认", "客户确认")) { approvalBlock(sheet, approvalRow, label, 11, 8, 9, cell, header); approvalRow += 2; }
            sheet.setFitToPage(true); sheet.getPrintSetup().setLandscape(true); workbook.write(output); return output.toByteArray();
        } catch (IOException exception) { throw new IllegalStateException("售后订单单据模板生成失败", exception); }
    }

    private byte[] formalDocument(String module, Map<String, Object> record) {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            String title = switch (module) {
                case "order" -> documentTitle(record.get("customerName"), "销售订单", "珠海吉门第销售订单");
                case "purchase" -> documentTitle(record.get("supplierName"), "采购订单", "珠海吉门第采购订单");
                default -> documentTitle(record.get("customerName"), "售后服务单", "珠海吉门第售后服务单");
            };
            Sheet sheet = workbook.createSheet(title);
            CellStyle cell = style(workbook, false, HorizontalAlignment.LEFT);
            CellStyle center = style(workbook, false, HorizontalAlignment.CENTER);
            CellStyle header = style(workbook, true, HorizontalAlignment.CENTER);
            CellStyle titleStyle = style(workbook, true, HorizontalAlignment.CENTER);
            Font titleFont = workbook.createFont(); titleFont.setFontName("宋体"); titleFont.setFontHeightInPoints((short) 18); titleFont.setBold(true); titleStyle.setFont(titleFont);
            for (int column = 0; column < 10; column++) sheet.setColumnWidth(column, (column == 2 ? 25 : 14) * 256);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 9));
            set(sheet, 0, 0, title, titleStyle);
            sheet.getRow(0).setHeightInPoints(30);

            String documentNo = string(record.get(module.equals("order") ? "orderNo" : module.equals("purchase") ? "purchaseNo" : "afterSalesNo"));
            String counterparty = module.equals("purchase") ? string(record.get("supplierName")) : string(record.get("customerName"));
            String date = string(record.get(module.equals("afterSales") ? "applicationDate" : module.equals("purchase") ? "expectedArrivalDate" : "orderDate"));
            String[] meta = module.equals("order")
                    ? new String[]{"订单编号：" + documentNo, "下单日期：" + date, "客户：" + counterparty, "订单类型：" + string(record.get("orderType")), "收货联系人及电话：" + string(record.get("deliveryContact")) + " / " + string(record.get("deliveryPhone")), "收货地址：" + string(record.get("deliveryAddress"))}
                    : module.equals("purchase")
                    ? new String[]{"采购单号：" + documentNo, "要求交期：" + date, "供应商：" + counterparty, "订单状态：" + string(record.get("status")), "联系人及电话：", "交货地址："}
                    : new String[]{"售后单号：" + documentNo, "申请日期：" + date, "关联订单：" + string(record.get("orderNo")), "客户：" + counterparty, "售后类型：" + string(record.get("afterSalesType")), "问题描述：" + string(record.get("issueDescription"))};
            int row = 2;
            for (String value : meta) { sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(row, row, 0, 9)); set(sheet, row++, 0, value, cell); }
            row++;
            String[] columns = module.equals("order")
                    ? new String[]{"序号", "产品编号", "产品名称", "型号", "产品配置", "数量", "单位", "含税单价", "含税金额", "备注"}
                    : new String[]{"序号", "产品编号", "客户料号", "产品名称", "型号", "数量", "已处理数量", "单位", "状态", "备注"};
            for (int column = 0; column < columns.length; column++) set(sheet, row, column, columns[column], header);
            List<Map<String, Object>> items = detailLines(module, record);
            row++;
            int count = Math.max(1, items.size());
            for (int index = 0; index < count; index++, row++) {
                Map<String, Object> item = index < items.size() ? items.get(index) : Map.of();
                set(sheet, row, 0, index + 1, center); set(sheet, row, 1, string(item.get("productCode")), cell);
                set(sheet, row, 2, module.equals("order") ? string(item.get("productName")) : string(item.get("customerPartNumber")), cell);
                set(sheet, row, 3, module.equals("order") ? string(item.get("model")) : string(item.get("productName")), cell);
                set(sheet, row, 4, module.equals("order") ? string(item.get("configuration")) : string(item.get("model")), cell);
                set(sheet, row, 5, number(item.get("quantity")), center); set(sheet, row, 6, module.equals("order") ? string(item.get("unit")) : number(item.get("processedQuantity")), center);
                set(sheet, row, 7, module.equals("order") ? number(item.get("salePrice")) : string(item.get("unit")), center);
                set(sheet, row, 8, module.equals("order") ? number(item.get("quantity")) * number(item.get("salePrice")) : string(item.get("status")), center);
                set(sheet, row, 9, string(item.get("remark")), cell);
            }
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(row, row, 0, 7));
            set(sheet, row, 0, "备注：" + string(record.get("remark")), cell);
            set(sheet, row, 8, module.equals("order") ? "合计金额" : "", header);
            set(sheet, row, 9, module.equals("order") ? number(record.get("totalAmount")) : "", center);
            row += 2;
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(row, row, 0, 9));
            set(sheet, row++, 0, "付款方式 / 其他说明：", cell);
            String[] approvals = module.equals("order") ? new String[]{"销售/商务确认", "客户确认", "营销总监审批", "财务审核"} : module.equals("purchase") ? new String[]{"采购确认", "供应商确认"} : new String[]{"申请人确认", "售后负责人确认", "客户确认"};
            for (String approval : approvals) {
                sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(row, row + 1, 0, 3));
                sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(row, row + 1, 4, 6));
                sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(row, row, 8, 9));
                sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(row + 1, row + 1, 8, 9));
                set(sheet, row, 0, approval, header); set(sheet, row, 4, "", cell); set(sheet, row, 7, "签字/盖章：", cell); set(sheet, row + 1, 7, "日期：", cell);
                set(sheet, row, 8, "", cell); set(sheet, row + 1, 8, "", cell); row += 2;
            }
            for (int r = 0; r <= row; r++) for (int c = 0; c < 10; c++) if (sheet.getRow(r) != null && sheet.getRow(r).getCell(c) != null) sheet.getRow(r).getCell(c).setCellStyle(sheet.getRow(r).getCell(c).getCellStyle());
            workbook.write(output); return output.toByteArray();
        } catch (IOException exception) { throw new IllegalStateException("单据模板生成失败", exception); }
    }

    private List<Map<String, Object>> detailLines(String module, Map<String, Object> record) {
        Object raw = record.get(module.equals("afterSales") ? "returnLines" : "items");
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Object value : list) if (value instanceof Map<?, ?> map) { Map<String, Object> line = new LinkedHashMap<>(); map.forEach((key, item) -> line.put(String.valueOf(key), item)); lines.add(line); }
        return lines;
    }

    private CellStyle style(XSSFWorkbook workbook, boolean bold, HorizontalAlignment alignment) {
        CellStyle style = workbook.createCellStyle(); style.setBorderTop(BorderStyle.THIN); style.setBorderBottom(BorderStyle.THIN); style.setBorderLeft(BorderStyle.THIN); style.setBorderRight(BorderStyle.THIN); style.setAlignment(alignment); style.setVerticalAlignment(VerticalAlignment.CENTER); style.setWrapText(true); Font font = workbook.createFont(); font.setFontName("宋体"); font.setFontHeightInPoints((short) 10); font.setBold(bold); style.setFont(font); return style;
    }

    private void set(Sheet sheet, int row, int column, Object value, CellStyle style) { var target = sheet.getRow(row) == null ? sheet.createRow(row) : sheet.getRow(row); var cell = target.getCell(column) == null ? target.createCell(column) : target.getCell(column); if (value instanceof Number number) cell.setCellValue(number.doubleValue()); else cell.setCellValue(String.valueOf(value == null ? "" : value)); cell.setCellStyle(style); }
    private String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private double number(Object value) { return value instanceof Number number ? number.doubleValue() : 0; }

    private List<Map<String, Object>> localizedSummary(String module, List<Map<String, Object>> rows) {
        return rows.stream().map(row -> localized(row, summaryKeys(module), module)).toList();
    }

    private List<Map<String, Object>> localizedDocument(String module, Map<String, Object> record) {
        Map<String, Object> header = localized(record, documentKeys(module), module);
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(header);
        Object items = record.get("items");
        if (items instanceof List<?> list) {
            for (Object item : list) if (item instanceof Map<?, ?> raw) {
                Map<String, Object> detail = new LinkedHashMap<>();
                raw.forEach((key, value) -> detail.put(String.valueOf(key), value));
                result.add(localized(detail, List.of("lineNo", "productCode", "customerPartNumber", "productName", "model", "quantity", "shippedQuantity", "remainingQuantity", "salePrice", "unit"), module));
            }
        }
        return result;
    }

    private Map<String, Object> localized(Map<String, Object> source, List<String> keys) {
        return localized(source, keys, "");
    }

    private Map<String, Object> localized(Map<String, Object> source, List<String> keys, String module) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : keys) if (source.containsKey(key)) {
            String column = "product".equals(module) && "remark".equals(key) ? "产品备注" : label(key);
            result.put(column, source.get(key));
        }
        return result;
    }

    private List<String> summaryKeys(String module) {
        return switch (module) {
            case "purchase" -> List.of("purchaseNo", "supplierName", "productSummary", "orderedQuantity", "receivedQuantity", "remainingQuantity", "totalAmount", "paymentStatus", "receiptStatus", "status", "expectedArrivalDate", "updatedAt");
            case "afterSales" -> List.of("afterSalesNo", "orderNo", "customerName", "orderType", "afterSalesType", "returnQuantity", "replacementQuantity", "status", "applicationDate", "updatedAt");
            case "product" -> List.of("productCode", "customerPartNumber", "model", "brand", "productType", "materialType", "productConfiguration", "configuration", "unit", "actualQuantity", "lockedQuantity", "inTransitQuantity", "sourceSupplierName", "inventoryRemark", "remark", "updatedAt");
            case "inventory" -> List.of("productCode", "customerPartNumber", "model", "productType", "productConfiguration", "configuration", "unit", "actualQuantity", "availableQuantity", "lockedQuantity", "inTransitQuantity", "pendingDeliveryQuantity", "supplyDemandSurplus", "sourceSupplierName", "inventoryRemark", "updatedAt");
            default -> List.of();
        };
    }

    private List<String> documentKeys(String module) {
        return switch (module) {
            case "order" -> List.of("orderNo", "orderDate", "orderType", "status", "salesperson", "orderContactName", "orderContactPhone", "deliveryAddress", "deliveryContact", "deliveryPhone", "shippingMethod", "remark", "totalAmount");
            case "purchase" -> List.of("purchaseNo", "supplierName", "status", "expectedArrivalDate", "totalAmount");
            case "afterSales" -> List.of("afterSalesNo", "orderNo", "customerName", "afterSalesType", "status", "applicationDate", "remark");
            default -> List.of();
        };
    }

    private byte[] workbook(String sheetName, List<Map<String, Object>> rows) {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName.length() > 31 ? sheetName.substring(0, 31) : sheetName);
            List<String> keys = rows.stream().flatMap(row -> row.entrySet().stream())
                    .filter(entry -> !(entry.getValue() instanceof List<?>) && !(entry.getValue() instanceof Map<?, ?>))
                    .map(Map.Entry::getKey).distinct().toList();
            var header = sheet.createRow(0);
            for (int column = 0; column < keys.size(); column++) {
                header.createCell(column).setCellValue(label(keys.get(column)));
                sheet.setColumnWidth(column, 20 * 256);
            }
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                var row = sheet.createRow(rowIndex + 1);
                for (int column = 0; column < keys.size(); column++) {
                    row.createCell(column).setCellValue(value(rows.get(rowIndex).get(keys.get(column))));
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Excel 导出失败", exception);
        }
    }

    private String value(Object raw) {
        if (raw == null) return "";
        if (raw instanceof LocalDateTime time) return TIME.format(time);
        if (raw instanceof List<?> || raw instanceof Map<?, ?>) return String.valueOf(raw);
        return String.valueOf(raw);
    }

    private void validateModule(String module) {
        if (!List.of("order", "purchase", "afterSales", "product", "inventory", "finance").contains(module)) {
            throw new IllegalArgumentException("该模块不支持 Excel 导出");
        }
    }

    private String moduleLabel(String module) {
        return Map.of("order", "销售订单", "purchase", "采购", "afterSales", "售后订单", "product", "产品", "inventory", "产品库存", "finance", "财务").get(module);
    }

    private String label(String key) {
        if (key.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)) return key;
        return Map.ofEntries(
                Map.entry("id", "记录编号"), Map.entry("lineNo", "行号"), Map.entry("recordType", "单据类型"),
                Map.entry("orderNo", "订单编号"), Map.entry("purchaseNo", "采购单号"), Map.entry("afterSalesNo", "售后单号"),
                Map.entry("customerName", "客户"), Map.entry("supplierName", "供应商"), Map.entry("productCode", "产品编号"),
                Map.entry("customerPartNumber", "客户料号"), Map.entry("productName", "产品名称"), Map.entry("actualQuantity", "实际库存数量"),
                Map.entry("availableQuantity", "未锁定库存数量"), Map.entry("inTransitQuantity", "在途数量"), Map.entry("totalAmount", "金额"),
                Map.entry("status", "状态"), Map.entry("orderDate", "订单日期"), Map.entry("updatedAt", "修改时间"),
                Map.entry("customerCode", "客户编码"), Map.entry("orderType", "订单类型"), Map.entry("salesperson", "销售员"),
                Map.entry("applicationDate", "申请日期"), Map.entry("afterSalesType", "售后类型"), Map.entry("returnQuantity", "退回数量"),
                Map.entry("replacementQuantity", "换出数量"), Map.entry("expectedArrivalDate", "预计到货日期"),
                Map.entry("paymentStatus", "付款进度"), Map.entry("receiptStatus", "收货进度"), Map.entry("orderedQuantity", "采购数量"),
                Map.entry("receivedQuantity", "已到货数量"), Map.entry("remainingQuantity", "未到货数量"), Map.entry("productSummary", "产品"),
                Map.entry("model", "型号"), Map.entry("brand", "品牌"), Map.entry("productType", "产品分类"), Map.entry("materialType", "物料类型"),
                Map.entry("productConfiguration", "产品配置"), Map.entry("configuration", "物料规格"), Map.entry("unit", "单位"),
                Map.entry("lockedQuantity", "已锁定数量"), Map.entry("pendingDeliveryQuantity", "全局未发货数量"),
                Map.entry("supplyDemandSurplus", "供需余量"), Map.entry("sourceSupplierName", "库存供应商"), Map.entry("inventoryRemark", "库存备注"),
                Map.entry("remark", "备注"), Map.entry("createdAt", "创建时间"), Map.entry("externalOrderNo", "外部订单号")
        ).getOrDefault(key, "其他信息");
    }
}
