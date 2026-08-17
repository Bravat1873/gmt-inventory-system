package com.internalops.importing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SalesOrderImportValidationServiceTest {
    private JdbcTemplate jdbc;
    private SalesOrderImportValidationService validation;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:order-import;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("DROP TABLE IF EXISTS sku");
        jdbc.execute("DROP TABLE IF EXISTS customer");
        jdbc.execute("CREATE TABLE customer (id BIGINT PRIMARY KEY, customer_code VARCHAR(64), enabled BOOLEAN)");
        jdbc.execute("CREATE TABLE sku (id BIGINT PRIMARY KEY, product_code VARCHAR(64), sku_code VARCHAR(64), enabled BOOLEAN, sales_minimum_order_quantity DECIMAL(18,2))");
        validation = new SalesOrderImportValidationService(jdbc);
    }

    @Test
    void resolvesUniqueEnabledCustomerAndProductAndAddsNormalizedOrderData() {
        customer(1, "C-001", true);
        sku(10, "P-001", "CM-001", true, 2);

        ParsedImportRow result = validation.validateAll(List.of(row(Map.of()))).get(0);

        assertThat(result.status()).isEqualTo(ImportRowStatus.VALID);
        assertThat(result.data()).containsEntry("_customerId", 1L)
                .containsEntry("_skuId", 10L)
                .containsEntry("_normalizedStatus", "PENDING_CUSTOMER_PAYMENT")
                .containsEntry("quantity", 2)
                .containsEntry("salePrice", new BigDecimal("10.50"));
    }

    @Test
    void rejectsMissingDuplicateOrDisabledCustomerAndProductMatches() {
        assertThat(validation.validateAll(List.of(row(Map.of()))).get(0).errorMessage()).contains("客户编码");
        customer(1, "C-001", true);
        customer(2, "C-001", true);
        assertThat(validation.validateAll(List.of(row(Map.of()))).get(0).errorMessage()).contains("客户编码").contains("唯一");
        jdbc.update("DELETE FROM customer");
        customer(1, "C-001", false);
        assertThat(validation.validateAll(List.of(row(Map.of()))).get(0).errorMessage()).contains("客户编码");
        jdbc.update("DELETE FROM customer");
        customer(1, "C-001", true);
        assertThat(validation.validateAll(List.of(row(Map.of()))).get(0).errorMessage()).contains("产品编号");
        sku(10, "P-001", "CM-001", true, 1);
        sku(11, "P-001", "CM-001", true, 1);
        assertThat(validation.validateAll(List.of(row(Map.of()))).get(0).errorMessage()).contains("产品编号").contains("唯一");
        jdbc.update("DELETE FROM sku");
        sku(10, "P-001", "CM-001", false, 1);
        assertThat(validation.validateAll(List.of(row(Map.of()))).get(0).errorMessage()).contains("产品编号");
    }

    @Test
    void rejectsInvalidDateTypeStatusQuantityAndPrice() {
        customer(1, "C-001", true);
        sku(10, "P-001", "CM-001", true, 2);
        assertError(Map.of("orderDate", "not-a-date"), "订单日期");
        assertError(Map.of("orderType", "无效类型"), "订单类型");
        assertError(Map.of("orderStatus", "已完成"), "订单状态");
        assertError(Map.of("quantity", "1.5"), "订单数量");
        assertError(Map.of("quantity", "0"), "订单数量");
        assertError(Map.of("salePrice", ""), "含税单价");
        assertError(Map.of("salePrice", "-0.01"), "含税单价");
    }

    @Test
    void rejectsQuantityBelowSalesMinimumAndMismatchedCustomerMaterialCode() {
        customer(1, "C-001", true);
        sku(10, "P-001", "CM-001", true, 3);
        assertError(Map.of("quantity", "2"), "销售最小起订量");
        assertError(Map.of("quantity", "3", "customerMaterialCode", "WRONG"), "客户料号");
    }

    private void assertError(Map<String, Object> overrides, String expectedMessage) {
        ParsedImportRow result = validation.validateAll(List.of(row(overrides))).get(0);
        assertThat(result.status()).isEqualTo(ImportRowStatus.ERROR);
        assertThat(result.errorMessage()).contains(expectedMessage);
    }

    private ParsedImportRow row(Map<String, Object> overrides) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("externalOrderNo", "EXT-001");
        data.put("customerCode", "C-001");
        data.put("orderDate", "2026-08-17");
        data.put("orderType", "工程订单");
        data.put("orderStatus", "正式订单");
        data.put("salesperson", "张三");
        data.put("customerMaterialCode", "CM-001");
        data.put("productCode", "P-001");
        data.put("quantity", "2");
        data.put("salePrice", "10.50");
        data.putAll(overrides);
        return new ParsedImportRow("订单导入", 2, ImportRowStatus.VALID, data, null);
    }

    private void customer(long id, String code, boolean enabled) {
        jdbc.update("INSERT INTO customer(id, customer_code, enabled) VALUES (?, ?, ?)", id, code, enabled);
    }

    private void sku(long id, String productCode, String skuCode, boolean enabled, int minimumQuantity) {
        jdbc.update("INSERT INTO sku(id, product_code, sku_code, enabled, sales_minimum_order_quantity) VALUES (?, ?, ?, ?, ?)",
                id, productCode, skuCode, enabled, minimumQuantity);
    }
}
