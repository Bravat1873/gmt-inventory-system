package com.internalops.workbench;

import com.internalops.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Object>> dataRule(DataIntegrityViolationException e) {
        String detail = e.getMostSpecificCause() == null ? "" : e.getMostSpecificCause().getMessage();
        String message = detail != null && detail.contains("Data too long")
                ? "保存失败：填写内容超过字段允许长度，请缩短后重试"
                : detail != null && detail.contains("Duplicate entry")
                ? "保存失败：存在重复的库存关联数据，请重新打开后再保存"
                : "保存失败：库存明细写入失败，请重新打开后再保存";
        return ResponseEntity.badRequest().body(new ApiResponse<>(false, null,
                message));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(false, null,
                "提交的数据格式不正确，请重新填写后保存"));
    }
}
