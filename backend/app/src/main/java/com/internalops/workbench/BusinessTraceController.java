package com.internalops.workbench;

import com.internalops.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/workbench/traces")
public class BusinessTraceController {
    private final BusinessTraceService service;
    public BusinessTraceController(BusinessTraceService service) { this.service = service; }

    @GetMapping("/{type}/{id}")
    public ApiResponse<Map<String, Object>> trace(@PathVariable String type, @PathVariable long id) { return ApiResponse.ok(service.trace(type, id)); }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(false, null, exception.getMessage()));
    }
}
