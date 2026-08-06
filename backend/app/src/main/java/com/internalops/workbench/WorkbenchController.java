package com.internalops.workbench;

import com.internalops.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workbench")
public class WorkbenchController {
    private final WorkbenchQueryService service;

    public WorkbenchController(WorkbenchQueryService service) {
        this.service = service;
    }

    @GetMapping("/{module}")
    public ApiResponse<PageResult<Map<String, Object>>> query(
            @PathVariable String module,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return ApiResponse.ok(service.query(module, new ListQuery(page, keyword, sort, direction)));
    }

    @GetMapping("/inventory/{inventoryId}/movements")
    public ApiResponse<List<Map<String, Object>>> inventoryMovements(@PathVariable long inventoryId) {
        return ApiResponse.ok(service.inventoryMovements(inventoryId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(false, null, exception.getMessage()));
    }
}
