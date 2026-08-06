package com.internalops.workbench;

import com.internalops.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/orders")
public class SalesOrderController {
    private final SalesOrderCommandService service;
    private final ShipmentQuantityService shipmentQuantityService;
    public SalesOrderController(SalesOrderCommandService service, ShipmentQuantityService shipmentQuantityService){this.service=service;this.shipmentQuantityService=shipmentQuantityService;}
    @PostMapping public ApiResponse<Map<String,Object>> create(@RequestBody SalesOrderRequest r){return ApiResponse.ok(service.create(r));}
    @GetMapping("/skus") public ApiResponse<java.util.List<Map<String,Object>>> skus(){return ApiResponse.ok(service.skuOptions());}
    @GetMapping("/customers") public ApiResponse<java.util.List<Map<String,Object>>> customers(){return ApiResponse.ok(service.customerOptions());}
    @GetMapping("/{id}") public ApiResponse<Map<String,Object>> get(@PathVariable long id){return ApiResponse.ok(service.get(id));}
    @PutMapping("/{id}") public ApiResponse<Map<String,Object>> update(@PathVariable long id,@RequestBody SalesOrderRequest r){return ApiResponse.ok(service.update(id,r));}
    @PutMapping("/{id}/shipment-quantities") public ApiResponse<Map<String,Object>> updateShipmentQuantities(@PathVariable long id,@RequestBody ShipmentQuantityRequest r){return ApiResponse.ok(shipmentQuantityService.update(id,r));}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<ApiResponse<Object>> bad(IllegalArgumentException e){return ResponseEntity.badRequest().body(new ApiResponse<>(false,null,e.getMessage()));}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<ApiResponse<Object>> conflict(IllegalStateException e){return ResponseEntity.status(409).body(new ApiResponse<>(false,null,e.getMessage()));}
}
