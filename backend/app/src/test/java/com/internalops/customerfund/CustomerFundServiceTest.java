package com.internalops.customerfund;

import com.internalops.auth.CurrentUser;
import com.internalops.auth.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerFundServiceTest {
    private JdbcTemplate jdbc;
    private CustomerFundService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("customer-fund-" + System.nanoTime())
                .addScript("customer-fund-schema.sql")
                .build();
        jdbc = new JdbcTemplate(dataSource);
        service = new CustomerFundService(jdbc);
        CurrentUser.set(new CurrentUser(9, "finance", "财务", UserRole.FINANCE));
    }

    @AfterEach
    void clearUser() { CurrentUser.clear(); }

    @Test
    void depositChangesBalanceOnlyAfterFinanceApprovesOwnRequest() {
        long requestId = service.submitDeposit(1, new CustomerFundRequestCommand(
                new BigDecimal("1000"), LocalDate.of(2026, 8, 14), "银行转账", "BANK-1", "首款"));

        assertThat(service.balance(1)).isEqualByComparingTo("0");
        service.review(requestId, new CustomerFundReviewCommand(true, "确认到账"));

        assertThat(service.balance(1)).isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_fund_ledger WHERE request_id=?", Integer.class, requestId)).isEqualTo(1);
        assertThatThrownBy(() -> service.review(requestId, new CustomerFundReviewCommand(true, "重复")))
                .hasMessageContaining("已处理");
    }

    @Test
    void orderDebitCannotExceedBalanceAndCreatesOutgoingLedger() {
        approveDeposit("500");

        assertThatThrownBy(() -> service.debitForOrder(10, new BigDecimal("501"), "客户余额"))
                .hasMessageContaining("客户余额不足");
        service.debitForOrder(10, new BigDecimal("200"), "客户余额");

        assertThat(service.balance(1)).isEqualByComparingTo("300");
        assertThat(jdbc.queryForObject("SELECT entry_type FROM customer_fund_ledger WHERE source_type='SALES_ORDER'", String.class))
                .isEqualTo("ORDER_RECEIPT");
    }

    @Test
    void approvedRefundCanDifferFromSuggestionWhenReasonIsRecordedAndCanBeReversed() {
        long requestId = service.submitAfterSalesRefund(20, new BigDecimal("90"), new BigDecimal("75"), "特殊补偿");
        service.review(requestId, new CustomerFundReviewCommand(true, "同意补偿"));
        long ledgerId = jdbc.queryForObject("SELECT id FROM customer_fund_ledger WHERE request_id=?", Long.class, requestId);

        service.reverse(ledgerId, new CustomerFundReversalCommand("金额登记错误"));

        assertThat(service.balance(1)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_fund_ledger WHERE reverses_ledger_id=?", Integer.class, ledgerId)).isEqualTo(1);
        assertThatThrownBy(() -> service.reverse(ledgerId, new CustomerFundReversalCommand("再次冲正")))
                .hasMessageContaining("已冲正");
    }

    @Test
    void nonFinanceUserCannotReview() {
        long requestId = service.submitDeposit(1, new CustomerFundRequestCommand(new BigDecimal("10"), LocalDate.now(), "现金", null, null));
        CurrentUser.set(new CurrentUser(3, "sales", "销售", UserRole.USER));
        assertThatThrownBy(() -> service.review(requestId, new CustomerFundReviewCommand(true, null)))
                .hasMessageContaining("财务或管理员");
    }

    private void approveDeposit(String amount) {
        long requestId = service.submitDeposit(1, new CustomerFundRequestCommand(new BigDecimal(amount), LocalDate.now(), "银行转账", null, null));
        service.review(requestId, new CustomerFundReviewCommand(true, null));
    }
}