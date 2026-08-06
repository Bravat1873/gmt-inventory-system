package com.internalops.system;

import com.internalops.api.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemHealthController {
    private final JdbcTemplate jdbc;

    public SystemHealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        return ApiResponse.ok(Map.of("application", "正常", "database", "正常"));
    }
}
