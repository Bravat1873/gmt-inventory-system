package com.internalops.customerfund;

import com.internalops.auth.CurrentUser;
import com.internalops.auth.UserRole;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CustomerFundsManagementTest {
    EmbeddedDatabase db; JdbcTemplate jdbc; CustomerFundService commands; CustomerFundsManagementQueryService queries; MockMvc mvc;
    @BeforeEach void setup(){
        db=new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).setName("fund-management-"+System.nanoTime()).addScript("customer-fund-schema.sql").build();
        jdbc=new JdbcTemplate(db);commands=new CustomerFundService(jdbc);queries=new CustomerFundsManagementQueryService(jdbc);
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        json.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc=MockMvcBuilders.standaloneSetup(new CustomerFundsManagementController(queries))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json)).build();
        CurrentUser.set(new CurrentUser(9,"finance","财务",UserRole.FINANCE));
    }
    @AfterEach void cleanup(){CurrentUser.clear();db.shutdown();}
    long deposit(String value){return commands.submitDeposit(1,new CustomerFundRequestCommand(new BigDecimal(value),LocalDate.of(2026,10,12),"银行转账","BANK-1","登记备注",10L,LocalDateTime.of(2026,10,12,9,30,15)));}

    @Test void globalListCustomerWindowAndConfirmationUseTheSameOperation() throws Exception {
        long id=deposit("100");
        mvc.perform(get("/api/customer-funds/operations").param("customerId","1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].amount").value(100))
                .andExpect(jsonPath("$.data.items[0].customerCode").value("C001"))
                .andExpect(jsonPath("$.data.items[0].submittedByName").value("财务"))
                .andExpect(jsonPath("$.data.items[0].paymentAt").value("2026-10-12T09:30:15"))
                .andExpect(jsonPath("$.data.items[0].sourceNo").value("SO-001"));
        String number=jdbc.queryForObject("SELECT operation_no FROM customer_fund_request WHERE id=?",String.class,id);
        assertThat(number).matches("ZJ\\d{11,}");
        assertThat(commands.balance(1)).isEqualByComparingTo("0");
        commands.review(id,new CustomerFundReviewCommand(true,"银行实际到账金额",new BigDecimal("90")));
        assertThat(commands.balance(1)).isEqualByComparingTo("90");
        var records=new CustomerFundQueryService(jdbc).requests(1);
        assertThat(records.get(0)).containsEntry("operationNo",number).containsEntry("submittedByName","财务").containsEntry("reviewedByName","财务");
        assertThat((BigDecimal)records.get(0).get("confirmedAmount")).isEqualByComparingTo("90");
        mvc.perform(get("/api/customer-funds/operations")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].confirmedAmount").value(90))
                .andExpect(jsonPath("$.data.summary.confirmedReceipt").value(90));
        assertThatThrownBy(()->commands.review(id,new CustomerFundReviewCommand(true,"重复"))).hasMessageContaining("已处理");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_fund_ledger WHERE request_id=?",Integer.class,id)).isEqualTo(1);
    }

    @Test void pendingReceiptsAndRefundsAreSeparateAndRefundUsesConfirmedAmount(){
        long original=deposit("500");commands.review(original,new CustomerFundReviewCommand(true,"到账"));
        long incoming=deposit("30");long outgoing=deposit("-30");
        var overview=new CustomerFundQueryService(jdbc).overview(1);
        assertThat((BigDecimal)overview.get("pendingReceipt")).isEqualByComparingTo("30");
        assertThat((BigDecimal)overview.get("pendingRefund")).isEqualByComparingTo("30");
        assertThatThrownBy(()->commands.review(outgoing,new CustomerFundReviewCommand(true,"",new BigDecimal("20")))).hasMessageContaining("原因");
        assertThat(commands.balance(1)).isEqualByComparingTo("500");
        commands.review(outgoing,new CustomerFundReviewCommand(true,"确认退回20",new BigDecimal("20")));
        assertThat(commands.balance(1)).isEqualByComparingTo("480");
        long ledger=jdbc.queryForObject("SELECT id FROM customer_fund_ledger WHERE request_id=?",Long.class,outgoing);
        commands.reverse(ledger,new CustomerFundReversalCommand("重复退款登记"));
        assertThat(commands.balance(1)).isEqualByComparingTo("500");
        assertThatThrownBy(()->commands.reverse(ledger,new CustomerFundReversalCommand("重复"))).hasMessageContaining("已冲正");
        assertThatThrownBy(()->commands.review(incoming,new CustomerFundReviewCommand(false,""))).hasMessageContaining("原因");
        commands.review(incoming,new CustomerFundReviewCommand(false,"流水不符"));
        assertThat(commands.balance(1)).isEqualByComparingTo("500");
    }

    @Test void ledgerDoesNotCountOrderDeductionsAsNewBankReceipts() throws Exception {
        long id=deposit("100");commands.review(id,new CustomerFundReviewCommand(true,"到账"));
        commands.debitForOrder(10,new BigDecimal("20"),"订单抵扣");
        long internal=commands.submitAfterSalesRefund(20,new BigDecimal("10"),new BigDecimal("10"),null);
        commands.review(internal,new CustomerFundReviewCommand(true,"售后退回余额"));
        mvc.perform(get("/api/customer-funds/operations")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.summary.confirmedReceipt").value(100));
        mvc.perform(get("/api/customer-funds/ledger")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3));
        assertThat(commands.balance(1)).isEqualByComparingTo("90");
    }

    @Test void supportsStablePaginationCustomerFiltersAndRejectsInvalidFilters() throws Exception {
        for(int i=0;i<12;i++)deposit("1");
        mvc.perform(get("/api/customer-funds/operations").param("page","2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(12)).andExpect(jsonPath("$.data.items.length()").value(2));
        mvc.perform(get("/api/customer-funds/operations").param("customerId","2")).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/customer-funds/operations").param("keyword","C001")).andExpect(jsonPath("$.data.total").value(12));
        mvc.perform(get("/api/customer-funds/operations").param("status","APPROVED")).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/customer-funds/operations").param("direction","bad")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/customer-funds/operations").param("from","2026-10-13").param("to","2026-10-12")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/customer-funds/customers")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].customerCode").exists());
    }

    @Test void rejectsExcessPrecisionAndInsufficientBalanceWithoutChangingState(){
        assertThatThrownBy(()->deposit("0.00001")).hasMessageContaining("四位");
        long id=deposit("-10");
        assertThatThrownBy(()->commands.review(id,new CustomerFundReviewCommand(true,"退款"))).hasMessageContaining("余额不足");
        assertThat(jdbc.queryForObject("SELECT status FROM customer_fund_request WHERE id=?",String.class,id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_fund_ledger",Integer.class)).isZero();
        CurrentUser.set(new CurrentUser(3,"sales","销售",UserRole.USER));
        assertThatThrownBy(()->commands.review(id,new CustomerFundReviewCommand(true,""))).hasMessageContaining("财务或管理员");
    }

    @Test void fundSourcePersistsAndFiltersWithoutGuessingHistoricalValues() throws Exception {
        deposit("50");
        long foreign=commands.submitDeposit(1,new CustomerFundRequestCommand(new BigDecimal("80"),LocalDate.now(),
                "银行转账",null,null,null,null,"FOREX"));
        mvc.perform(get("/api/customer-funds/operations").param("fundSource","FOREX"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].fundSource").value("FOREX"))
                .andExpect(jsonPath("$.data.summary.pendingReceipt").value(80));
        mvc.perform(get("/api/customer-funds/operations").param("fundSource","DOMESTIC"))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].fundSource").value("DOMESTIC"));
        assertThat(new CustomerFundQueryService(jdbc).requests(1).get(0)).containsEntry("fundSource","FOREX");
        commands.review(foreign,new CustomerFundReviewCommand(true,"确认到账"));
        mvc.perform(get("/api/customer-funds/ledger").param("fundSource","FOREX"))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].fundSource").value("FOREX"));
        long original=jdbc.queryForObject("SELECT id FROM customer_fund_ledger WHERE request_id=?",Long.class,foreign);
        commands.reverse(original,new CustomerFundReversalCommand("外汇金额登记错误"));
        mvc.perform(get("/api/customer-funds/operations").param("fundSource","FOREX"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("REVERSED"))
                .andExpect(jsonPath("$.data.items[0].reviewComment").value("确认到账"))
                .andExpect(jsonPath("$.data.items[0].reversalReason").value("外汇金额登记错误"))
                .andExpect(jsonPath("$.data.items[0].reversalByName").value("财务"))
                .andExpect(jsonPath("$.data.items[0].reversalAt").isNotEmpty())
                .andExpect(jsonPath("$.data.summary.confirmedReceipt").value(0));
        mvc.perform(get("/api/customer-funds/ledger").param("fundSource","FOREX"))
                .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.items[0].fundSource").value("FOREX"))
                .andExpect(jsonPath("$.data.items[0].reversalReason").value("外汇金额登记错误"))
                .andExpect(jsonPath("$.data.items[1].reversalReason").value("外汇金额登记错误"));
        jdbc.update("UPDATE customer_fund_request SET fund_source=NULL WHERE id=?",foreign);
        mvc.perform(get("/api/customer-funds/operations").param("fundSource","FOREX")).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/customer-funds/operations").param("fundSource","INVALID")).andExpect(status().isBadRequest());
        assertThatThrownBy(()->commands.submitDeposit(1,new CustomerFundRequestCommand(new BigDecimal("10"),LocalDate.now(),
                "银行转账",null,null,null,null,null))).hasMessageContaining("外汇或国内");
    }
}
