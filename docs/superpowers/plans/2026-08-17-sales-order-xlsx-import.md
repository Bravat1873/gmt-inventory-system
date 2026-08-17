# 销售订单 XLSX 批量导入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 增加按客户编码和产品编号唯一匹配的销售订单 XLSX 预览与批量提交，生成标准模板，并把用户可见的“物料编号”统一为“客户料号”。

**Architecture:** 在现有通用导入批次上新增 `ORDER` 类型；解析器输出一行一明细，订单专用验证服务负责唯一键匹配、同订单一致性和预览错误，订单专用提交服务按外部订单号分组并逐组调用现有 `SalesOrderCommandService.create`。前端复用 `ImportPanel`，增加订单分组预览；模板由受审计脚本生成并以静态下载接口交付。

**Tech Stack:** Java 17、Spring Boot、JdbcTemplate、Apache POI、Vue 3、TypeScript、Vitest、artifact-tool XLSX。

## Global Constraints

- 同一外部订单号的多行合并为一张订单。
- 客户仅通过唯一客户编码匹配；产品仅通过唯一产品编号匹配。
- 客户料号只辅助核对，不作为产品关联键。
- 模板默认“正式订单”；正式订单复用现有库存分配逻辑，草稿不锁定库存。
- 客户余额只展示提醒，不因导入订单自动扣减。
- 已存在外部订单号时整单拒绝，禁止重复建单。
- 用户可见“物料编号”统一为“客户料号”；内部 `skuCode` 和数据库字段不迁移。
- 旧成本/库存 XLSX 表头继续兼容“物料编号”，新模板使用“客户料号”。

---

### Task 1: 订单工作簿解析与行级校验

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/importing/ImportType.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ExcelImportParser.java`
- Create: `backend/app/src/main/java/com/internalops/importing/SalesOrderExcelParser.java`
- Create: `backend/app/src/main/java/com/internalops/importing/SalesOrderImportValidationService.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ImportValidationService.java`
- Test: `backend/app/src/test/java/com/internalops/importing/SalesOrderExcelParserTest.java`
- Test: `backend/app/src/test/java/com/internalops/importing/SalesOrderImportValidationServiceTest.java`

**Interfaces:**
- Produces: `ImportType.ORDER`; parsed keys `externalOrderNo`, `customerCode`, `orderDate`, `orderType`, `orderStatus`, `salesperson`, `customerMaterialCode`, `productCode`, `quantity`, `salePrice`, contact/delivery/remark fields.
- Produces: `List<ParsedImportRow> SalesOrderImportValidationService.validateAll(List<ParsedImportRow>)` with resolved `_customerId`, `_skuId`, `_normalizedStatus` retained in row data.

- [ ] **Step 1: Write parser and validation failing tests**

```java
assertThat(rows).extracting(r -> r.data().get("externalOrderNo"))
        .containsExactly("PO-1001", "PO-1001", "PO-1002");
assertThat(validated.get(0).data()).containsEntry("_customerId", 7L).containsEntry("_skuId", 11L);
assertThat(validated).allMatch(row -> row.status() == ImportRowStatus.VALID);
```

Add cases for missing/duplicate customer code, missing/duplicate product code, disabled records, invalid date/type/status/quantity/price, minimum quantity and mismatched customer material code.

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn "-Dmaven.repo.local=.m2" -pl app -am "-Dtest=SalesOrderExcelParserTest,SalesOrderImportValidationServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: compilation fails because `SalesOrderExcelParser` and `ImportType.ORDER` do not exist.

- [ ] **Step 3: Implement parser and validation**

```java
case ORDER -> new SalesOrderExcelParser().parse(workbook);
```

Resolve exact enabled matches with SQL using `customer.customer_code` and `sku.product_code`. Normalize `草稿 -> DRAFT`, `正式订单 -> PENDING_CUSTOMER_PAYMENT`; validate numeric/date fields without supplying silent price defaults.

- [ ] **Step 4: Run tests and verify GREEN**

Run the Step 2 command. Expected: all specified tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/app/src/main/java/com/internalops/importing backend/app/src/test/java/com/internalops/importing
git commit -m "增加销售订单表格解析校验"
```

### Task 2: 跨行订单分组一致性与重复单号保护

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/importing/SalesOrderImportValidationService.java`
- Test: `backend/app/src/test/java/com/internalops/importing/SalesOrderImportValidationServiceTest.java`

**Interfaces:**
- Consumes: Task 1 parsed normalized rows.
- Produces: every row in an invalid external-order group becomes `ERROR` with a group-specific message.

- [ ] **Step 1: Add failing group tests**

```java
assertThat(validate(rowsWithDifferentCustomersInSameOrder()))
        .allMatch(row -> row.status() == ImportRowStatus.ERROR);
assertThat(validate(rowsForExistingExternalOrder()))
        .allMatch(row -> row.errorMessage().contains("外部订单号已存在"));
```

Cover inconsistent date/type/status/salesperson/contact/delivery/remark and missing external order number.

- [ ] **Step 2: Run targeted tests and verify RED**

Run the Task 1 Maven command. Expected: new group assertions fail.

- [ ] **Step 3: Implement group validation**

Group non-ignored rows by trimmed `externalOrderNo`; compare all order-level keys using normalized null/blank semantics; query existing `sales_order.external_order_no`; replace every affected row with the same actionable error.

- [ ] **Step 4: Run targeted tests and verify GREEN**

Run the Task 1 Maven command. Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add backend/app/src/main/java/com/internalops/importing/SalesOrderImportValidationService.java backend/app/src/test/java/com/internalops/importing/SalesOrderImportValidationServiceTest.java
git commit -m "校验订单导入分组一致性"
```

### Task 3: 按订单事务提交并复用库存锁定逻辑

**Files:**
- Create: `backend/app/src/main/java/com/internalops/importing/SalesOrderImportCommitService.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ImportCommitService.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/SalesOrderCommandService.java`
- Test: `backend/app/src/test/java/com/internalops/importing/SalesOrderImportCommitServiceTest.java`
- Test resources: `backend/app/src/test/resources/import-commit-schema.sql`

**Interfaces:**
- Consumes: validated rows containing `_customerId`, `_skuId`, `_normalizedStatus`.
- Produces: `SalesOrderImportCommitService.Result commit(List<ImportRowView> rows)` with `createdOrders`, `committedRows`, `errors`.
- Reuses: `SalesOrderCommandService.create(SalesOrderRequest)` so formal orders allocate inventory and drafts do not.

- [ ] **Step 1: Add failing commit tests**

```java
var result = service.commit(twoRowsForOneFormalOrder());
assertThat(result.createdOrders()).isEqualTo(1);
assertThat(jdbc.queryForObject("select count(*) from sales_order_item", Integer.class)).isEqualTo(2);
assertThat(jdbc.queryForObject("select sum(locked_quantity) from inventory_balance", Integer.class)).isPositive();
```

Add draft lock=0, duplicate external number rollback, one invalid line rolls back whole order, and fund tables unchanged.

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn "-Dmaven.repo.local=.m2" -pl app -am "-Dtest=SalesOrderImportCommitServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: compilation fails because commit service does not exist.

- [ ] **Step 3: Implement grouped commit**

Build one `SalesOrderRequest` per external order number and invoke `SalesOrderCommandService.create`. Add a public duplicate-check helper or enforce a unique precondition in the same transaction before insertion. Dispatch `ImportType.ORDER` before the generic row switch and mark the batch committed only after all groups succeed.

- [ ] **Step 4: Run tests and verify GREEN**

Run Step 2 command and `ImportCommitServiceTest`. Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add backend/app/src/main/java/com/internalops/importing backend/app/src/main/java/com/internalops/workbench/SalesOrderCommandService.java backend/app/src/test
git commit -m "支持批量提交销售订单"
```

### Task 4: 订单导入入口与分组预览

**Files:**
- Modify: `frontend/src/api/imports.ts`
- Modify: `frontend/src/modules/module-config.ts`
- Modify: `frontend/src/components/ModuleListPage.vue`
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/components/ImportPanel.vue`
- Test: `frontend/src/components/ImportPanel.test.ts`
- Test: `frontend/src/components/ModuleListPage.test.ts`
- Test: `frontend/src/App.test.ts`

**Interfaces:**
- Consumes: `ImportType.ORDER` and existing preview/commit APIs.
- Produces: order page has both `新增订单` and `导入订单`; ORDER preview groups rows by external order number and disables commit when any row is ERROR.

- [ ] **Step 1: Add failing UI tests**

```ts
expect(wrapper.get('[data-test="order-import-action"]').text()).toBe('导入订单')
expect(wrapper.findAll('[data-test="order-import-group"]')).toHaveLength(2)
expect(wrapper.get('[data-test="commit-import"]').attributes('disabled')).toBeDefined()
```

- [ ] **Step 2: Run tests and verify RED**

Run: `npm test -- ImportPanel.test.ts ModuleListPage.test.ts App.test.ts`

Expected: ORDER type and action are absent.

- [ ] **Step 3: Implement entry and preview**

Extend `ImportType` union with `ORDER`. Keep the existing primary “新增订单” action and add a separate authorized import action for the order module. Render group header, order status, customer code, external number and its line rows; reuse the existing error download and confirmation flow.

- [ ] **Step 4: Run tests and build**

Run: `npm test -- ImportPanel.test.ts ModuleListPage.test.ts App.test.ts` and `npm run build`.

Expected: tests and TypeScript build pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "增加订单批量导入界面"
```

### Task 5: 全系统“客户料号”用词兼容修复

**Files:**
- Modify: all tracked frontend/backend source files returned by `git grep -n "物料编号" -- frontend/src backend/app/src/main/java`
- Test: relevant component/parser tests changed by the wording.

**Interfaces:**
- Produces: all user-visible labels/errors say `客户料号`; legacy COST/INVENTORY parsers accept both old and new headers.

- [ ] **Step 1: Add failing wording and compatibility tests**

```java
assertThat(parseWorkbookWithHeader("客户料号")).hasSize(1);
assertThat(parseWorkbookWithHeader("物料编号")).hasSize(1);
```

```ts
expect(wrapper.text()).toContain('客户料号')
expect(wrapper.text()).not.toContain('物料编号')
```

- [ ] **Step 2: Run tests and verify RED**

Run affected Java parser tests and Vue component tests. Expected: new-header or copy assertions fail.

- [ ] **Step 3: Replace copy and add header aliases**

Replace only user-visible strings. Keep internal `skuCode` identifiers unchanged. For parser column lookup use aliases such as `optionalColumn(header, "客户料号", "物料编号")` so historical files remain valid.

- [ ] **Step 4: Scan and test**

Run: `git grep -n "物料编号" -- frontend/src backend/app/src/main/java`.

Expected: only explicit legacy-header compatibility constants/comments remain. Run affected backend tests and full frontend tests/build.

- [ ] **Step 5: Commit**

```bash
git add frontend/src backend/app/src
git commit -m "统一客户料号展示名称"
```

### Task 6: 生成、下载并验证 XLSX 模板

**Files:**
- Create: `scripts/generate-sales-order-import-template.mjs`
- Create: `outputs/templates/销售订单批量导入模板.xlsx`
- Create: `backend/app/src/main/java/com/internalops/importing/ImportTemplateController.java`
- Test: `backend/app/src/test/java/com/internalops/importing/ImportTemplateApiTest.java`
- Modify: `frontend/src/components/ImportPanel.vue`
- Test: `frontend/src/components/ImportPanel.test.ts`

**Interfaces:**
- Produces: `GET /api/imports/templates/ORDER.xlsx` and an ORDER-only “下载模板” button.

- [ ] **Step 1: Read spreadsheet style guidance and mark artifact operation once**

Read the spreadsheet skill style guide, call `mark_artifact_operation_started` exactly once before the first workbook edit, and use artifact-tool rather than openpyxl.

- [ ] **Step 2: Create failing template/API tests**

Assert the API returns the XLSX media type and a non-empty resource; assert the UI link targets `/api/imports/templates/ORDER.xlsx`.

- [ ] **Step 3: Generate workbook**

Create `订单导入` and `填写说明`; add the exact columns from the design, two example orders, frozen/filterable header, required-column color, date/currency formats and validations for order type/status. Do not place production customer/product values in the example.

- [ ] **Step 4: Inspect and render**

Use artifact-tool inspect to assert sheet names, header values, validations and used range. Render both sheets to PNG, visually inspect clipping/overlap, and scan formula errors. Correct defects before delivery.

- [ ] **Step 5: Add download endpoint and UI button**

Serve the verified workbook as a classpath/static resource or generate it deterministically at request time; the repository copy and endpoint bytes must match.

- [ ] **Step 6: Run tests/build and commit**

Run `ImportTemplateApiTest`, `ImportPanel.test.ts`, and `npm run build`.

```bash
git add scripts outputs/templates backend/app/src frontend/src
git commit -m "提供销售订单导入模板"
```

### Task 7: 本地端到端验收

**Files:**
- No production file changes expected.
- Create temporary test workbook under `.codex-tmp/order-import/` only.

**Interfaces:**
- Verifies all prior tasks against the local Docker database.

- [ ] **Step 1: Record database baselines**

Record counts/sums for `sales_order`, `sales_order_item`, `inventory_balance.locked_quantity`, all `customer_fund_*` tables, and the selected customer/product IDs.

- [ ] **Step 2: Upload preview**

Create one formal two-line order and one draft order using real local unique customer/product codes. Assert preview has two groups, three valid rows and zero errors.

- [ ] **Step 3: Commit and verify**

Assert two orders and three items created; formal order contributes locked inventory, draft contributes none; customer fund counts and sums remain byte-for-byte equal to baseline.

- [ ] **Step 4: Verify duplicate protection**

Re-upload the same external order numbers and assert preview errors prevent commit.

- [ ] **Step 5: Run final regressions**

Run all import and sales order backend tests, full frontend tests, `npm run build`, `git diff --check`, and verify `http://localhost/api/system/health` returns database/application normal.

- [ ] **Step 6: Final commit if verification required corrections**

Commit only intentional tracked corrections with a Chinese message; do not stage `.codex-tmp/`, `data/`, unrelated `outputs/`, backups or Compose overrides.
