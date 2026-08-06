package com.internalops.importing;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class RawWorkbookReaderTest {
    @Test
    void preservesEverySheetRowAndBlankCellWithoutUsingBusinessKeys() throws Exception {
        byte[] file = workbookBytes();

        RawWorkbookSnapshot snapshot = new RawWorkbookReader().read(file);

        assertThat(snapshot.fileContent()).isEqualTo(file);
        assertThat(snapshot.sheets()).hasSize(2);
        assertThat(snapshot.sheets().get(0).sheetName()).isEqualTo("库存");
        assertThat(snapshot.sheets().get(0).rows()).extracting(RawWorkbookRow::sourceRow)
                .containsExactly(1, 2, 3);
        assertThat(snapshot.sheets().get(0).rows().get(1).cells()).containsExactly("SKU-001", "SKU-001", "7");
        assertThat(snapshot.sheets().get(0).rows().get(2).cells()).containsExactly("", "", "");
        assertThat(snapshot.sheets().get(1).rows().get(0).cells()).containsExactly("客户", "客户");
    }

    private byte[] workbookBytes() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var inventory = workbook.createSheet("库存");
            inventory.createRow(0).createCell(0).setCellValue("SKU");
            inventory.getRow(0).createCell(1).setCellValue("SKU");
            var duplicate = inventory.createRow(1);
            duplicate.createCell(0).setCellValue("SKU-001");
            duplicate.createCell(1).setCellValue("SKU-001");
            duplicate.createCell(2).setCellValue(7);
            inventory.createRow(2);

            var customers = workbook.createSheet("客户");
            customers.createRow(0).createCell(0).setCellValue("客户");
            customers.getRow(0).createCell(1).setCellValue("客户");
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
