package com.internalops.importing;

import com.internalops.workbench.SalesOrderCommandService;
import com.internalops.workbench.SalesOrderRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SalesOrderImportCommitService {
    private final JdbcTemplate jdbc;
    private final ImportBatchRepository repository;
    private final SalesOrderCommandService salesOrders;
    private final TransactionTemplate transactions;
    private final TransactionTemplate orderTransactions;

    public SalesOrderImportCommitService(JdbcTemplate jdbc, ImportBatchRepository repository,
                                         SalesOrderCommandService salesOrders,
                                         PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.salesOrders = salesOrders;
        this.transactions = new TransactionTemplate(transactionManager);
        this.orderTransactions = new TransactionTemplate(transactionManager);
        this.orderTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ImportBatchView commit(long batchId) {
        ImportBatchView batch = transactions.execute(status -> claim(batchId));
        if (batch == null) throw new IllegalStateException("订单导入批次读取失败");

        Map<String, List<ImportRowView>> rowsByOrder = groupRows(batch);
        List<OrderFailure> failures = new ArrayList<>();
        int committedRows = 0;
        int createdOrders = 0;
        for (Map.Entry<String, List<ImportRowView>> order : rowsByOrder.entrySet()) {
            try {
                orderTransactions.executeWithoutResult(status -> commitOrder(order.getKey(), order.getValue()));
                committedRows += order.getValue().size();
                createdOrders++;
            } catch (RuntimeException failure) {
                failures.add(new OrderFailure(order.getKey(), order.getValue(), failureMessage(failure)));
            }
        }

        int finalCommittedRows = committedRows;
        int finalCreatedOrders = createdOrders;
        return transactions.execute(status -> finish(batchId, finalCommittedRows, finalCreatedOrders, failures));
    }

    private ImportBatchView claim(long batchId) {
        ImportBatchView batch = repository.findBatchForUpdate(batchId);
        if (batch.importType() != ImportType.ORDER) {
            throw new IllegalArgumentException("仅支持订单导入批次");
        }
        if ("COMMITTED".equals(batch.status())) {
            throw new IllegalStateException("订单导入批次已提交，不可重复提交");
        }
        if (!"PREVIEW".equals(batch.status())) {
            throw new IllegalStateException("订单导入批次状态不允许提交");
        }
        if (batch.errorRows() > 0) {
            throw new IllegalStateException("订单导入批次存在错误行");
        }
        if (batch.validRows() <= 0 || groupRows(batch).isEmpty()) {
            throw new IllegalStateException("订单导入批次没有有效订单");
        }
        repository.markCommitting(batchId);
        return batch;
    }

    private Map<String, List<ImportRowView>> groupRows(ImportBatchView batch) {
        Map<String, List<ImportRowView>> rowsByOrder = new LinkedHashMap<>();
        for (ImportRowView row : batch.rows()) {
            if (row.status() != ImportRowStatus.VALID) continue;
            String externalOrderNo = text(row.data(), "externalOrderNo");
            rowsByOrder.computeIfAbsent(externalOrderNo, ignored -> new ArrayList<>()).add(row);
        }

        return rowsByOrder;
    }

    private void commitOrder(String externalOrderNo, List<ImportRowView> rows) {
        lockAndRejectExistingOrder(externalOrderNo);
        salesOrders.create(request(externalOrderNo, rows));
    }

    private ImportBatchView finish(long batchId, int committedRows, int createdOrders,
                                   List<OrderFailure> failures) {
        ImportBatchView batch = repository.findBatchForUpdate(batchId);
        if (!"COMMITTING".equals(batch.status())) {
            throw new IllegalStateException("订单导入批次状态不允许完成提交");
        }
        int errorRows = 0;
        List<Map<String, Object>> orderErrors = new ArrayList<>();
        for (OrderFailure failure : failures) {
            errorRows += failure.rows().size();
            for (ImportRowView row : failure.rows()) {
                repository.updateRow(batchId, row.id(), new ParsedImportRow(
                        row.sheetName(), row.rowNumber(), ImportRowStatus.ERROR, row.data(), failure.message()));
            }
            orderErrors.add(Map.of("externalOrderNo", failure.externalOrderNo(), "error", failure.message()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("createdOrders", createdOrders);
        result.put("failedOrders", failures.size());
        result.put("committed", committedRows);
        result.put("errors", errorRows);
        result.put("orderErrors", orderErrors);
        repository.markCommitted(batch.batchId(), committedRows, result);
        return repository.findBatch(batch.batchId());
    }

    private String failureMessage(RuntimeException failure) {
        String message = failure.getMessage();
        if (message != null && message.contains("外部订单号已存在")) return "外部订单号已存在，订单提交已取消";
        if (isDuplicateKeyFailure(failure)) return "外部订单号已存在，订单提交已取消";
        return message == null || message.isBlank() ? "订单提交失败" : message;
    }

    private boolean isDuplicateKeyFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String name = current.getClass().getSimpleName();
            if (name.contains("DuplicateKey") || name.contains("ConstraintViolation")) return true;
        }
        return false;
    }

    private void lockAndRejectExistingOrder(String externalOrderNo) {
        List<Long> existing = jdbc.queryForList(
                "SELECT id FROM sales_order WHERE external_order_no=? FOR UPDATE", Long.class, externalOrderNo);
        if (!existing.isEmpty()) {
            throw new IllegalStateException("外部订单号已存在，订单提交已取消");
        }
    }

    private SalesOrderRequest request(String externalOrderNo, List<ImportRowView> rows) {
        Map<String, Object> first = rows.get(0).data();
        List<SalesOrderRequest.Item> items = rows.stream()
                .map(row -> new SalesOrderRequest.Item(
                        row.rowNumber(), requiredLong(row.data(), "_skuId"),
                        requiredInt(row.data(), "quantity"), requiredDecimal(row.data(), "salePrice")))
                .toList();
        String orderContactName = text(first, "orderContactName");
        String orderContactPhone = text(first, "orderContactPhone");
        return new SalesOrderRequest(
                requiredLong(first, "_customerId"), externalOrderNo,
                LocalDate.parse(text(first, "orderDate")), text(first, "orderType"),
                text(first, "_normalizedStatus"), text(first, "salesperson"),
                orderContactName, orderContactPhone,
                text(first, "businessContactName"), text(first, "businessContactPhone"),
                orderContactName, orderContactPhone,
                text(first, "financeContactName"), text(first, "financeContactPhone"),
                text(first, "remark"), text(first, "deliveryAddress"),
                text(first, "deliveryContact"), text(first, "deliveryPhone"),
                text(first, "shippingMethod"), null, items);
    }

    private String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private long requiredLong(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number number) return number.longValue();
        throw new IllegalStateException("订单导入行缺少已解析字段 " + key);
    }

    private int requiredInt(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number number) return number.intValue();
        throw new IllegalStateException("订单导入行缺少已解析字段 " + key);
    }

    private BigDecimal requiredDecimal(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof BigDecimal decimal) return decimal.setScale(2, RoundingMode.HALF_UP);
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).setScale(2, RoundingMode.HALF_UP);
        }
        throw new IllegalStateException("订单导入行缺少已解析字段 " + key);
    }

    private record OrderFailure(String externalOrderNo, List<ImportRowView> rows, String message) { }
}
