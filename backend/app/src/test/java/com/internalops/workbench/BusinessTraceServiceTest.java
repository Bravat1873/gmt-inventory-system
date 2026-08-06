package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class BusinessTraceServiceTest {
    @Test
    void rejectsUnknownBusinessTraceTypeBeforeQueryingData() {
        BusinessTraceService service = new BusinessTraceService(mock(JdbcTemplate.class));

        assertThrows(IllegalArgumentException.class, () -> service.trace("unknown", 1L));
    }
}
