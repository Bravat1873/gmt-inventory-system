package com.internalops.importing;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class ImportErrorWorkbookService {
    private final ImportBatchRepository repository;

    public ImportErrorWorkbookService(ImportBatchRepository repository) {
        this.repository = repository;
    }

    public byte[] create(long batchId) {
        var errors = repository.findRows(batchId).stream().filter(row -> row.status() == ImportRowStatus.ERROR).toList();
        Set<String> keys = new LinkedHashSet<>();
        errors.forEach(row -> keys.addAll(row.data().keySet()));
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("异常数据");
            var header = sheet.createRow(0);
            String[] fixed = {"工作表", "原始行号", "手工录入", "错误原因"};
            for (int i = 0; i < fixed.length; i++) header.createCell(i).setCellValue(fixed[i]);
            int column = fixed.length;
            for (String key : keys) header.createCell(column++).setCellValue(key);
            int rowIndex = 1;
            for (ImportRowView error : errors) {
                var row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(error.sheetName());
                row.createCell(1).setCellValue(error.rowNumber());
                row.createCell(2).setCellValue(error.manualEntry() ? "是" : "否");
                row.createCell(3).setCellValue(error.errorMessage() == null ? "" : error.errorMessage());
                column = fixed.length;
                for (String key : keys) row.createCell(column++).setCellValue(String.valueOf(error.data().getOrDefault(key, "")));
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("错误清单生成失败", exception);
        }
    }
}
