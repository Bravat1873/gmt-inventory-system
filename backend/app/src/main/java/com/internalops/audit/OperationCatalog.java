package com.internalops.audit;

import java.util.*;

final class OperationCatalog {
    private OperationCatalog() { }
    private static final Map<String,String> TABLES = new LinkedHashMap<>();
    static {
        group("客户管理", "customer", "customer_contact", "customer_contract", "customer_contract_price");
        group("用户管理", "sys_user");
        group("产品管理", "sku", "product_code_rule", "product_image", "sku_cost_history");
        group("供应商管理", "supplier", "sku_supplier_config", "sku_supplier_config_history", "sku_supplier_purchase_info");
        group("订单管理", "sales_order", "sales_order_item", "sales_shipment", "sales_shipment_item", "exception_case");
        group("库存管理", "warehouse", "inventory_balance", "inventory_transaction", "inventory_locked_allocation");
        group("采购管理", "procurement_suggestion", "procurement_suggestion_item", "shortage_coverage", "purchase_order", "purchase_order_item", "goods_receipt", "goods_receipt_item");
        group("财务管理", "customer_receipt", "supplier_payment", "sales_invoice", "purchase_invoice");
        group("资金管理", "customer_fund_account", "customer_fund_request", "customer_fund_ledger");
        group("售后管理", "after_sales_order", "after_sales_return_line", "after_sales_replacement_line", "after_sales_receipt", "after_sales_receipt_line", "after_sales_shipment", "after_sales_shipment_line", "after_sales_refund");
        group("导入管理", "import_batch", "import_row", "import_workbook_file", "import_workbook_sheet", "import_workbook_row");
        group("单号管理", "document_number_sequence");
        group("附件管理", "attachment");
    }
    private static void group(String module, String... tables) { for (String table:tables) TABLES.put(table,module); }
    static String module(String table) {
        if (table.startsWith("after_sales")) return "售后管理";
        return TABLES.getOrDefault(table, "其他业务");
    }
    static String requestModule(String path) {
        if (path.startsWith("/api/auth")) return "登录管理";
        if (path.startsWith("/api/operation-records")) return "操作记录";
        if (path.contains("customer-funds")) return "资金管理";
        if (path.contains("after-sales")) return "售后管理";
        if (path.contains("procurement") || path.contains("/purchase")) return "采购管理";
        if (path.contains("finance")) return "财务管理";
        if (path.contains("import")) return "导入管理";
        if (path.contains("customer")) return "客户管理";
        if (path.contains("supplier")) return "供应商管理";
        if (path.contains("inventory")) return "库存管理";
        if (path.contains("product") || path.contains("/cost")) return "产品管理";
        if (path.contains("order")) return "订单管理";
        if (path.contains("user")) return "用户管理";
        if (path.contains("dashboard")) return "首页";
        return "业务查询";
    }
    static String action(String method, String path) {
        if (path.endsWith("/login")) return "登录系统";
        if (path.endsWith("/logout")) return "退出登录";
        if (path.endsWith("/undo")) return "撤回操作";
        String suffix = path.substring(path.lastIndexOf('/') + 1);
        String verb = switch(suffix) {
            case "commit" -> "提交导入"; case "preview" -> "上传并预览导入";
            case "review" -> "复核"; case "confirm" -> "确认"; case "reject" -> "驳回";
            case "receive" -> "收货入库"; case "ship", "shipment" -> "发货出库";
            case "pay", "payment" -> "支付/付款登记"; case "receipt" -> "收款登记";
            case "reverse" -> "资金冲正"; case "refund" -> "退款"; case "cancel" -> "取消";
            case "generate" -> "生成采购建议"; case "allocations" -> "库存分配";
            case "shipment-quantities" -> "修改发货数量"; case "logistics" -> "修改物流信息";
            case "primary" -> "设置主图"; default -> switch(method) {
                case "POST" -> "新增/执行"; case "PUT", "PATCH" -> "修改"; case "DELETE" -> "删除"; default -> "查看";
            };
        };
        if (method.equals("GET")) verb = path.contains("export") || path.endsWith(".xlsx") ? "导出" : "查看";
        return requestModule(path) + " · " + verb;
    }
    static String label(String table, Map<String,Object> row) {
        if (row == null) return "";
        for (String column:List.of("order_no","purchase_no","suggestion_no","after_sales_no","receipt_no","source_no","business_no","invoice_no","customer_name","supplier_name","product_name","username","filename","original_filename")) {
            Object value=row.get(column);
            if (value!=null && !value.toString().isBlank()) return value.toString();
        }
        return module(table);
    }
    static String sourceTable(Object type) {
        return switch(String.valueOf(type)) {
            case "SALES", "SALES_ORDER", "ORDER", "ORDER_RECEIPT", "ORDER_REFUND" -> "sales_order";
            case "PURCHASE", "PURCHASE_ORDER" -> "purchase_order";
            case "AFTER_SALES", "AFTER_SALES_REFUND" -> "after_sales_order";
            default -> null;
        };
    }
}
