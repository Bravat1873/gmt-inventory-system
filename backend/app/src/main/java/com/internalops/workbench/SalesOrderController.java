package com.internalops.workbench;

import com.internalops.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/orders")
public class SalesOrderController {
    private final SalesOrderCommandService service;
    private final ShipmentQuantityService shipmentQuantityService;
    private final OrderAllocationService allocationService;
    public SalesOrderController(SalesOrderCommandService service, ShipmentQuantityService shipmentQuantityService, OrderAllocationService allocationService){this.service=service;this.shipmentQuantityService=shipmentQuantityService;this.allocationService=allocationService;}
    @PostMapping public ApiResponse<Map<String,Object>> create(@RequestBody SalesOrderRequest r){return ApiResponse.ok(service.create(r));}
    @GetMapping("/skus") public ApiResponse<java.util.List<Map<String,Object>>> skus(){return ApiResponse.ok(service.skuOptions());}
    @GetMapping("/customers") public ApiResponse<java.util.List<Map<String,Object>>> customers(){return ApiResponse.ok(service.customerOptions());}
    @GetMapping("/contract-price") public ApiResponse<Map<String,Object>> contractPrice(@RequestParam long customerId,@RequestParam long skuId){return ApiResponse.ok(service.contractPrice(customerId,skuId));}
    @GetMapping("/{id}") public ApiResponse<Map<String,Object>> get(@PathVariable long id){return ApiResponse.ok(service.get(id));}
    @PutMapping("/{id}") public ApiResponse<Map<String,Object>> update(@PathVariable long id,@RequestBody SalesOrderRequest r){return ApiResponse.ok(service.update(id,r));}
    @GetMapping("/{id}/allocations") public ApiResponse<Map<String,Object>> allocations(@PathVariable long id){return ApiResponse.ok(allocationService.view(id));}
    @PutMapping("/{id}/allocations") public ApiResponse<Map<String,Object>> updateAllocations(@PathVariable long id,@RequestBody OrderAllocationRequest r){return ApiResponse.ok(allocationService.update(id,r));}
    @PutMapping("/{id}/shipment-quantities") public ApiResponse<Map<String,Object>> updateShipmentQuantities(@PathVariable long id,@RequestBody ShipmentQuantityRequest r){return ApiResponse.ok(shipmentQuantityService.update(id,r));}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<ApiResponse<Object>> bad(IllegalArgumentException e){return ResponseEntity.badRequest().body(new ApiResponse<>(false,null,e.getMessage()));}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<ApiResponse<Object>> conflict(IllegalStateException e){return ResponseEntity.status(409).body(new ApiResponse<>(false,null,e.getMessage()));}
}
