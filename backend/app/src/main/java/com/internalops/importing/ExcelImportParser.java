package com.internalops.importing;

import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class ExcelImportParser {
    public List<ParsedImportRow> parse(ImportType type, InputStream input) {
        try (var workbook = WorkbookFactory.create(input)) {
            return switch (type) {
                case CUSTOMER -> new CustomerExcelParser().parse(workbook);
                case COST -> new CostExcelParser().parse(workbook);
                case INVENTORY -> new InventoryExcelParser().parse(workbook);
                case SUPPLIER -> new SupplierExcelParser().parse(workbook);
                case PRODUCT -> new ProductWorkbookExcelParser().parse(workbook);
                case ORDER -> new SalesOrderExcelParser().parse(workbook);
            };
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Excel 文件无法读取：" + exception.getMessage(), exception);
        }
    }
}
