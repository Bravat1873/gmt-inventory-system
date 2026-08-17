package com.internalops.importing;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/imports/templates")
public class ImportTemplateController {
    private static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final String TEMPLATE_PATH = "templates/sales-order-import-template.xlsx";
    private static final String DOWNLOAD_NAME = "销售订单批量导入模板.xlsx";

    @GetMapping("/ORDER.xlsx")
    public ResponseEntity<byte[]> downloadSalesOrderTemplate() throws IOException {
        byte[] template;
        try (var input = new ClassPathResource(TEMPLATE_PATH).getInputStream()) {
            template = input.readAllBytes();
        }
        String disposition = ContentDisposition.attachment()
                .filename(DOWNLOAD_NAME, StandardCharsets.UTF_8)
                .build()
                .toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(XLSX_MEDIA_TYPE)
                .contentLength(template.length)
                .body(template);
    }
}
