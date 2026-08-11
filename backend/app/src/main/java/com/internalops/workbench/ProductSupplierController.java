package com.internalops.workbench;

import com.internalops.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class ProductSupplierController {
    private final WorkbenchQueryService queries;

    public ProductSupplierController(WorkbenchQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/{skuId}/suppliers")
    public ApiResponse<List<Map<String, Object>>> suppliers(@PathVariable long skuId,
                                                             @RequestParam(defaultValue = "") String keyword) {
        return ApiResponse.ok(queries.productSuppliers(skuId, keyword));
    }
}