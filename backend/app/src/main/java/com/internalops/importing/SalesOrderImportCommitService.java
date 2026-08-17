package com.internalops.importing;

import com.internalops.workbench.SalesOrderCommandService;
import com.internalops.workbench.SalesOrderRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public SalesOrderImportCommitService(JdbcTemplate jdbc, ImportBatchRepository repository,
                                         SalesOrderCommandService salesOrders) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.salesOrders = salesOrders;
    }

    @Transactional
    public ImportBatchView commit(ImportBatchView batch) {
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

        Map<String, List<ImportRowView>> rowsByOrder = new LinkedHashMap<>();
        for (ImportRowView row : batch.rows()) {
            if (row.status() != ImportRowStatus.VALID) continue;
            String externalOrderNo = text(row.data(), "externalOrderNo");
            rowsByOrder.computeIfAbsent(externalOrderNo, ignored -> new ArrayList<>()).add(row);
        }

        for (Map.Entry<String, List<ImportRowView>> order : rowsByOrder.entrySet()) {
            lockAndRejectExistingOrder(order.getKey());
            salesOrders.create(request(order.getKey(), order.getValue()));
        }

        int committedRows = rowsByOrder.values().stream().mapToInt(List::size).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("createdOrders", rowsByOrder.size());
        result.put("committed", committedRows);
        result.put("errors", 0);
        repository.markCommitted(batch.batchId(), committedRows, result);
        return repository.findBatch(batch.batchId());
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
}
