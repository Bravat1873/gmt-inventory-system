package com.internalops.workbench;

import com.internalops.api.ApiResponse;
import com.internalops.auth.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/finance/orders")
public class FinanceController {
    private final FinanceWorkflowService workflow;
    private final FinanceReviewService review;
    private final FinanceInvoiceService invoices;
    public FinanceController(FinanceWorkflowService workflow, FinanceReviewService review, FinanceInvoiceService invoices) { this.workflow=workflow; this.review=review; this.invoices=invoices; }
    @PostMapping("/{id}/receipt") public ApiResponse<Map<String,Object>> receipt(@PathVariable long id,@RequestBody FinanceActionRequest request){requireFinanceWrite();return ApiResponse.ok(workflow.receipt(id,request));}
    @GetMapping("/{type}/{id}/records") public ApiResponse<Object> records(@PathVariable String type,@PathVariable long id){return ApiResponse.ok(review.records(type,id));}
    @PostMapping("/receipts/{id}/review") public ApiResponse<Map<String,Object>> reviewReceipt(@PathVariable long id,@RequestBody FinanceReviewRequest request){requireFinanceWrite();return ApiResponse.ok(review.reviewReceipt(id,request));}
    @PostMapping("/payments/{id}/review") public ApiResponse<Map<String,Object>> reviewPayment(@PathVariable long id,@RequestBody FinanceReviewRequest request){requireFinanceWrite();return ApiResponse.ok(review.reviewPayment(id,request));}
    @PostMapping("/{type}/invoices/{id}/review") public ApiResponse<Map<String,Object>> reviewInvoice(@PathVariable String type,@PathVariable long id,@RequestBody FinanceReviewRequest request){requireFinanceWrite();return ApiResponse.ok(review.reviewInvoice(type,id,request));}
    @GetMapping("/{type}/{id}/review-summary") public ApiResponse<Map<String,Object>> reviewSummary(@PathVariable String type,@PathVariable long id){return ApiResponse.ok(review.reviewSummary(type,id));}
    @GetMapping("/{type}/{id}/invoices") public ApiResponse<Object> invoices(@PathVariable String type,@PathVariable long id){return ApiResponse.ok(invoices.list(type,id));}
    @PostMapping("/{type}/{id}/invoices") public ApiResponse<Map<String,Object>> addInvoice(@PathVariable String type,@PathVariable long id,@RequestBody InvoiceRequest request){requireFinanceWrite();return ApiResponse.ok(invoices.save(type,id,request));}
    @DeleteMapping("/{type}/{id}/invoices/{invoiceId}") public ApiResponse<Void> deleteInvoice(@PathVariable String type,@PathVariable long id,@PathVariable long invoiceId){requireFinanceWrite();invoices.delete(type,id,invoiceId);return ApiResponse.ok(null);}
    @PutMapping("/{type}/{id}/invoice") public ApiResponse<Map<String,Object>> saveInvoice(@PathVariable String type,@PathVariable long id,@RequestBody InvoiceRequest request){requireFinanceWrite();return ApiResponse.ok(invoices.save(type,id,request));}
    @DeleteMapping("/{type}/{id}/invoice") public ApiResponse<Void> deleteInvoice(@PathVariable String type,@PathVariable long id){requireFinanceWrite();invoices.delete(type,id);return ApiResponse.ok(null);}
    private void requireFinanceWrite(){if(!CurrentUser.required().role().canWriteFinance())throw new SecurityException("无此操作权限");}
    @ExceptionHandler(SecurityException.class) ResponseEntity<ApiResponse<Object>> forbidden(SecurityException e){return ResponseEntity.status(403).body(new ApiResponse<>(false,null,e.getMessage()));}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<ApiResponse<Object>> bad(IllegalArgumentException e){return ResponseEntity.badRequest().body(new ApiResponse<>(false,null,e.getMessage()));}
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<ApiResponse<Object>> conflict(IllegalStateException e){return ResponseEntity.status(409).body(new ApiResponse<>(false,null,e.getMessage()));}
}
