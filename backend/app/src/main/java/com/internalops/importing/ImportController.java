package com.internalops.importing;

import com.internalops.api.ApiResponse;
import com.internalops.auth.CurrentUser;
import com.internalops.auth.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/imports")
public class ImportController {
    private final ImportPreviewService previewService;
    private final ImportCommitService commitService;
    private final ImportErrorWorkbookService errorWorkbookService;

    public ImportController(ImportPreviewService previewService, ImportCommitService commitService,
                            ImportErrorWorkbookService errorWorkbookService) {
        this.previewService = previewService;
        this.commitService = commitService;
        this.errorWorkbookService = errorWorkbookService;
    }

    @PostMapping("/preview")
    public ApiResponse<ImportBatchView> preview(@RequestParam ImportType type, @RequestPart MultipartFile file) {
        authorizeOrderImport(type);
        return ApiResponse.ok(previewService.preview(type, file));
    }

    @GetMapping("/{batchId}")
    public ApiResponse<ImportBatchView> get(@PathVariable long batchId) {
        return ApiResponse.ok(authorizedBatch(batchId));
    }

    @PostMapping("/{batchId}/rows")
    public ApiResponse<ImportRowView> add(@PathVariable long batchId, @RequestBody ImportRowRequest request) {
        authorizedBatch(batchId);
        return ApiResponse.ok(previewService.add(batchId, request));
    }

    @PutMapping("/{batchId}/rows/{rowId}")
    public ApiResponse<ImportRowView> update(@PathVariable long batchId, @PathVariable long rowId,
                                             @RequestBody ImportRowRequest request) {
        authorizedBatch(batchId);
        return ApiResponse.ok(previewService.update(batchId, rowId, request));
    }

    @PostMapping("/{batchId}/commit")
    public ApiResponse<ImportBatchView> commit(@PathVariable long batchId,
                                                @RequestBody(required = false) ImportCommitRequest request) {
        authorizedBatch(batchId);
        ImportConflictPolicy policy = request == null
                ? ImportConflictPolicy.UPSERT_KEEP_EXISTING_ON_BLANK
                : request.resolvedPolicy();
        ImportCommitRequest.SupplierMode supplierMode = request == null
                ? ImportCommitRequest.SupplierMode.OVERWRITE
                : request.resolvedSupplierMode();
        return ApiResponse.ok(commitService.commit(batchId, policy, supplierMode,
                request == null ? java.util.Map.of() : request.productConflictActions()));
    }

    @GetMapping("/{batchId}/errors.xlsx")
    public ResponseEntity<byte[]> errors(@PathVariable long batchId) {
        authorizedBatch(batchId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=import-errors-" + batchId + ".xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(errorWorkbookService.create(batchId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(false, null, exception.getMessage()));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponse<Object>> forbidden(SecurityException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiResponse<>(false, null, exception.getMessage()));
    }

    private ImportBatchView authorizedBatch(long batchId) {
        ImportBatchView batch = previewService.get(batchId);
        authorizeOrderImport(batch.importType());
        return batch;
    }

    private void authorizeOrderImport(ImportType type) {
        if (type != ImportType.ORDER) return;
        UserRole role = CurrentUser.required().role();
        if (role != UserRole.ADMIN && role != UserRole.USER) {
            throw new SecurityException("财务用户不能导入销售订单");
        }
    }
}
