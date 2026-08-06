package com.internalops.workbench;

import com.internalops.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/suppliers")
public class SupplierManagementController {
    private final SupplierManagementService service;
    private final WorkbenchQueryService queries;

    public SupplierManagementController(SupplierManagementService service, WorkbenchQueryService queries) {
        this.service = service;
        this.queries = queries;
    }

    @GetMapping("/options")
    public ApiResponse<List<Map<String, Object>>> options(@RequestParam(defaultValue = "") String keyword) {
        return ApiResponse.ok(queries.supplierOptions(keyword));
    }

    @GetMapping("/{id}/products")
    public ApiResponse<List<Map<String, Object>>> products(@PathVariable long id,
                                                             @RequestParam(defaultValue = "") String keyword) {
        return ApiResponse.ok(queries.supplierProducts(id, keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        return ApiResponse.ok(queries.supplierDetail(id));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody SupplierCommandRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable long id, @RequestBody SupplierCommandRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(false, null, exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Object>> conflict(IllegalStateException exception) {
        return ResponseEntity.status(409).body(new ApiResponse<>(false, null, exception.getMessage()));
    }
}
