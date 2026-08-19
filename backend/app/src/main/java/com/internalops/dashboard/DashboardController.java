package com.internalops.dashboard;

import com.internalops.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardQueryService service;
    private final DashboardEventService events;

    public DashboardController(DashboardQueryService service, DashboardEventService events) {
        this.service = service;
        this.events = events;
    }

    @GetMapping("/summary")
    public ApiResponse<DashboardSummary> summary() { return ApiResponse.ok(service.summary()); }

    @GetMapping("/snapshot")
    public ApiResponse<DashboardSnapshot> snapshot(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(service.snapshot(days));
    }

    @GetMapping(path = "/events", produces = "text/event-stream")
    public SseEmitter events(@RequestParam(defaultValue = "30") int days) { return events.subscribe(days); }
}
