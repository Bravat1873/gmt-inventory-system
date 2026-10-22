package com.internalops.audit;

import com.internalops.api.ApiResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/operation-records")
public class OperationRecordController {
    private final OperationRecordService service;
    public OperationRecordController(OperationRecordService service){this.service=service;}
    @GetMapping public ApiResponse<Map<String,Object>> list(
            @RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String actor,
            @RequestParam(defaultValue="")String module,@RequestParam(defaultValue="")String status,
            @RequestParam(defaultValue="")String from,@RequestParam(defaultValue="")String to,
            @RequestParam(defaultValue="1")int page) {
        return ApiResponse.ok(service.list(keyword,actor,module,status,from,to,page));
    }
    @GetMapping("/{id}") public ApiResponse<Map<String,Object>> detail(@PathVariable long id,@RequestParam(defaultValue="1")int changePage) {
        return ApiResponse.ok(service.detail(id,changePage));
    }
    @PostMapping("/{id}/undo") public ApiResponse<Map<String,Object>> undo(@PathVariable long id,@RequestBody UndoRequest request) {
        return ApiResponse.ok(service.undo(id,request.reason()));
    }
    @ExceptionHandler(SecurityException.class) @ResponseStatus(HttpStatus.FORBIDDEN)
    ApiResponse<Void> forbidden(SecurityException e){return new ApiResponse<>(false,null,e.getMessage());}
    @ExceptionHandler(OperationRecordService.UndoConflict.class) @ResponseStatus(HttpStatus.CONFLICT)
    ApiResponse<Void> conflict(RuntimeException e){return new ApiResponse<>(false,null,e.getMessage());}
    @ExceptionHandler(DataIntegrityViolationException.class) @ResponseStatus(HttpStatus.CONFLICT)
    ApiResponse<Void> referenced(DataIntegrityViolationException e){return new ApiResponse<>(false,null,"关联数据不允许撤回，整次撤回已回滚，请先处理后续业务");}
    public record UndoRequest(String reason) { }
}
