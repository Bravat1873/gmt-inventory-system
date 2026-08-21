# 产品供应商报价维护 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在产品新增和修改页维护多个供应商的采购单价、最小起购量和交货天数，并同步供应商管理。

**Architecture:** 产品请求增加 `supplierQuotes` 数组。后端在产品事务中复用 `sku_supplier_config` 和 `sku_supplier_purchase_info` 写入报价；产品详情读取同一数据；前端从启用供应商中选择并随产品提交。

**Tech Stack:** Spring Boot、JdbcTemplate、JUnit 5 / MockMvc、Vue 3、TypeScript、Vitest。

## Global Constraints

- 不新增报价表。
- 同一产品内供应商不可重复。
- 单价最多四位小数且不小于零；最小起购量为正整数；交货天数为非负整数。
- 未提交 `supplierQuotes` 时不改既有报价；提交 `[]` 时移除全部报价。

---

### Task 1: 扩展产品报价命令

**Files:** Create `backend/app/src/main/java/com/internalops/workbench/ProductSupplierQuoteCommand.java`; modify `EntityCommandRequest.java`, `EntityCommandFields.java`, `MasterDataCommandService.java`; test `MasterDataCommandApiTest.java`.

**Interfaces:** Request field `supplierQuotes: [{ supplierId, purchasePrice, moq, leadTimeDays }]`; command record `ProductSupplierQuoteCommand(Long supplierId, BigDecimal purchasePrice, Integer moq, Integer leadTimeDays)`; field-presence flag `supplierQuotesPresent`.

- [ ] **Step 1: Write failing MockMvc tests**: POST a valid product with `[ {"supplierId":1,"purchasePrice":390.5,"moq":10,"leadTimeDays":7} ]`, assert its `sku_supplier_config` and `sku_supplier_purchase_info` rows; PUT the same product with `supplierQuotes: []`, assert no enabled config remains. POST duplicate `supplierId` and expect `同一供应商不能重复报价`.
- [ ] **Step 2: Run `mvn -q -pl app -am "-Dtest=MasterDataCommandApiTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`**. Expected: FAIL because `supplierQuotes` is currently ignored.
- [ ] **Step 3: Implement the request record and JSON parsing**: parse the list only when the JSON body contains `supplierQuotes`; reject non-array values with `供应商报价格式不正确`.
- [ ] **Step 4: Implement `replaceProductSupplierQuotes(long skuId, List<ProductSupplierQuoteCommand> quotes)`**: validate enabled supplier ids and numeric bounds; upsert one `sku_supplier_config` and one enabled `sku_supplier_purchase_info` per quote; disable configurations absent from the submitted list; invoke after SKU create or successful optimistic update only when the presence flag is true.
- [ ] **Step 5: Run the Task 1 Maven command again**. Expected: PASS.
- [ ] **Step 6: Commit only Task 1 sources and test with message `支持产品维护供应商报价`.**

### Task 2: 返回产品报价与供应商选项

**Files:** Modify `backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java`, `frontend/src/api/workbench.ts`; test `MasterDataCommandApiTest.java` and existing frontend API tests.

**Interfaces:** Product detail adds `supplierQuotes` items with supplier id/name, purchase price, MOQ and lead time. Frontend adds typed `loadSupplierOptions(keyword)`.

- [ ] **Step 1: Write failing API test**: GET `/api/workbench/product/1`, expect `data.supplierQuotes[0].supplierName` and `data.supplierQuotes[0].moq` after inserting an enabled test configuration.
- [ ] **Step 2: Run the Task 1 Maven command**. Expected: FAIL because product detail has no quote list.
- [ ] **Step 3: Add a product-detail query joining `sku_supplier_config` to `supplier`, filtered by enabled SKU, config and supplier rows, then attach it to the product detail response.**
- [ ] **Step 4: Add `SupplierOption` and `loadSupplierOptions` in `frontend/src/api/workbench.ts`, calling the existing supplier-options endpoint.**
- [ ] **Step 5: Run `mvn -q -pl app -am "-Dtest=MasterDataCommandApiTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` and `npm test -- --run src/api/workbench.test.ts`.** Expected: PASS.
- [ ] **Step 6: Commit only Task 2 files with message `返回产品供应商报价明细`.**

### Task 3: 产品表单报价编辑器

**Files:** Modify `frontend/src/components/EntityDialog.vue`; test `frontend/src/components/EntityDialog.test.ts`.

**Interfaces:** Product form state is an array of `{ supplierId: number | null, purchasePrice: number | null, moq: number, leadTimeDays: number }`; product payload includes `supplierQuotes`.

- [ ] **Step 1: Write failing Vitest case**: add a quote row, select supplier `3`, set unit price `390.5`, submit, and expect `createEntity('product', ...)` receives `supplierQuotes: [{ supplierId: 3, purchasePrice: 390.5, moq: 1, leadTimeDays: 0 }]`.
- [ ] **Step 2: Run `npm test -- --run src/components/EntityDialog.test.ts`.** Expected: FAIL because the product form only displays quotes as read-only text.
- [ ] **Step 3: Replace the read-only product quote block with product-only editable rows after the remark field. Load enabled suppliers on mount, initialize existing quote rows, and provide selection, price, MOQ, lead-time, add, and remove controls.**
- [ ] **Step 4: Validate before save**: reject blank supplier, duplicate supplier, negative price, non-positive MOQ and negative lead time using the backend Chinese messages; include an empty array when the user removes all rows.
- [ ] **Step 5: Run `npm test -- --run src/components/EntityDialog.test.ts src/components/ModuleListPage.test.ts` and `npm run build`.** Expected: PASS.
- [ ] **Step 6: Commit only Task 3 files with message `在产品页面维护供应商报价`.**

### Task 4: Full verification and release

**Files:** Verify only; do not modify unrelated files.

- [ ] **Step 1: Run `mvn -q -pl app -am "-Dtest=MasterDataCommandApiTest,ExcelExportServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`.** Expected: PASS.
- [ ] **Step 2: Run `npm test -- --run src/components/EntityDialog.test.ts src/components/ModuleListPage.test.ts src/api/workbench.test.ts` and `npm run build`.** Expected: PASS.
- [ ] **Step 3: Run `git diff --check` and inspect `git status --short`; stage only planned files, commit with `完善产品供应商报价维护`, and push only after user approval.**
- [ ] **Step 4: Deploy only after the user explicitly asks to update the server.**
