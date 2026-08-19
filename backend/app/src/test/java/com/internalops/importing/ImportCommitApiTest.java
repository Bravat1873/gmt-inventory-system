package com.internalops.importing;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = {"/auth-schema.sql", "/import-schema.sql", "/import-commit-schema.sql"})
class ImportCommitApiTest {
    private static final String PASSWORD = "123";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ImportBatchRepository repository;

    @BeforeEach
    void addRoleUsers() {
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,enabled,role) VALUES(?,?,?,?,?)",
                "finance", PASSWORD, "财务", true, "FINANCE");
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,enabled,role) VALUES(?,?,?,?,?)",
                "regular-user", PASSWORD, "操作员", true, "USER");
    }

    @Test
    void regularUserCannotCommitCostAndFinanceCommitsTheWholeBatch() throws Exception {
        jdbc.update("INSERT INTO sku(customer_part_number,product_name,current_cost,factory_price,enabled) VALUES('COST-1','成本一',10,20,TRUE)");
        jdbc.update("INSERT INTO sku(customer_part_number,product_name,current_cost,factory_price,enabled) VALUES('COST-2','成本二',30,40,TRUE)");
        long batchId = repository.create(ImportType.COST, "cost.xlsx", "api-cost-permission", List.of(
                row(Map.of("customerPartNumber", "COST-1", "cost", new BigDecimal("100"), "factoryPrice", new BigDecimal("200"))),
                row(Map.of("customerPartNumber", "COST-2", "cost", new BigDecimal("300"), "factoryPrice", new BigDecimal("400")))));

        mvc.perform(post("/api/imports/{batchId}/commit", batchId).cookie(loginAs("regular-user")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));

        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?", String.class, batchId)).isEqualTo("PREVIEW");
        assertThat(jdbc.queryForObject("SELECT committed_rows FROM import_batch WHERE id=?", Integer.class, batchId)).isZero();
        assertThat(jdbc.queryForList("SELECT current_cost FROM sku ORDER BY customer_part_number", BigDecimal.class))
                .containsExactly(new BigDecimal("10.0000"), new BigDecimal("30.0000"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku_cost_history", Integer.class)).isZero();

        mvc.perform(post("/api/imports/{batchId}/commit", batchId).cookie(loginAs("finance")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMMITTED"))
                .andExpect(jsonPath("$.data.committedRows").value(2));

        assertThat(jdbc.queryForList("SELECT current_cost FROM sku ORDER BY customer_part_number", BigDecimal.class))
                .containsExactly(new BigDecimal("100.0000"), new BigDecimal("300.0000"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku_cost_history", Integer.class)).isEqualTo(2);
    }

    @Test
    void regularUserCanStillCommitCustomerAndInventoryBatches() throws Exception {
        Cookie userSession = loginAs("regular-user");
        long customerBatch = repository.create(ImportType.CUSTOMER, "customer.xlsx", "api-customer-user", List.of(
                new ParsedImportRow("Sheet1", 1, ImportRowStatus.VALID, Map.of("customerName", "普通客户"), null)));
        long inventoryBatch = repository.create(ImportType.INVENTORY, "inventory.xlsx", "api-inventory-user", List.of(
                new ParsedImportRow("Sheet1", 1, ImportRowStatus.VALID, Map.of(
                        "customerPartNumber", "INV-USER", "model", "P90", "actualQuantity", 1,
                        "lockedQuantity", 0, "inTransitQuantity", 0), null)));

        mvc.perform(post("/api/imports/{batchId}/commit", customerBatch).cookie(userSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMMITTED"));
        mvc.perform(post("/api/imports/{batchId}/commit", inventoryBatch).cookie(userSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMMITTED"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE customer_name='普通客户'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_balance", Integer.class)).isOne();
    }

    @Test
    void overwriteRetainsMatchedIdReplacesBlankFieldsAddsNewNamesAndKeepsAbsentSuppliers() throws Exception {
        jdbc.update("""
                INSERT INTO supplier(
                    supplier_code,supplier_name,contact_name,phone,bank_account,
                    manufacturer_category,manufacturer_type,supplier_location,product_attribute,
                    short_name,contact_title,address,currency,tax_registration_no,bank_address,enabled)
                VALUES('SUP00009','星 云(深圳)','旧联系人','999','OLD-BANK',
                    '旧分类','旧类型','旧地点','旧属性','旧简称','旧职称','旧地址','CNY','OLD-TAX','旧开户地址',FALSE)
                """);
        long matchedId = jdbc.queryForObject("SELECT id FROM supplier WHERE supplier_code='SUP00009'", Long.class);
        jdbc.update("INSERT INTO supplier(supplier_code,supplier_name,enabled) VALUES('LEGACY-ABSENT','文件外供应商',TRUE)");
        long batchId = repository.create(ImportType.SUPPLIER, "supplier.xls", "supplier-overwrite", List.of(
                row(supplierData("星云（深圳）")),
                row(supplierData("新增供应商"))));

        mvc.perform(post("/api/imports/{batchId}/commit", batchId).cookie(loginAs("regular-user"))
                        .contentType("application/json").content("{\"supplierMode\":\"OVERWRITE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMMITTED"))
                .andExpect(jsonPath("$.data.result.created").value(1))
                .andExpect(jsonPath("$.data.result.updated").value(1))
                .andExpect(jsonPath("$.data.result.mode").value("OVERWRITE"));

        Map<String, Object> matched = jdbc.queryForMap("SELECT * FROM supplier WHERE supplier_name='星云（深圳）'");
        assertThat(((Number) matched.get("ID")).longValue()).isEqualTo(matchedId);
        assertThat(matched)
                .containsEntry("SUPPLIER_CODE", "SUP00009")
                .containsEntry("MANUFACTURER_CATEGORY", "新分类")
                .containsEntry("MANUFACTURER_TYPE", null)
                .containsEntry("SUPPLIER_LOCATION", "东莞")
                .containsEntry("PRODUCT_ATTRIBUTE", "门锁")
                .containsEntry("SHORT_NAME", null)
                .containsEntry("CONTACT_NAME", null)
                .containsEntry("CONTACT_TITLE", "采购经理")
                .containsEntry("PHONE", "001 234")
                .containsEntry("ADDRESS", null)
                .containsEntry("CURRENCY", "USD")
                .containsEntry("TAX_REGISTRATION_NO", "000123")
                .containsEntry("BANK_ADDRESS", null)
                .containsEntry("BANK_ACCOUNT", null)
                .containsEntry("ENABLED", true);
        assertThat(jdbc.queryForObject("SELECT supplier_code FROM supplier WHERE supplier_name='新增供应商'", String.class))
                .isEqualTo("SUP00010");
        assertThat(jdbc.queryForObject("SELECT enabled FROM supplier WHERE supplier_name='文件外供应商'", Boolean.class))
                .isTrue();
    }

    @Test
    void replaceAllRetainsMatchedIdDisablesAbsentSuppliersAndPreservesPurchaseReferences() throws Exception {
        jdbc.execute("""
                CREATE TABLE purchase_supplier_reference (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    supplier_id BIGINT NOT NULL,
                    purchase_no VARCHAR(80) NOT NULL,
                    CONSTRAINT fk_purchase_supplier FOREIGN KEY (supplier_id) REFERENCES supplier(id))
                """);
        jdbc.update("INSERT INTO supplier(supplier_code,supplier_name,contact_name,enabled) VALUES('SUP00001','保留供应商','旧联系人',FALSE)");
        long retainedId = jdbc.queryForObject("SELECT id FROM supplier WHERE supplier_code='SUP00001'", Long.class);
        jdbc.update("INSERT INTO purchase_supplier_reference(supplier_id,purchase_no) VALUES(?, 'PO-001')", retainedId);
        jdbc.update("INSERT INTO supplier(supplier_code,supplier_name,enabled) VALUES('SUP00002','停用供应商',TRUE)");
        long batchId = repository.create(ImportType.SUPPLIER, "supplier.xlsx", "supplier-replace-all",
                List.of(row(supplierData(" 保留供应商 "))));

        mvc.perform(post("/api/imports/{batchId}/commit", batchId).cookie(loginAs("regular-user"))
                        .contentType("application/json").content("{\"supplierMode\":\"REPLACE_ALL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.mode").value("REPLACE_ALL"))
                .andExpect(jsonPath("$.data.result.disabled").value(1));

        assertThat(jdbc.queryForObject("SELECT id FROM supplier WHERE supplier_name='保留供应商'", Long.class))
                .isEqualTo(retainedId);
        assertThat(jdbc.queryForObject("SELECT enabled FROM supplier WHERE id=?", Boolean.class, retainedId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT enabled FROM supplier WHERE supplier_name='停用供应商'", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT supplier_id FROM purchase_supplier_reference WHERE purchase_no='PO-001'", Long.class))
                .isEqualTo(retainedId);
    }

    @Test
    void replaceAllRejectsInvalidBatchWithoutChangingSuppliersOrBatchStatus() throws Exception {
        jdbc.update("INSERT INTO supplier(supplier_code,supplier_name,contact_name,enabled) VALUES('SUP00001','原供应商','原联系人',TRUE)");
        long batchId = repository.create(ImportType.SUPPLIER, "invalid.xlsx", "supplier-invalid", List.of(
                row(supplierData("将被回滚")),
                new ParsedImportRow("Sheet1", 3, ImportRowStatus.ERROR,
                        Map.of("supplierName", ""), "供应商名称不能为空")));

        mvc.perform(post("/api/imports/{batchId}/commit", batchId).cookie(loginAs("regular-user"))
                        .contentType("application/json").content("{\"supplierMode\":\"REPLACE_ALL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("全量替换前必须修正所有错误行"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplier", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT contact_name FROM supplier WHERE supplier_name='原供应商'", String.class))
                .isEqualTo("原联系人");
        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?", String.class, batchId))
                .isEqualTo("PREVIEW");
    }

    @AfterEach
    void removePurchaseReferenceFixture() {
        jdbc.execute("DROP TABLE IF EXISTS purchase_supplier_reference");
    }

    private Cookie loginAs(String username) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("OPS_SESSION");
    }

    private ParsedImportRow row(Map<String, Object> data) {
        return new ParsedImportRow("Sheet1", 1, ImportRowStatus.VALID, data, null);
    }

    private Map<String, Object> supplierData(String supplierName) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("manufacturerCategory", "新分类");
        data.put("manufacturerType", "");
        data.put("supplierLocation", "东莞");
        data.put("productAttribute", "门锁");
        data.put("shortName", "");
        data.put("supplierName", supplierName);
        data.put("contactName", "");
        data.put("contactTitle", "采购经理");
        data.put("phone", "001 234");
        data.put("address", "");
        data.put("currency", "USD");
        data.put("taxRegistrationNo", "000123");
        data.put("bankAddress", "");
        data.put("bankAccount", "");
        return data;
    }
}
