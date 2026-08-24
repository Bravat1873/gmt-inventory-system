package com.internalops.importing;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

final class ImportTemplateWorkbookService {
    byte[] create(ImportType type) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (type == ImportType.PRODUCT) {
                createProductTemplate(workbook);
            } else {
                var sheet = workbook.createSheet(templateSheetName(type));
                writeHeaders(sheet, headers(type));
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("模板生成失败", exception);
        }
    }

    private List<String> headers(ImportType type) {
        return switch (type) {
            case CUSTOMER -> List.of("客户类型", "客户编码", "客户名称", "地址", "业务联系人", "业务联系电话", "订单联系人", "订单联系电话", "财务联系人", "财务联系电话", "发票抬头", "纳税人识别号", "开票地址", "开票电话", "开户银行", "银行账号");
            case SUPPLIER -> List.of("厂商分类", "厂商类型", "供应商地点", "产品属性", "简称", "供应商名称", "联系人", "职称", "联系方式", "供应商地址", "币种", "税务登记号", "开户地址", "开户账户");
            case PRODUCT -> List.of("产品分类", "物料类型", "客户料号", "产品编号（不用写）", "品牌", "系列", "物料颜色", "锁体类型", "联网方式", "销售渠道", "运营主体", "语言", "编码后缀", "型号（系列号+第几代）", "物料规格（不用写，会自动生成）", "产品配置", "销售最小起订量", "供应商名称", "供应商含税价", "实际库存数量", "已锁定数量", "在途数量", "库存备注");
            case ORDER -> List.of("客户编码", "外部订单号", "订单日期", "订单类型", "产品编号", "客户料号", "型号", "数量", "含税单价", "收货地址", "收货联系人", "收货联系电话", "发货方式", "订单备注");
            case COST, INVENTORY -> throw new IllegalArgumentException("该导入类型已合并到产品与库存模板");
        };
    }

    private String templateSheetName(ImportType type) {
        return switch (type) {
            case CUSTOMER -> "客户导入模板";
            case COST -> "成本导入模板";
            case INVENTORY -> "库存导入模板";
            case SUPPLIER -> "供应商导入模板";
            case PRODUCT -> "产品导入模板";
            case ORDER -> "销售订单导入模板";
        };
    }

    private void createProductTemplate(Workbook workbook) {
        for (String sheetName : List.of("GMT库存产品清单", "贝朗库存产品清单", "STANLEY库存产品清单")) {
            writeHeaders(workbook.createSheet(sheetName), headers(ImportType.PRODUCT));
        }
        writeProductRulesSheet(workbook.createSheet("规则说明"));
    }

    private void writeHeaders(org.apache.poi.ss.usermodel.Sheet sheet, List<String> headers) {
        var row = sheet.createRow(0);
        for (int index = 0; index < headers.size(); index++) {
            row.createCell(index).setCellValue(headers.get(index));
            sheet.setColumnWidth(index, 24 * 256);
        }
    }

    private void writeProductRulesSheet(org.apache.poi.ss.usermodel.Sheet sheet) {
        sheet.setColumnWidth(0, 42 * 256);
        sheet.setColumnWidth(1, 48 * 256);
        var title = sheet.createRow(0);
        title.createCell(0).setCellValue("产品编号和物料规格规则");
        sheet.createRow(1).createCell(0).setCellValue("产品编号 = 品牌 + 系列 + 物料颜色 + 锁体类型 + 联网方式 + 销售渠道 + 运营主体 + 语言 + 编码后缀");
        sheet.createRow(2).createCell(0).setCellValue("物料规格 = 品牌 + 型号 + 物料颜色 + 锁体类型 + 语言");
        sheet.createRow(3).createCell(0).setCellValue("产品编号、物料规格不用填写，填写规则列后由系统自动生成。");
        sheet.createRow(5).createCell(0).setCellValue("需要填写的字段");
        var fields = sheet.createRow(6);
        fields.createCell(0).setCellValue("产品分类、物料类型、客户料号、品牌、系列、物料颜色、锁体类型、联网方式、销售渠道、运营主体、语言、编码后缀、型号、产品配置、销售最小起订量、供应商名称、供应商含税价、实际库存数量、已锁定数量、在途数量、库存备注");
    }
}
