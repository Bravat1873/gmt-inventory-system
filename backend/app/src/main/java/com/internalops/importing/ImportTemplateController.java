package com.internalops.importing;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/imports/templates")
public class ImportTemplateController {
    private final ImportTemplateWorkbookService workbookService = new ImportTemplateWorkbookService();

    @GetMapping("/{type}.xlsx")
    public ResponseEntity<byte[]> download(@PathVariable ImportType type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, attachmentFilename(templateFilename(type)))
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(workbookService.create(type));
    }

    private String templateFilename(ImportType type) {
        return switch (type) {
            case CUSTOMER -> "客户批量导入模板.xlsx";
            case COST -> "成本批量导入模板.xlsx";
            case INVENTORY -> "库存批量导入模板.xlsx";
            case SUPPLIER -> "供应商批量导入模板.xlsx";
            case PRODUCT -> "产品批量导入模板.xlsx";
            case ORDER -> "销售订单批量导入模板.xlsx";
        };
    }

    private String attachmentFilename(String filename) {
        return "attachment; filename*=UTF-8''"
                + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
