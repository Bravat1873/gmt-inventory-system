package com.internalops.workbench;

import com.internalops.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/workbench")
public class MasterDataController {
    private final MasterDataCommandService service;
    public MasterDataController(MasterDataCommandService service) { this.service = service; }

    @PostMapping("/{module}")
    public ApiResponse<Map<String,Object>> create(@PathVariable String module, @RequestBody EntityCommandRequest request) {
        return ApiResponse.ok(service.create(module, request));
    }
    @PutMapping("/{module}/{id}")
    public ApiResponse<Map<String,Object>> update(@PathVariable String module, @PathVariable long id, @RequestBody EntityCommandRequest request) {
        return ApiResponse.ok(service.update(module, id, request));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(false,null,e.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Object>> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(new ApiResponse<>(false,null,e.getMessage()));
    }
}
