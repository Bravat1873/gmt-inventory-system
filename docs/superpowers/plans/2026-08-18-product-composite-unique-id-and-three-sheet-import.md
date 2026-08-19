# Product Composite Unique ID and Three-Sheet Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist `产品编号::客户料号` as the product business unique ID, keep both source fields separately visible, and replace product master data from the GMT, 贝朗, and STANLEY product sheets.

**Architecture:** Add a focused `ProductUniqueId` value generator shared by manual commands and product replacement import. Store its normalized value in `sku.business_unique_id` with a database unique constraint while retaining numeric `sku.id` for all foreign keys. Expand the workbook parser to three fixed sheets and change preview/commit conflict grouping from product code to the composite ID.

**Tech Stack:** Java 21, Spring Boot JDBC, Flyway/MySQL 8, H2 integration tests, Apache POI, Maven, Vue 3/Vitest for UI regression, Docker Compose for the local import rehearsal.

## Global Constraints

- Format is `normalized product code::normalized customer part number`.
- Trim both components and normalize case with `Locale.ROOT`; preserve original product code and customer part number fields for display.
- Reject blank product code or blank customer part number.
- Keep `sku.id` as the primary/foreign-key identity; the composite value is a business unique key.
- Supported product sheets are exactly `GMT库存产品清单`, `贝朗库存产品清单`, and `STANLEY库存产品清单`.
- Do not parse `库存数量` as product master data or modify the supplied workbook.
- Product preview and commit must recompute the key server-side; the frontend never submits or displays it.
- Use TDD for every behavior change and commit only task-scoped files.

---

### Task 1: Composite ID Generator and Database Migration

**Files:**
- Create: `backend/app/src/main/java/com/internalops/productcode/ProductUniqueId.java`
- Create: `backend/app/src/test/java/com/internalops/productcode/ProductUniqueIdTest.java`
- Create: `backend/app/src/main/resources/db/migration/V48__product_composite_business_unique_id.sql`
- Create: `backend/app/src/test/java/com/internalops/workbench/ProductCompositeUniqueIdMigrationTest.java`

**Interfaces:**
- Consumes: product code and customer part number strings.
- Produces: `ProductUniqueId.from(String productCode, String customerPartNumber): String`.

- [ ] **Step 1: Write the failing generator test**

```java
class ProductUniqueIdTest {
    @Test void trims_and_uppercases_both_parts() {
        assertThat(ProductUniqueId.from(" g_t5 ", " g8t5 "))
                .isEqualTo("G_T5::G8T5");
    }

    @ParameterizedTest
    @CsvSource({"'',G8T5", "G_T5,''", "'   ',G8T5", "G_T5,'   '"})
    void rejects_blank_parts(String productCode, String customerPartNumber) {
        assertThatThrownBy(() -> ProductUniqueId.from(productCode, customerPartNumber))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("产品编号和客户料号不能为空");
    }
}
```

- [ ] **Step 2: Run the generator test and verify RED**

Run: `mvn -pl app -Dtest=ProductUniqueIdTest test` from `backend`.
Expected: compilation failure because `ProductUniqueId` does not exist.

- [ ] **Step 3: Implement the minimal generator**

```java
public final class ProductUniqueId {
    private ProductUniqueId() {}

    public static String from(String productCode, String customerPartNumber) {
        String code = normalize(productCode);
        String part = normalize(customerPartNumber);
        if (code.isBlank() || part.isBlank()) {
            throw new IllegalArgumentException("产品编号和客户料号不能为空");
        }
        return code + "::" + part;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
```

- [ ] **Step 4: Run the generator test and verify GREEN**

Run: `mvn -pl app -Dtest=ProductUniqueIdTest test`.
Expected: PASS.

- [ ] **Step 5: Write the failing migration test**

```java
@Test void migration_backfills_composite_ids_and_replaces_product_code_uniqueness() {
    runMigration("V48__product_composite_business_unique_id.sql");
    assertThat(jdbc.queryForObject(
            "SELECT business_unique_id FROM sku WHERE id=1", String.class))
            .isEqualTo("G_T5::G8T5");
    jdbc.update("INSERT INTO sku(product_code,customer_part_number,business_unique_id,product_name) VALUES(?,?,?,?)",
            "G_T5", "OTHER", "G_T5::OTHER", "same generated code");
}
```

- [ ] **Step 6: Run the migration test and verify RED**

Run: `mvn -pl app -Dtest=ProductCompositeUniqueIdMigrationTest test`.
Expected: FAIL because `business_unique_id` and migration V48 do not exist.

- [ ] **Step 7: Implement V48 with a safe staged migration**

```sql
ALTER TABLE sku DROP INDEX uk_sku_product_code;
ALTER TABLE sku ADD COLUMN business_unique_id VARCHAR(200) NULL;
UPDATE sku
SET business_unique_id = CONCAT(UPPER(TRIM(product_code)), '::', UPPER(TRIM(customer_part_number)))
WHERE NULLIF(TRIM(product_code), '') IS NOT NULL
  AND NULLIF(TRIM(customer_part_number), '') IS NOT NULL;
ALTER TABLE sku MODIFY business_unique_id VARCHAR(200) NOT NULL;
ALTER TABLE sku ADD CONSTRAINT uk_sku_business_unique_id UNIQUE (business_unique_id);
CREATE INDEX idx_sku_product_code ON sku(product_code);
```

Before finalizing the SQL, add explicit precondition checks using a stored procedure or Flyway-compatible failing expression so blank components or duplicate normalized combinations abort before the `NOT NULL`/unique operations.

- [ ] **Step 8: Run migration and generator tests**

Run: `mvn -pl app -Dtest=ProductUniqueIdTest,ProductCompositeUniqueIdMigrationTest test`.
Expected: PASS, including same product code with a different customer part number.

- [ ] **Step 9: Commit Task 1**

```bash
git add backend/app/src/main/java/com/internalops/productcode/ProductUniqueId.java backend/app/src/test/java/com/internalops/productcode/ProductUniqueIdTest.java backend/app/src/main/resources/db/migration/V48__product_composite_business_unique_id.sql backend/app/src/test/java/com/internalops/workbench/ProductCompositeUniqueIdMigrationTest.java
git commit -m "功能：新增产品复合唯一ID"
```

### Task 2: Manual Product Create and Update

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/MasterDataCommandService.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/MasterDataCommandApiTest.java`
- Modify: `backend/app/src/test/resources/master-command-schema.sql`

**Interfaces:**
- Consumes: `ProductUniqueId.from(...)` from Task 1.
- Produces: manual create/update SQL that always persists `business_unique_id`; API responses remain unchanged.

- [ ] **Step 1: Update the test schema and write failing API tests**

Add `business_unique_id VARCHAR(200) NOT NULL UNIQUE` to the test `sku` table and seed it for existing rows. Add assertions:

```java
@Test void creates_product_with_composite_business_unique_id() {
    long id = createProduct("G8T5VMHS02");
    assertThat(jdbc.queryForObject("SELECT business_unique_id FROM sku WHERE id=?", String.class, id))
            .isEqualTo("G_T5YZH60WPSC-M::G8T5VMHS02");
}

@Test void allows_same_product_code_for_different_customer_part_numbers() {
    createProduct("PART-A");
    assertThatCode(() -> createProduct("PART-B")).doesNotThrowAnyException();
}

@Test void rejects_same_product_code_and_customer_part_number_combination() {
    createProduct("PART-A");
    assertThatThrownBy(() -> createProduct(" part-a "))
            .hasMessageContaining("产品编号和客户料号组合已存在");
}
```

- [ ] **Step 2: Run the manual command tests and verify RED**

Run: `mvn -pl app -Dtest=MasterDataCommandApiTest test`.
Expected: FAIL because create/update do not populate `business_unique_id` and still report product-code-only conflicts.

- [ ] **Step 3: Inject and use the generator in create/update SQL**

In `createProduct`, trim `customerPartNumber`, compute:

```java
String businessUniqueId = ProductUniqueId.from(productCode, customerPartNumber);
```

Add `business_unique_id` and its bind value to `INSERT INTO sku`. In `updateProduct`, include `customer_part_number` in the current-row query, choose the requested or existing value, recompute the business ID, and update `product_code`, `customer_part_number`, and `business_unique_id` atomically. Replace the catch message with:

```java
throw new IllegalStateException("产品编号和客户料号组合已存在：" + productCode + " + " + customerPartNumber);
```

- [ ] **Step 4: Run the manual command tests and verify GREEN**

Run: `mvn -pl app -Dtest=MasterDataCommandApiTest test`.
Expected: PASS with unchanged response JSON fields.

- [ ] **Step 5: Commit Task 2**

```bash
git add backend/app/src/main/java/com/internalops/workbench/MasterDataCommandService.java backend/app/src/test/java/com/internalops/workbench/MasterDataCommandApiTest.java backend/app/src/test/resources/master-command-schema.sql
git commit -m "功能：产品维护使用复合唯一ID"
```

### Task 3: Parse the Third Product Sheet

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/importing/ProductWorkbookExcelParser.java`
- Modify: `backend/app/src/test/java/com/internalops/importing/ExcelImportParserTest.java`

**Interfaces:**
- Consumes: Apache POI `Workbook`.
- Produces: parsed rows from all three named sheets, in GMT → 贝朗 → STANLEY order.

- [ ] **Step 1: Write failing parser tests**

Build a workbook with all three named sheets and one business row per sheet, then assert:

```java
assertThat(rows).extracting(ParsedImportRow::sheetName)
        .containsExactly("GMT库存产品清单", "贝朗库存产品清单", "STANLEY库存产品清单");
```

Also remove the STANLEY sheet and assert `缺少产品工作表：STANLEY库存产品清单`.

- [ ] **Step 2: Run parser tests and verify RED**

Run: `mvn -pl app -Dtest=ExcelImportParserTest test`.
Expected: FAIL because the parser currently requires only two sheets.

- [ ] **Step 3: Extend the fixed sheet list**

```java
private static final List<String> SHEETS = List.of(
        "GMT库存产品清单", "贝朗库存产品清单", "STANLEY库存产品清单");
```

Keep `库存数量` excluded and retain the existing legend filtering.

- [ ] **Step 4: Run parser tests and verify GREEN**

Run: `mvn -pl app -Dtest=ExcelImportParserTest test`.
Expected: PASS.

- [ ] **Step 5: Commit Task 3**

```bash
git add backend/app/src/main/java/com/internalops/importing/ProductWorkbookExcelParser.java backend/app/src/test/java/com/internalops/importing/ExcelImportParserTest.java
git commit -m "功能：产品导入支持STANLEY清单"
```

### Task 4: Composite Conflict Detection and Replacement Write

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/importing/ProductImportValidationService.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ProductReplaceImportService.java`
- Modify: `backend/app/src/test/java/com/internalops/importing/ProductReplaceImportServiceTest.java`
- Modify: `backend/app/src/test/java/com/internalops/importing/ImportValidationServiceTest.java`
- Modify: `backend/app/src/test/resources/product-replace-import-schema.sql`
- Modify other import test schemas containing `CREATE TABLE sku` only where compilation/runtime failures prove required.

**Interfaces:**
- Consumes: `ProductUniqueId.from(resolved.productCode(), customerPartNumber)`.
- Produces: `_businessUniqueId` preview metadata and `_conflictGroup` keyed by composite ID; inserts `business_unique_id` into `sku`.

- [ ] **Step 1: Write failing validation tests**

Create two resolved rows with the same generated product code:

```java
assertThat(validateAll(rows("PART-A", "PART-B")))
        .allSatisfy(row -> assertThat(row.data().get("_conflict")).isEqualTo(false));
assertThat(validateAll(rows("PART-A", " part-a ")))
        .allSatisfy(row -> assertThat(row.data().get("_conflict")).isEqualTo(true));
```

Assert `_businessUniqueId` is uppercase and `_conflictGroup` equals that value for duplicates.

- [ ] **Step 2: Run validation tests and verify RED**

Run: `mvn -pl app -Dtest=ImportValidationServiceTest test`.
Expected: FAIL because grouping still uses `productCode` only.

- [ ] **Step 3: Implement composite preview grouping**

After resolving the code, compute and store:

```java
String businessUniqueId = ProductUniqueId.from(
        resolved.productCode(), text(data, "customerPartNumber"));
data.put("_businessUniqueId", businessUniqueId);
```

Group duplicates by `_businessUniqueId`; retain `_conflict`, `_conflictGroup`, and `_conflictAction` behavior.

- [ ] **Step 4: Run validation tests and verify GREEN**

Run: `mvn -pl app -Dtest=ImportValidationServiceTest test`.
Expected: PASS.

- [ ] **Step 5: Write failing replacement tests**

Cover commit revalidation and storage:

```java
assertThat(jdbc.queryForList(
        "SELECT product_code,customer_part_number,business_unique_id FROM sku ORDER BY customer_part_number"))
        .extracting(row -> row.get("BUSINESS_UNIQUE_ID"))
        .containsExactly("BR_P90YZH70WPZC::PART-A", "BR_P90YZH70WPZC::PART-B");
```

Add a test that tampers `_businessUniqueId` after preview and expects commit to reject it with `产品唯一ID已变化，请重新预览后再提交`.

- [ ] **Step 6: Run replacement tests and verify RED**

Run: `mvn -pl app -Dtest=ProductReplaceImportServiceTest test`.
Expected: FAIL because commit groups/inserts by product code and the schema lacks the new column.

- [ ] **Step 7: Implement commit revalidation and storage**

In `validateAndSelect`, recompute the business ID, compare it with `_businessUniqueId`, and group `SelectedProduct` by it. In `insertProduct`, add `business_unique_id` to the insert columns and bind:

```java
ProductUniqueId.from(resolved.productCode(), text(data, "customerPartNumber"))
```

Update the test schema with a non-null unique `business_unique_id` and give all seeded products a value.

- [ ] **Step 8: Run import tests and verify GREEN**

Run: `mvn -pl app -Dtest=ImportValidationServiceTest,ProductReplaceImportServiceTest,ExcelImportParserTest test`.
Expected: PASS.

- [ ] **Step 9: Commit Task 4**

```bash
git add backend/app/src/main/java/com/internalops/importing/ProductImportValidationService.java backend/app/src/main/java/com/internalops/importing/ProductReplaceImportService.java backend/app/src/test/java/com/internalops/importing/ProductReplaceImportServiceTest.java backend/app/src/test/java/com/internalops/importing/ImportValidationServiceTest.java backend/app/src/test/resources/product-replace-import-schema.sql
git commit -m "功能：产品导入按复合唯一ID判重"
```

### Task 5: Frontend Regression, Full Verification, and Real Import

**Files:**
- Modify only if a regression is found: `frontend/src/components/ImportPanel.vue`
- Modify only if needed: `frontend/src/components/ImportPanel.test.ts`
- Use without committing credentials: `preview-import.ps1`, `commit-import.ps1`, `.codex-tmp/inventory-import/*`

**Interfaces:**
- Consumes: unchanged visible `productCode` and `customerPartNumber`, product preview API, admin credentials supplied/configured locally.
- Produces: verified UI, migrated application, preview/commit evidence, and database reconciliation.

- [ ] **Step 1: Add or confirm a frontend regression test**

The preview row must render both fields independently and must not render `_businessUniqueId`:

```ts
expect(wrapper.text()).toContain('G_T5YZH60WPSC-M')
expect(wrapper.text()).toContain('G8T5VMHS02')
expect(wrapper.text()).not.toContain('G_T5YZH60WPSC-M::G8T5VMHS02')
```

- [ ] **Step 2: Run frontend tests**

Run: `npm test -- --run frontend/src/components/ImportPanel.test.ts` from the repository root if the root script supports paths; otherwise run `npm test -- --run src/components/ImportPanel.test.ts` from `frontend`.
Expected: PASS. If it fails because the internal key is rendered, remove only that rendering and rerun.

- [ ] **Step 3: Run the focused backend suite**

Run from `backend`:

```bash
mvn -pl app -Dtest=ProductUniqueIdTest,ProductCompositeUniqueIdMigrationTest,MasterDataCommandApiTest,ExcelImportParserTest,ImportValidationServiceTest,ProductReplaceImportServiceTest test
```

Expected: PASS with zero failures/errors.

- [ ] **Step 4: Run full verification builds**

Run `mvn test` from `backend`, then `npm test -- --run` and `npm run build` from `frontend`.
Expected: all backend and frontend tests pass and the production frontend build completes.

- [ ] **Step 5: Rebuild and migrate the local stack**

Run `docker compose up -d --build` from the repository root, then `docker compose ps` and request `/api/system/health`.
Expected: MySQL, backend, and frontend are healthy; Flyway reports V48 applied.

- [ ] **Step 6: Preview the supplied workbook without committing**

Run:

```powershell
.\preview-import.ps1 -WorkbookPath 'C:\Users\linfu\Documents\xwechat_files\wxid_3npmpu5sgt7i12_87e6\msg\file\2026-08\吉门第库存汇总表20260814(2)(1).xlsx' -AdminPassword '<local-admin-password>'
```

Expected: the product batch includes rows from all three sheets; `errors=0`; every composite conflict is intentionally resolvable. Do not echo or commit the password.

- [ ] **Step 7: Reconcile preview counts before destructive replacement**

Record valid counts per source sheet and query duplicate `_businessUniqueId` values. Abort commit if any row is missing, any error remains, or any conflict lacks an explicit decision. Confirm the replacement-mode environment is the intended local/development database.

- [ ] **Step 8: Commit the product replacement batch**

Submit only the product batch using the existing commit endpoint/script and explicit conflict actions. Expected: status `COMMITTED`, committed row count equals selected valid rows, and the transaction reports zero errors.

- [ ] **Step 9: Reconcile the database**

Run read-only SQL/API checks:

```sql
SELECT COUNT(*) AS products,
       COUNT(DISTINCT business_unique_id) AS unique_products
FROM sku;
SELECT product_code, customer_part_number, business_unique_id
FROM sku
WHERE business_unique_id <> CONCAT(UPPER(TRIM(product_code)), '::', UPPER(TRIM(customer_part_number)));
```

Expected: product count equals unique count; mismatch query returns zero rows. Verify at least one GMT, 贝朗, and STANLEY record and reconcile supplier quote counts.

- [ ] **Step 10: Final commit for any frontend/test-schema adjustments**

```bash
git add frontend/src/components/ImportPanel.vue frontend/src/components/ImportPanel.test.ts
git commit -m "测试：保持产品编号与客户料号分开展示"
```

Skip this commit when no frontend files changed. Never commit the workbook, passwords, generated preview payloads, or database dumps.
