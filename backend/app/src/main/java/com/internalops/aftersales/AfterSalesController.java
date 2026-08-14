package com.internalops.aftersales;

import com.internalops.api.ApiResponse;
import com.internalops.customerfund.CustomerFundService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/after-sales") public class AfterSalesController {
 private final AfterSalesCommandService commands;private final AfterSalesQueryService queries;private final CustomerFundService funds;public AfterSalesController(AfterSalesCommandService c,AfterSalesQueryService q,CustomerFundService f){commands=c;queries=q;funds=f;}
 @PostMapping public ApiResponse<Map<String,Object>> create(@RequestBody AfterSalesRequest r){return ApiResponse.ok(commands.create(r));}
 @GetMapping("/{id}") public ApiResponse<Map<String,Object>> get(@PathVariable long id){return ApiResponse.ok(queries.get(id));}
 @PutMapping("/{id}") public ApiResponse<Map<String,Object>> update(@PathVariable long id,@RequestBody AfterSalesRequest r){return ApiResponse.ok(commands.update(id,r));}
 @PostMapping("/{id}/cancel") public ApiResponse<Map<String,Object>> cancel(@PathVariable long id,@RequestParam int version){return ApiResponse.ok(commands.cancel(id,version));}
 @PostMapping("/{id}/receipts") public ApiResponse<Map<String,Object>> receive(@PathVariable long id,@RequestBody AfterSalesReceiptRequest r){return ApiResponse.ok(commands.receive(id,r));}
 @PostMapping("/{id}/shipments") public ApiResponse<Map<String,Object>> ship(@PathVariable long id,@RequestBody AfterSalesShipmentRequest r){return ApiResponse.ok(commands.ship(id,r));}
 @GetMapping("/{id}/refund-suggestion") public ApiResponse<Map<String,Object>> refundSuggestion(@PathVariable long id){return ApiResponse.ok(queries.refundSuggestion(id));}
 @PostMapping("/{id}/refunds") public ApiResponse<Map<String,Object>> refund(@PathVariable long id,@RequestBody AfterSalesRefundRequest r){long requestId=funds.submitAfterSalesRefund(id,r.amount(),r.suggestedAmount(),r.adjustmentReason());return ApiResponse.ok(Map.of("requestId",requestId,"status","PENDING"));}
 @GetMapping("/order-options") public ApiResponse<List<Map<String,Object>>> orders(@RequestParam(defaultValue="")String keyword){return ApiResponse.ok(queries.orderOptions(keyword));}
 @GetMapping("/orders/{id}/lines") public ApiResponse<List<Map<String,Object>>> lines(@PathVariable long id){return ApiResponse.ok(queries.orderLines(id));}
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<ApiResponse<Object>> bad(IllegalArgumentException e){return ResponseEntity.badRequest().body(new ApiResponse<>(false,null,e.getMessage()));}
 @ExceptionHandler(IllegalStateException.class) ResponseEntity<ApiResponse<Object>> conflict(IllegalStateException e){return ResponseEntity.status(409).body(new ApiResponse<>(false,null,e.getMessage()));}
}
