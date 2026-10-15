package com.internalops.customerfund;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class CustomerFundsExcelExport {
    private static final Map<String,String> LABELS = Map.ofEntries(
            Map.entry("DOMESTIC","国内"),Map.entry("FOREX","外汇"),Map.entry("PENDING","待确认"),
            Map.entry("APPROVED","已确认"),Map.entry("REJECTED","已驳回"),Map.entry("REVERSED","已冲正"),
            Map.entry("CUSTOMER_DEPOSIT","客户打款"),Map.entry("ORDER_RECEIPT","订单抵扣"),
            Map.entry("ORDER_REFUND","订单抵扣退回"),Map.entry("AFTER_SALES_REFUND","售后退回余额"),Map.entry("REVERSAL","冲正"));

    @SuppressWarnings("unchecked")
    static byte[] workbook(boolean ledger, Map<String,Object> data) {
        LinkedHashMap<String,String> columns = new LinkedHashMap<>();
        columns.put("operationNo","操作编号"); columns.put("customerCode","客户编码"); columns.put("customerName","客户名称");
        columns.put("fundSource","资金来源"); columns.put(ledger ? "entryType" : "requestType","业务类型");
        columns.put("direction",ledger ? "变动方向" : "收付款类型"); columns.put("amount","资金金额");
        if (!ledger) columns.put("confirmedAmount","确认金额");
        columns.put("submittedAt","操作时间"); columns.put("status","状态"); columns.put("submittedByName","操作人员");
        if (ledger) { columns.put("balanceBefore","变动前余额"); columns.put("balanceAfter","变动后余额"); }
        else {
            columns.put("paymentAt","打款时间"); columns.put("paymentMethod","打款方式"); columns.put("referenceNo","银行流水号");
            columns.put("reviewedByName","确认人员"); columns.put("reviewedAt","确认时间"); columns.put("reviewComment","确认或驳回原因");
        }
        columns.put("sourceNo","关联单据号"); columns.put("remark","备注");
        columns.put("reversalReason","冲正原因"); columns.put("reversalByName","冲正人员"); columns.put("reversalAt","冲正时间");
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet(ledger ? "余额流水" : "收付款登记");
            CellStyle header = book.createCellStyle(); Font font = book.createFont(); font.setBold(true); header.setFont(font);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex()); header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle money = book.createCellStyle(); money.setDataFormat(book.createDataFormat().getFormat("#,##0.00##"));
            Row title = sheet.createRow(0); int column = 0;
            for (String label : columns.values()) { Cell cell = title.createCell(column++); cell.setCellValue(label); cell.setCellStyle(header); }
            int index = 1;
            for (Map<String,Object> item : (List<Map<String,Object>>) data.get("items")) {
                Row row = sheet.createRow(index++); column = 0;
                for (String key : columns.keySet()) {
                    Cell cell = row.createCell(column++); Object value = item.get(key);
                    if ("paymentAt".equals(key) && value == null) value = item.get("paymentDate");
                    if (value == null) { cell.setCellValue(""); continue; }
                    if (value instanceof Number number) { cell.setCellValue(number.doubleValue()); cell.setCellStyle(money); }
                    else if ("direction".equals(key)) cell.setCellValue("OUT".equals(value) ? (ledger ? "余额扣减" : "退款") : (ledger ? "余额增加" : "收款"));
                    else if (value instanceof LocalDateTime time) cell.setCellValue(time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    else cell.setCellValue(LABELS.getOrDefault(String.valueOf(value),String.valueOf(value)));
                }
            }
            sheet.createFreezePane(0,1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,Math.max(0,index-1),0,columns.size()-1));
            for (int i=0;i<columns.size();i++) sheet.setColumnWidth(i,22*256);
            if (!ledger && data.get("summary") instanceof Map<?,?> summary) {
                Sheet totals = book.createSheet("筛选汇总");
                String[] keys = {"pendingReceipt","pendingRefund","confirmedReceipt","confirmedRefund"};
                String[] labels = {"待确认收款","待确认退款","已确认收款","已确认退款"};
                for (int i=0;i<keys.length;i++) { Row row=totals.createRow(i);row.createCell(0).setCellValue(labels[i]);Cell cell=row.createCell(1);cell.setCellValue(((Number)summary.get(keys[i])).doubleValue());cell.setCellStyle(money); }
                totals.setColumnWidth(0,24*256);totals.setColumnWidth(1,24*256);
            }
            book.write(output); return output.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("资金数据导出失败",e); }
    }
}
