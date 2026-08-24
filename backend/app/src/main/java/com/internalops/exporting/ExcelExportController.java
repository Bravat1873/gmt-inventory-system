package com.internalops.exporting;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/exports")
public class ExcelExportController {
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final ExcelExportService exports;

    public ExcelExportController(ExcelExportService exports) { this.exports = exports; }

    @GetMapping("/{module}/summary")
    public ResponseEntity<byte[]> summary(@PathVariable String module) {
        return file(exports.summary(module), exports.summaryFilename(module));
    }

    @GetMapping("/{module}/document")
    public ResponseEntity<byte[]> document(@PathVariable String module, @RequestParam long id,
                                           @RequestParam(required = false) String type,
                                           @RequestParam(required = false) Long shipmentId) {
        return file(exports.document(module, id, type, shipmentId), exports.documentFilename(module, id, type, shipmentId));
    }

    private ResponseEntity<byte[]> file(byte[] data, String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded).body(data);
    }
}
