package com.internalops.workbench;

import com.internalops.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/customers")
public class CustomerManagementController {
    private final CustomerManagementService service;
    public CustomerManagementController(CustomerManagementService service) { this.service = service; }
    @GetMapping("/{id}") public ApiResponse<Map<String,Object>> detail(@PathVariable long id) { return ApiResponse.ok(service.detail(id)); }
    @PostMapping public ApiResponse<Map<String,Object>> create(@RequestBody CustomerCommandRequest request) { return ApiResponse.ok(service.create(request)); }
    @PutMapping("/{id}") public ApiResponse<Map<String,Object>> update(@PathVariable long id, @RequestBody CustomerCommandRequest request) { return ApiResponse.ok(service.update(id, request)); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<ApiResponse<Object>> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(new ApiResponse<>(false,null,e.getMessage())); }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<ApiResponse<Object>> conflict(IllegalStateException e) { return ResponseEntity.status(409).body(new ApiResponse<>(false,null,e.getMessage())); }
}
