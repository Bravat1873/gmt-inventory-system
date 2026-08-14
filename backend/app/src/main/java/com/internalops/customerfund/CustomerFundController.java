package com.internalops.customerfund;

import com.internalops.api.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CustomerFundController {
    private final CustomerFundService commands;
    private final CustomerFundQueryService queries;
    public CustomerFundController(CustomerFundService commands, CustomerFundQueryService queries) { this.commands=commands; this.queries=queries; }

    @GetMapping("/customers/{customerId}/funds/overview") public ApiResponse<Map<String,Object>> overview(@PathVariable long customerId){return ApiResponse.ok(queries.overview(customerId));}
    @GetMapping("/customers/{customerId}/funds/requests") public ApiResponse<List<Map<String,Object>>> requests(@PathVariable long customerId){return ApiResponse.ok(queries.requests(customerId));}
    @GetMapping("/customers/{customerId}/funds/ledger") public ApiResponse<List<Map<String,Object>>> ledger(@PathVariable long customerId){return ApiResponse.ok(queries.ledger(customerId));}
    @GetMapping("/customers/{customerId}/funds/summary") public ApiResponse<List<Map<String,Object>>> summary(@PathVariable long customerId,@RequestParam(defaultValue="MONTH") String period,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to){return ApiResponse.ok(queries.summary(customerId,period,from,to));}
    @GetMapping("/customers/{customerId}/funds/summary/export") public ResponseEntity<byte[]> export(@PathVariable long customerId,@RequestParam(defaultValue="MONTH") String period,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to){byte[] bytes=queries.csv(customerId,period,from,to).getBytes(StandardCharsets.UTF_8);return ResponseEntity.ok().contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=customer-funds.csv").body(bytes);}
    @PostMapping("/customers/{customerId}/funds/deposits") public ApiResponse<Long> deposit(@PathVariable long customerId,@RequestBody CustomerFundRequestCommand command){return ApiResponse.ok(commands.submitDeposit(customerId,command));}
    @PostMapping("/customer-funds/requests/{id}/review") public ApiResponse<Map<String,Object>> review(@PathVariable long id,@RequestBody CustomerFundReviewCommand command){commands.review(id,command);return ApiResponse.ok(Map.of("id",id,"status",command.approved()?"APPROVED":"REJECTED"));}
    @PostMapping("/customer-funds/ledger/{id}/reverse") public ApiResponse<Map<String,Object>> reverse(@PathVariable long id,@RequestBody CustomerFundReversalCommand command){return ApiResponse.ok(Map.of("id",commands.reverse(id,command)));}

    @ExceptionHandler(SecurityException.class) ResponseEntity<ApiResponse<Object>> forbidden(SecurityException e){return ResponseEntity.status(403).body(new ApiResponse<>(false,null,e.getMessage()));}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<ApiResponse<Object>> bad(IllegalArgumentException e){return ResponseEntity.badRequest().body(new ApiResponse<>(false,null,e.getMessage()));}
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<ApiResponse<Object>> conflict(IllegalStateException e){return ResponseEntity.status(409).body(new ApiResponse<>(false,null,e.getMessage()));}
}