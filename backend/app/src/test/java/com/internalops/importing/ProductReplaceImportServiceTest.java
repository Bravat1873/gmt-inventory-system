package com.internalops.importing;

import com.internalops.auth.CurrentUser;
import com.internalops.auth.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "app.import.product-replace-enabled=true")
@Sql(scripts = {"/import-schema.sql", "/product-replace-import-schema.sql"})
class ProductReplaceImportServiceTest {
    @Autowired ImportBatchRepository repository;
    @Autowired ProductReplaceImportService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void loginAsAdminAndSeedProductDomain() {
        CurrentUser.set(new CurrentUser(1, "admin", "管理员", UserRole.ADMIN));
        jdbc.update("INSERT INTO sku(id,product_code,product_name) VALUES(1,'OLD-1','旧产品')");
        jdbc.update("INSERT INTO sales_order(id,customer_id,order_no) VALUES(1,1,'SO-1')");
        jdbc.update("INSERT INTO sales_order_item(id,sales_order_id,sku_id) VALUES(1,1,1)");
        jdbc.update("INSERT INTO inventory_balance(id,sku_id) VALUES(1,1)");
        jdbc.update("INSERT INTO inventory_locked_allocation(inventory_balance_id) VALUES(1)");
        jdbc.update("INSERT INTO inventory_transaction(sku_id) VALUES(1)");
        jdbc.update("INSERT INTO sku_cost_history(sku_id) VALUES(1)");
        jdbc.update("INSERT INTO product_image(product_id) VALUES(1)");
    }

    @AfterEach
    void clearUser() { CurrentUser.clear(); }

    @Test
    void rejectsReplaceWhenFeatureFlagIsDisabled() {
        ProductReplaceImportService disabled = new ProductReplaceImportService(jdbc, repository,
                new ProductImportCodeResolver(jdbc, new com.internalops.productcode.ProductCodeGenerator(jdbc)), false);
        long batchId = batch(List.of(validRow("A")));

        assertThatThrownBy(() -> disabled.replace(repository.findBatchForUpdate(batchId), Map.of()))
                .hasMessageContaining("未启用产品全量替换");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku", Integer.class)).isOne();
    }

    @Test
    void rejectsNonAdminBeforeDeletingAnything() {
        CurrentUser.set(new CurrentUser(2, "finance", "财务", UserRole.FINANCE));
        long batchId = batch(List.of(validRow("A")));

        assertThatThrownBy(() -> service.replace(repository.findBatchForUpdate(batchId), Map.of()))
                .hasMessageContaining("仅管理员");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku", Integer.class)).isOne();
    }

    @Test
    void rejectsErrorsRuleChangesAndUnresolvedDuplicateGroupsBeforeDeleting() {
        long errorBatch = repository.create(ImportType.PRODUCT, "bad.xlsx", "bad", List.of(
                new ParsedImportRow("GMT库存产品清单", 9, ImportRowStatus.ERROR, Map.of(), "编码错误")));
        assertThatThrownBy(() -> service.replace(repository.findBatchForUpdate(errorBatch), Map.of()))
                .hasMessageContaining("错误行");

        Map<String,Object> changed = validData("A");
        changed.put("_ruleFingerprint", "changed");
        long changedBatch = batch(List.of(new ParsedImportRow("GMT库存产品清单", 9, ImportRowStatus.VALID, changed, null)));
        assertThatThrownBy(() -> service.replace(repository.findBatchForUpdate(changedBatch), Map.of()))
                .hasMessageContaining("编码规则已变化");

        long duplicateBatch = batch(List.of(validRow("A"), validRow("A")));
        assertThatThrownBy(() -> service.replace(repository.findBatchForUpdate(duplicateBatch), Map.of()))
                .hasMessageContaining("冲突");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku", Integer.class)).isOne();
    }

    @Test
    void rejectsActionsForRowsOutsideTheBatch() {
        long batchId = batch(List.of(validRow("A"), validRow("A")));
        assertThatThrownBy(() -> service.replace(repository.findBatchForUpdate(batchId), Map.of(999L, ProductConflictAction.KEEP)))
                .hasMessageContaining("不属于当前批次");
    }

    @Test
    void deletesProductDependenciesInForeignKeyOrderAndPreservesMasterData() {
        long batchId = batch(List.of(validRow("A")));

        ImportBatchView result = service.replace(repository.findBatchForUpdate(batchId), Map.of());

        assertThat(result.status()).isEqualTo("COMMITTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT product_code FROM sku", String.class)).isEqualTo("BR_P90YZH70WPZC-A");
        assertThat(jdbc.queryForObject("SELECT sku_code FROM sku", String.class)).isEqualTo("CUS-P90");
        assertThat(jdbc.queryForObject("SELECT product_type FROM sku", String.class)).isEqualTo("SMART_LOCK");
        assertThat(jdbc.queryForObject("SELECT configuration FROM sku", String.class)).isEqualTo("测试规格");
        assertThat(jdbc.queryForObject("SELECT product_configuration FROM sku", String.class)).isEqualTo("测试配置");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_order", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_balance", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplier", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_code_rule", Integer.class)).isEqualTo(8);
    }

    @Test
    void keepsExactlyOneDuplicateAndSkipsTheOther() {
        long batchId = batch(List.of(validRow("A"), validRow("A")));
        List<ImportRowView> rows = repository.findBatch(batchId).rows();

        service.replace(repository.findBatchForUpdate(batchId), Map.of(
                rows.get(0).id(), ProductConflictAction.KEEP,
                rows.get(1).id(), ProductConflictAction.SKIP));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku", Integer.class)).isOne();
    }

    @Test
    void rollsBackDeletesWhenSupplierLookupFailsDuringInsert() {
        Map<String,Object> row = validData("A");
        row.put("supplierName", "不存在供应商");
        long batchId = batch(List.of(new ParsedImportRow("GMT库存产品清单", 9, ImportRowStatus.VALID, row, null)));

        assertThatThrownBy(() -> service.replace(repository.findBatchForUpdate(batchId), Map.of()))
                .hasMessageContaining("供应商不存在");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_order", Integer.class)).isOne();
        assertThat(repository.findBatch(batchId).status()).isEqualTo("PREVIEW");
    }

    private long batch(List<ParsedImportRow> rows) {
        return repository.create(ImportType.PRODUCT, "products.xlsx", "hash-" + System.nanoTime(), rows);
    }

    private ParsedImportRow validRow(String suffix) {
        return new ParsedImportRow("GMT库存产品清单", 9, ImportRowStatus.VALID, validData(suffix), null);
    }

    private Map<String,Object> validData(String suffix) {
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("brand", "BRAVAT"); data.put("series", "P90"); data.put("bodyColor", "宇宙黑");
        data.put("lockType", "7068"); data.put("connectivity", "WiFi"); data.put("salesChannel", "平面");
        data.put("operatingEntity", "珠海"); data.put("language", "中文版"); data.put("codeSuffix", suffix);
        data.put("productCode", "BR_P90YZH70WPZC-" + suffix);
        data.put("_ruleFingerprint", "BRAND:1|SERIES:2|BODY_COLOR:3|LOCK_TYPE:4|CONNECTIVITY:5|SALES_CHANNEL:6|OPERATING_ENTITY:7|LANGUAGE:8");
        data.put("model", "P90"); data.put("productCategory", "智能锁"); data.put("materialType", "成品");
        data.put("customerMaterialCode", "CUS-P90"); data.put("materialSpecification", "测试规格");
        data.put("productConfiguration", "测试配置"); data.put("salesMinimumOrderQuantity", BigDecimal.ONE);
        data.put("supplierName", "测试供应商"); data.put("supplierTaxPrice", new BigDecimal("88.50"));
        return data;
    }
}
