package com.internalops.workbench;

import com.internalops.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/procurement")
public class ProcurementController {
    private final ProcurementWorkflowService service;

    public ProcurementController(ProcurementWorkflowService service) {
        this.service = service;
    }

    @GetMapping("/unconfigured-shortages")
    public ApiResponse<List<Map<String, Object>>> unconfiguredShortages() {
        return ApiResponse.ok(service.unconfiguredShortages());
    }
    @PostMapping("/generate")
    public ApiResponse<Map<String, Object>> generate() { return ApiResponse.ok(service.generate()); }

    @PutMapping("/suggestions/{id}")
    public ApiResponse<Map<String,Object>> updateReview(@PathVariable long id,@RequestBody Map<String,Object> body) {
        return ApiResponse.ok(service.updateReview(id,body));
    }
    @GetMapping("/suggestions/{id}")
    public ApiResponse<Map<String,Object>> review(@PathVariable long id) { return ApiResponse.ok(service.review(id)); }

    @PostMapping("/suggestions/{id}/reject")
    public ApiResponse<Map<String,Object>> reject(@PathVariable long id,@RequestBody Map<String,Object> body) {
        int version=((Number)body.getOrDefault("version",-1)).intValue();
        return ApiResponse.ok(service.reject(id,version,String.valueOf(body.getOrDefault("reason",""))));
    }
    @PostMapping("/suggestions/{id}/confirm")
    public ApiResponse<Map<String, Object>> confirm(@PathVariable long id) { return ApiResponse.ok(service.confirm(id)); }

    @PostMapping("/manual")
    public ApiResponse<Map<String, Object>> manual(@RequestBody ManualPurchaseRequest request) { return ApiResponse.ok(service.manual(request)); }

    @PostMapping("/purchases/{id}/payment")
    public ApiResponse<Map<String, Object>> pay(@PathVariable long id, @RequestBody FinanceActionRequest request) { return ApiResponse.ok(service.payment(id, request)); }

    @PostMapping("/purchases/by-number/{purchaseNo}/payment")
    public ApiResponse<Map<String, Object>> payByNumber(@PathVariable String purchaseNo, @RequestBody FinanceActionRequest request) { return ApiResponse.ok(service.paymentByPurchaseNo(purchaseNo, request)); }

    @PostMapping("/purchases/{id}/receive")
    public ApiResponse<Map<String, Object>> receive(@PathVariable long id, @RequestBody PurchaseReceiptRequest request) { return ApiResponse.ok(service.receive(id, request)); }

    @GetMapping("/purchases/{id}")
    public ApiResponse<Map<String, Object>> purchase(@PathVariable long id) { return ApiResponse.ok(service.purchase(id)); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Object>> bad(IllegalArgumentException exception) { return ResponseEntity.badRequest().body(new ApiResponse<>(false, null, exception.getMessage())); }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiResponse<Object>> conflict(IllegalStateException exception) { return ResponseEntity.status(409).body(new ApiResponse<>(false, null, exception.getMessage())); }
}
