package com.internalops.importing;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;

import java.math.BigDecimal;

final class ExcelValueReader {
    private static final DataFormatter FORMATTER = new DataFormatter();

    private ExcelValueReader() {
    }

    static String text(Cell cell) {
        if (cell == null) return "";
        return FORMATTER.formatCellValue(cell).trim();
    }

    static BigDecimal decimal(Cell cell) {
        String value = text(cell);
        if (value.isBlank()) return null;
        if (cell != null && (cell.getCellType() == CellType.NUMERIC
                || cell.getCellType() == CellType.FORMULA && cell.getCachedFormulaResultType() == CellType.NUMERIC)) {
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros();
        }
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("数值格式错误：" + value);
        }
    }

    static int integer(Cell cell) {
        BigDecimal value = decimal(cell);
        if (value == null) return 0;
        try {
            return value.intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("数量必须为整数：" + value.toPlainString());
        }
    }
}
