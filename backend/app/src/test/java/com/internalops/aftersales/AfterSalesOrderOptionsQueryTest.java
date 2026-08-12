package com.internalops.aftersales;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AfterSalesOrderOptionsQueryTest {
    @Test
    void usesMySqlCompatibleGroupingAndNewestFirst() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        new AfterSalesQueryService(jdbc).orderOptions("");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        String normalized = sql.getValue().replaceAll("\\s+", " ").toLowerCase();
        assertFalse(normalized.contains("select distinct"));
        assertTrue(normalized.contains("group by o.id,o.order_no,c.customer_name,o.updated_at"));
        assertTrue(normalized.contains("order by o.updated_at desc"));
    }
}
