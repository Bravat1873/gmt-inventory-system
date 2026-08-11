package com.internalops.productcode;

import com.internalops.api.ApiResponse;
import com.internalops.auth.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/product-code-rules")
public class ProductCodeRuleController {
    private final ProductCodeRuleService service;
    public ProductCodeRuleController(ProductCodeRuleService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<Map<String,Object>>> list(@RequestParam ProductCodeCategory category,
                                                       @RequestParam(defaultValue = "false") boolean includeDisabled) {
        CurrentUser.required();
        return ApiResponse.ok(service.list(category, includeDisabled));
    }

    @PostMapping
    public ApiResponse<Map<String,Object>> create(@RequestBody ProductCodeRuleRequest request) {
        CurrentUser.required();
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String,Object>> update(@PathVariable long id, @RequestBody ProductCodeRuleRequest request) {
        CurrentUser.required();
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Object> delete(@PathVariable long id) {
        CurrentUser.required();
        service.delete(id);
        return ApiResponse.ok(null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(false, null, e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiResponse<Object>> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(new ApiResponse<>(false, null, e.getMessage()));
    }
}
