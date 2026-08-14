package com.internalops.aftersales;

import com.internalops.auth.CurrentUser;
import com.internalops.auth.UserRole;
import com.internalops.customerfund.CustomerFundService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AfterSalesRefundApiTest {
    @AfterEach void clear(){ CurrentUser.clear(); }

    @Test
    void suggestionUsesReceivedReturnQuantityAndOriginalSalePriceWhileAdjustedRequestStaysPending() {
        var database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("after-sales-refund-" + System.nanoTime()).addScript("customer-fund-schema.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        AfterSalesQueryService queries = new AfterSalesQueryService(jdbc);
        CustomerFundService funds = new CustomerFundService(jdbc);
        CurrentUser.set(new CurrentUser(9,"finance","财务", UserRole.FINANCE));

        Map<String,Object> suggestion = queries.refundSuggestion(20);
        assertThat(suggestion.get("suggestedAmount")).isEqualTo(new BigDecimal("60.0000"));

        long requestId = funds.submitAfterSalesRefund(20,new BigDecimal("70"),new BigDecimal("60"),"额外补偿");
        assertThat(jdbc.queryForObject("SELECT status FROM customer_fund_request WHERE id=?",String.class,requestId)).isEqualTo("PENDING");
        assertThat(funds.balance(1)).isZero();
    }
}