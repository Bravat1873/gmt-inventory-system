package com.internalops.customerfund;

import com.internalops.api.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/customer-funds")
public class CustomerFundsManagementController {
    private final CustomerFundsManagementQueryService queries;
    public CustomerFundsManagementController(CustomerFundsManagementQueryService queries) { this.queries=queries; }

    @GetMapping("/customers") public ApiResponse<List<Map<String,Object>>> customers() { return ApiResponse.ok(queries.customers()); }

    @GetMapping({"/operations","/ledger"})
    public ApiResponse<Map<String,Object>> operations(jakarta.servlet.http.HttpServletRequest request,
            @RequestParam(defaultValue="1") int page, @RequestParam(required=false) Long customerId,
            @RequestParam(required=false) String keyword, @RequestParam(required=false) String status,
            @RequestParam(required=false) String direction, @RequestParam(required=false) String fundSource,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(queries.operations(request.getRequestURI().endsWith("/ledger"),page,customerId,keyword,status,direction,fundSource,from,to));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Object>> bad(IllegalArgumentException e) { return ResponseEntity.badRequest().body(new ApiResponse<>(false,null,e.getMessage())); }
}
