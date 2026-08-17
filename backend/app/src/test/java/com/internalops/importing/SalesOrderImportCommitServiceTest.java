package com.internalops.importing;

import com.internalops.auth.CurrentUser;
import com.internalops.auth.UserRole;
import com.internalops.workbench.SalesOrderCommandService;
import com.internalops.workbench.SalesOrderRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest
@Sql(scripts = {"/import-schema.sql", "/sales-command-schema.sql", "/sales-order-import-commit-schema.sql"})
class SalesOrderImportCommitServiceTest {
    @Autowired ImportBatchRepository repository;
    @Autowired ImportCommitService commitService;
    @Autowired JdbcTemplate jdbc;
    @SpyBean SalesOrderCommandService salesOrders;

    @BeforeEach
    void useAdministrator() {
        CurrentUser.set(new CurrentUser(1, "admin", "管理员", UserRole.ADMIN));
    }

    @AfterEach
    void clearUser() {
        CurrentUser.clear();
    }

    @Test
    void financeCannotProgrammaticallyCommitOrderBatch() {
        long batch = batch("finance-forbidden", List.of(row(2,
                order("EXT-FINANCE-FORBIDDEN", "DRAFT", 1, 1, "12.50"))));
        CurrentUser.set(new CurrentUser(3, "finance", "财务", UserRole.FINANCE));

        SecurityException failure = assertThrows(SecurityException.class, () -> commitService.commit(batch));

        assertEquals("财务用户不能导入销售订单", failure.getMessage());
        assertEquals(0, count("sales_order"));
        assertEquals("PREVIEW", repository.status(batch));
    }

    @Test
    void groupsRowsByTrimmedExternalOrderNumberAndUsesSourceRowsAsLineNumbers() {
        long batch = batch("grouped", List.of(
                row(7, order(" EXT-100 ", "DRAFT", 1, 2, "12.50")),
                row(9, order("EXT-100", "DRAFT", 2, 3, "8.00"))));

        ImportBatchView committed = commitService.commit(batch);

        assertEquals(1, count("sales_order"));
        assertEquals(2, count("sales_order_item"));
        assertEquals(List.of(7, 9), jdbc.queryForList(
                "SELECT line_no FROM sales_order_item ORDER BY line_no", Integer.class));
        assertEquals("EXT-100", jdbc.queryForObject(
                "SELECT external_order_no FROM sales_order", String.class));
        assertEquals(2, committed.committedRows());
        Map<?, ?> result = (Map<?, ?>) committed.result();
        assertEquals(1, ((Number) result.get("createdOrders")).intValue());
        assertEquals(2, ((Number) result.get("committed")).intValue());
        assertEquals(0, ((Number) result.get("errors")).intValue());
    }

    @Test
    void formalOrderReusesInventoryAllocationAndLocksStock() {
        jdbc.update("INSERT INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,version) VALUES(1,1,10,0,0,0)");
        long batch = batch("formal", List.of(row(2,
                order("EXT-FORMAL", "PENDING_CUSTOMER_PAYMENT", 1, 4, "12.50"))));

        commitService.commit(batch);

        assertEquals(4, jdbc.queryForObject(
                "SELECT locked_quantity FROM inventory_balance WHERE warehouse_id=1 AND sku_id=1", Integer.class));
        assertEquals(4, jdbc.queryForObject(
                "SELECT locked_quantity FROM sales_order_item", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transaction WHERE transaction_type='ALLOCATE' AND business_type='SALES_ORDER'", Integer.class));
    }

    @Test
    void draftOrderDoesNotLockStock() {
        jdbc.update("INSERT INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,version) VALUES(1,1,10,0,0,0)");
        long batch = batch("draft", List.of(row(2, order("EXT-DRAFT", "DRAFT", 1, 4, "12.50"))));

        commitService.commit(batch);

        assertEquals(0, jdbc.queryForObject(
                "SELECT locked_quantity FROM inventory_balance WHERE warehouse_id=1 AND sku_id=1", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT locked_quantity FROM sales_order_item", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transaction WHERE transaction_type='ALLOCATE'", Integer.class));
    }

    @Test
    void importDoesNotWriteCustomerFundTables() {
        long batch = batch("funds", List.of(row(2,
                order("EXT-FUNDS", "PENDING_CUSTOMER_PAYMENT", 1, 1, "99.00"))));
        BigDecimal balanceBefore = jdbc.queryForObject(
                "SELECT balance FROM customer_fund_account WHERE customer_id=1", BigDecimal.class);
        int ledgerBefore = count("customer_fund_ledger");
        int requestBefore = count("customer_fund_request");
        List<Map<String, Object>> accountBefore = jdbc.queryForList(
                "SELECT customer_id,balance,version FROM customer_fund_account ORDER BY customer_id");
        List<Map<String, Object>> ledgerAmountsBefore = jdbc.queryForList(
                "SELECT id,customer_id,amount,balance_before,balance_after FROM customer_fund_ledger ORDER BY id");
        List<Map<String, Object>> requestAmountsBefore = jdbc.queryForList(
                "SELECT id,customer_id,amount FROM customer_fund_request ORDER BY id");

        commitService.commit(batch);

        assertEquals(0, balanceBefore.compareTo(jdbc.queryForObject(
                "SELECT balance FROM customer_fund_account WHERE customer_id=1", BigDecimal.class)));
        assertEquals(ledgerBefore, count("customer_fund_ledger"));
        assertEquals(requestBefore, count("customer_fund_request"));
        assertEquals(accountBefore, jdbc.queryForList(
                "SELECT customer_id,balance,version FROM customer_fund_account ORDER BY customer_id"));
        assertEquals(ledgerAmountsBefore, jdbc.queryForList(
                "SELECT id,customer_id,amount,balance_before,balance_after FROM customer_fund_ledger ORDER BY id"));
        assertEquals(requestAmountsBefore, jdbc.queryForList(
                "SELECT id,customer_id,amount FROM customer_fund_request ORDER BY id"));
    }

    @Test
    void roundsImportedSalePriceToTheOrderCurrencyScale() {
        long batch = batch("price-scale", List.of(row(2,
                order("EXT-PRICE", "DRAFT", 1, 1, "12.345"))));

        commitService.commit(batch);

        assertEquals(0, new BigDecimal("12.35").compareTo(jdbc.queryForObject(
                "SELECT sale_price FROM sales_order_item", BigDecimal.class)));
        assertEquals(0, new BigDecimal("12.35").compareTo(jdbc.queryForObject(
                "SELECT total_amount FROM sales_order", BigDecimal.class)));
    }

    @Test
    void duplicateAppearingAfterPreviewReportsFailedOrderWithoutInventoryLocks() {
        jdbc.update("INSERT INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,version) VALUES(1,1,10,0,0,0)");
        long batch = batch("late-duplicate", List.of(row(2,
                order("EXT-DUP", "PENDING_CUSTOMER_PAYMENT", 1, 4, "12.50"))));
        insertExistingOrder("EXT-DUP");

        ImportBatchView result = commitService.commit(batch);

        assertEquals(1, count("sales_order"));
        assertEquals(0, count("sales_order_item"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT locked_quantity FROM inventory_balance WHERE warehouse_id=1 AND sku_id=1", Integer.class));
        assertEquals("COMMITTED", repository.status(batch));
        assertEquals(0, result.committedRows());
        assertEquals(1, result.errorRows());
        assertEquals("外部订单号已存在，订单提交已取消", result.rows().get(0).errorMessage());
    }

    @Test
    void concurrentBatchesWithTheSameExternalOrderNumberReportOneSuccessAndOneFailure() throws Exception {
        jdbc.update("INSERT INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,version) VALUES(1,1,10,0,0,0)");
        long firstBatch = batch("concurrent-first", List.of(row(2,
                order("EXT-CONCURRENT", "PENDING_CUSTOMER_PAYMENT", 1, 4, "12.50"))));
        long secondBatch = batch("concurrent-second", List.of(row(2,
                order("EXT-CONCURRENT", "PENDING_CUSTOMER_PAYMENT", 1, 4, "12.50"))));
        CyclicBarrier bothPassedDuplicateCheck = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothPassedDuplicateCheck.await(5, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(salesOrders).create(any(SalesOrderRequest.class));

        Throwable firstFailure;
        Throwable secondFailure;
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> commitFailure(firstBatch));
            var second = executor.submit(() -> commitFailure(secondBatch));
            firstFailure = first.get(15, TimeUnit.SECONDS);
            secondFailure = second.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            reset(salesOrders);
        }

        assertEquals(null, firstFailure);
        assertEquals(null, secondFailure);
        assertEquals(1, count("sales_order"));
        assertEquals(1, count("sales_order_item"));
        assertEquals(4, jdbc.queryForObject(
                "SELECT locked_quantity FROM inventory_balance WHERE warehouse_id=1 AND sku_id=1", Integer.class));
        assertEquals(List.of("COMMITTED", "COMMITTED"), List.of(
                repository.status(firstBatch), repository.status(secondBatch)).stream().sorted().toList());
        List<ImportBatchView> results = List.of(repository.findBatch(firstBatch), repository.findBatch(secondBatch));
        assertEquals(List.of(0, 1), results.stream().map(ImportBatchView::committedRows).sorted().toList());
        assertEquals(List.of(0, 1), results.stream()
                .map(result -> ((Number) ((Map<?, ?>) result.result()).get("failedOrders")).intValue())
                .sorted().toList());
    }

    @Test
    void failureInSecondOrderKeepsFirstOrderAndReportsTheFailedOrder() {
        jdbc.update("INSERT INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,version) VALUES(1,1,10,0,0,0)");
        long batch = batch("second-fails", List.of(
                row(2, order("EXT-FIRST", "PENDING_CUSTOMER_PAYMENT", 1, 4, "12.50")),
                row(3, order("EXT-SECOND", "DRAFT", 2, 1, "8.00"))));
        insertExistingOrder("EXT-SECOND");

        ImportBatchView result = commitService.commit(batch);

        assertEquals(List.of("EXT-SECOND", "EXT-FIRST"), jdbc.queryForList(
                "SELECT external_order_no FROM sales_order ORDER BY id", String.class));
        assertEquals(1, count("sales_order_item"));
        assertEquals(4, jdbc.queryForObject(
                "SELECT locked_quantity FROM inventory_balance WHERE warehouse_id=1 AND sku_id=1", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transaction WHERE transaction_type='ALLOCATE'", Integer.class));
        assertEquals("COMMITTED", repository.status(batch));
        assertEquals(1, result.committedRows());
        assertEquals(1, result.errorRows());
        Map<?, ?> detail = (Map<?, ?>) result.result();
        assertEquals(1, ((Number) detail.get("createdOrders")).intValue());
        assertEquals(1, ((Number) detail.get("failedOrders")).intValue());
        assertEquals(1, ((Number) detail.get("committed")).intValue());
        assertEquals(1, ((Number) detail.get("errors")).intValue());
        assertEquals(ImportRowStatus.ERROR, result.rows().get(1).status());
        assertEquals("外部订单号已存在，订单提交已取消", result.rows().get(1).errorMessage());
    }

    @Test
    void rejectsBatchContainingErrorRows() {
        long batch = repository.create(ImportType.ORDER, "orders.xlsx", "order-error", List.of(
                row(2, order("EXT-VALID", "DRAFT", 1, 1, "12.50")),
                new ParsedImportRow("订单导入", 3, ImportRowStatus.ERROR,
                        order("EXT-ERROR", "DRAFT", 2, 1, "8.00"), "产品编号不存在")));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> commitService.commit(batch));

        assertEquals("订单导入批次存在错误行", failure.getMessage());
        assertEquals(0, count("sales_order"));
        assertEquals("PREVIEW", repository.status(batch));
    }

    @Test
    void rejectsRepeatedCommitOfTheSameOrderBatch() {
        long batch = batch("repeat", List.of(row(2, order("EXT-REPEAT", "DRAFT", 1, 1, "12.50"))));
        commitService.commit(batch);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> commitService.commit(batch));

        assertEquals("订单导入批次已提交，不可重复提交", failure.getMessage());
        assertEquals(1, count("sales_order"));
        assertEquals(1, count("sales_order_item"));
    }

    private long batch(String hash, List<ParsedImportRow> rows) {
        return repository.create(ImportType.ORDER, "orders.xlsx", "order-" + hash, rows);
    }

    private ParsedImportRow row(int sourceRow, Map<String, Object> data) {
        return new ParsedImportRow("订单导入", sourceRow, ImportRowStatus.VALID, data, null);
    }

    private Map<String, Object> order(String externalOrderNo, String status, long skuId,
                                      int quantity, String salePrice) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("externalOrderNo", externalOrderNo);
        data.put("customerCode", "C1");
        data.put("orderDate", "2026-08-17");
        data.put("orderType", "工程订单");
        data.put("orderStatus", "DRAFT".equals(status) ? "草稿" : "正式订单");
        data.put("salesperson", "Sales Zhang");
        data.put("businessContactName", "Business Contact");
        data.put("businessContactPhone", "13700137000");
        data.put("orderContactName", "Order Contact");
        data.put("orderContactPhone", "13800138000");
        data.put("financeContactName", "Finance Contact");
        data.put("financeContactPhone", "13900139000");
        data.put("deliveryAddress", "Shenzhen");
        data.put("deliveryContact", "Receiver");
        data.put("deliveryPhone", "13600136000");
        data.put("shippingMethod", "Logistics");
        data.put("remark", "Imported order");
        data.put("quantity", quantity);
        data.put("salePrice", new BigDecimal(salePrice));
        data.put("_customerId", 1L);
        data.put("_skuId", skuId);
        data.put("_normalizedStatus", status);
        return data;
    }

    private void insertExistingOrder(String externalOrderNo) {
        jdbc.update("INSERT INTO sales_order(order_no,external_order_no,customer_id,status,total_amount,order_date) VALUES(?,?,?,?,?,?)",
                "EXISTING-" + externalOrderNo, externalOrderNo, 1L, "DRAFT", BigDecimal.ZERO,
                java.time.LocalDate.of(2026, 8, 17));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private Throwable commitFailure(long batch) {
        CurrentUser.set(new CurrentUser(1, "admin", "管理员", UserRole.ADMIN));
        try {
            commitService.commit(batch);
            return null;
        } catch (Throwable failure) {
            return failure;
        } finally {
            CurrentUser.clear();
        }
    }
}
