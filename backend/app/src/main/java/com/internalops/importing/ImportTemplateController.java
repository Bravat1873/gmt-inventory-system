package com.internalops.importing;

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
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + type.name() + ".xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(workbookService.create(type));
    }
}
