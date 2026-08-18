# 全业务页面产品身份信息统一展示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 所有业务页面完整、统一地按“产品编号 → 客户料号 → 型号”展示产品身份信息。

**Architecture:** 新建无业务依赖的 `ProductIdentityDisplay` 组件和格式化函数，页面只传入三个身份字段。后端在采购、订单、库存分配、业务追踪和售后查询中补齐三个字段，前端类型同步扩展；主列表保留独立列，紧凑表格和弹窗复用三行身份组件。

**Tech Stack:** Vue 3、TypeScript、CSS、Vitest、Java 17、Spring JDBC、MockMvc、JUnit 5

## Global Constraints

- 固定顺序为产品编号、客户料号、型号。
- 缺失字段显示“—”。
- 产品名称不作为身份字段展示，也不替代型号。
- 三个身份字段禁止省略号和固定高度裁切，空间不足时完整换行。
- 不修改产品唯一编号规则、数据库结构和业务计算。
- 后端只扩展响应字段，不改变请求结构。

---

### Task 1: 公共产品身份展示组件

**Files:**
- Create: `frontend/src/components/ProductIdentityDisplay.vue`
- Create: `frontend/src/components/ProductIdentityDisplay.test.ts`
- Create: `frontend/src/utils/product-identity.ts`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Produces: `ProductIdentity`、`productIdentityLines(identity)`、`productIdentitySearchText(identity)`。
- Produces: `<ProductIdentityDisplay :product-code :customer-part-number :model compact />`。

- [ ] **Step 1: 写失败测试**，断言标签顺序、空值“—”、长编号完整文本、无产品名称以及公共样式不含 `ellipsis`。
- [ ] **Step 2: 运行 `npm test -- ProductIdentityDisplay.test.ts`**，预期因组件不存在失败。
- [ ] **Step 3: 实现公共类型与格式化函数**：

```ts
export interface ProductIdentity { productCode?: string | null; customerPartNumber?: string | null; model?: string | null }
export const identityValue = (value?: string | null) => String(value ?? '').trim() || '—'
export const productIdentityLines = (value: ProductIdentity) => [
  `产品编号：${identityValue(value.productCode)}`,
  `客户料号：${identityValue(value.customerPartNumber)}`,
  `型号：${identityValue(value.model)}`,
]
export const productIdentitySearchText = (value: ProductIdentity) =>
  [value.productCode, value.customerPartNumber, value.model].filter(Boolean).join(' ')
```

- [ ] **Step 4: 实现三行展示组件和 CSS**；根元素使用 `.product-identity-display`，每行包含标签和值，值使用 `overflow-wrap:anywhere; word-break:break-word; white-space:normal`。
- [ ] **Step 5: 重跑组件测试**，预期通过。

### Task 2: 后端业务明细补齐三项身份字段

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/ProcurementWorkflowService.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/OrderAllocationService.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/SalesOrderCommandService.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/BusinessTraceService.java`
- Modify: `backend/app/src/main/java/com/internalops/aftersales/AfterSalesQueryService.java`
- Test: `backend/app/src/test/java/com/internalops/workbench/ProcurementWorkflowApiTest.java`
- Test: `backend/app/src/test/java/com/internalops/workbench/SalesOrderCommandApiTest.java`
- Test: `backend/app/src/test/java/com/internalops/workbench/WorkbenchQueryApiTest.java`

**Interfaces:**
- Produces: 采购建议、采购单明细、订单详情、库存分配、发货、业务追踪、售后明细中的 `productCode`、`customerPartNumber`、`model`。

- [ ] **Step 1: 在三个 API 测试中先断言 JSON 三字段**，例如 `$.data.items[0].productCode`、`customerPartNumber`、`model`。
- [ ] **Step 2: 运行目标 Maven 测试**，预期 `productCode` 等缺失断言失败。
- [ ] **Step 3: 给 SQL 查询补充 `s.product_code` 与 `s.model`**，并在手工映射的 Map 中写入：

```java
item.put("productCode", rs.getString("product_code"));
item.put("customerPartNumber", rs.getString("customer_part_number"));
item.put("model", rs.getString("model"));
```

- [ ] **Step 4: 业务追踪的订单与采购详情查询都返回 `s.product_code AS productCode`**，并将其列入身份字段组。
- [ ] **Step 5: 售后原商品、退货和换货查询均联接 `sku` 并返回三字段**。
- [ ] **Step 6: 重跑目标 Maven 测试**，预期通过。

### Task 3: 采购与库存页面统一产品身份展示

**Files:**
- Modify: `frontend/src/api/workbench.ts`
- Modify: `frontend/src/components/ProcurementReviewDialog.vue`
- Modify: `frontend/src/components/ProcurementReviewDialog.test.ts`
- Modify: `frontend/src/components/ManualPurchaseDialog.vue`
- Modify: `frontend/src/components/ManualPurchaseDialog.test.ts`
- Modify: `frontend/src/components/PurchaseReceiptDialog.vue`
- Modify: `frontend/src/components/PurchaseReceiptDialog.test.ts`
- Modify: `frontend/src/modules/module-config.ts`
- Modify: `frontend/src/modules/module-config.test.ts`

**Interfaces:**
- Consumes: `ProductIdentityDisplay` 与后端采购三字段。
- Produces: 采购复核、手工采购、采购收货、库存主列表的一致展示。

- [ ] **Step 1: 扩展类型**：`SupplierProductOption`、`PurchaseReceiptItem`、`ProcurementSuggestionItem`、`UnconfiguredProcurementShortage` 增加可空 `productCode` 与 `model`，已有字段保持兼容。
- [ ] **Step 2: 先修改测试数据为三个不同值并断言顺序**：

```ts
expect(identity.text()).toMatch(/产品编号.*BR_D51[\s\S]*客户料号.*D1213K-D51[\s\S]*型号.*D51-GEN2/)
expect(identity.text()).not.toContain('产品名称')
```

- [ ] **Step 3: 运行三组组件测试**，预期旧模板缺少产品编号或型号而失败。
- [ ] **Step 4: 将采购复核“物料”列、采购收货“物料”列、手工采购已选产品和搜索结果改用公共组件/格式化函数**。
- [ ] **Step 5: 库存主列表在产品编号后新增客户料号列**，字段为 `customerPartNumber`，保持型号列紧随其后。
- [ ] **Step 6: 重跑采购与模块配置测试**，预期通过。

### Task 4: 订单、分配、发货和业务追踪统一展示

**Files:**
- Modify: `frontend/src/api/workbench.ts`
- Modify: `frontend/src/components/OrderAllocationDialog.vue`
- Modify: `frontend/src/components/OrderAllocationDialog.test.ts`
- Modify: `frontend/src/components/ShipmentQuantityDialog.vue`
- Modify: `frontend/src/components/ShipmentQuantityDialog.test.ts`
- Modify: `frontend/src/components/BusinessTraceDialog.vue`
- Modify: `frontend/src/components/BusinessTraceDialog.test.ts`
- Modify: `frontend/src/components/OrderDialog.vue`
- Modify: `frontend/src/components/OrderDialog.test.ts`

**Interfaces:**
- Consumes: 订单、库存分配和追踪响应中的三字段。
- Produces: 所有订单操作弹窗的统一产品身份块。

- [ ] **Step 1: `OrderAllocationItem` 和发货明细前端类型增加 `productCode`、`model`**。
- [ ] **Step 2: 先在四组测试中断言三字段固定顺序和不显示产品名称**。
- [ ] **Step 3: 运行测试，预期库存分配、发货和追踪旧模板失败**。
- [ ] **Step 4: 库存分配卡片头部、发货明细卡片和业务追踪身份区域接入公共组件**。
- [ ] **Step 5: 订单新增/修改继续保留独立三字段，但搜索选项改用公共格式化函数，移除重复拼接代码**。
- [ ] **Step 6: 重跑四组订单相关测试**，预期通过。

### Task 5: 售后、客户、供应商与导入预览统一展示

**Files:**
- Modify: `frontend/src/api/after-sales.ts`
- Modify: `frontend/src/components/AfterSalesDialog.vue`
- Modify: `frontend/src/components/AfterSalesReceiptDialog.vue`
- Modify: `frontend/src/components/AfterSalesShipmentDialog.vue`
- Modify: `frontend/src/components/CustomerDialog.vue`
- Modify: `frontend/src/components/SupplierDialog.vue`
- Modify: `frontend/src/components/ImportPanel.vue`
- Modify corresponding `*.test.ts` files beside each component.

**Interfaces:**
- Consumes: 售后三字段与公共组件/格式化函数。
- Produces: 剩余业务页面的统一产品身份展示。

- [ ] **Step 1: 售后行类型增加 `productCode`，已有 `customerPartNumber` 与 `model` 保留**。
- [ ] **Step 2: 为原商品、换货选择、退货收货、换货发出、合同产品、供应产品和订单导入预览增加三字段顺序断言**。
- [ ] **Step 3: 运行对应测试，预期旧的单字段/双字段展示失败**。
- [ ] **Step 4: 售后三个弹窗使用公共组件，换货 `<option>` 使用 `productIdentityLines(...).join(' · ')`**。
- [ ] **Step 5: 客户合同与供应商产品复用公共格式化函数；订单导入预览增加型号列并显示完整值**。
- [ ] **Step 6: 重跑对应测试，预期通过。**

### Task 6: 全局验证与本地部署

**Files:**
- Verify all modified files only.

**Interfaces:**
- Consumes: Tasks 1–5 的全部实现。
- Produces: 可在 `http://localhost` 验收的本地版本。

- [ ] **Step 1: 运行相关前端测试**：

```powershell
npm test -- ProductIdentityDisplay.test.ts ProcurementReviewDialog.test.ts PurchaseReceiptDialog.test.ts ManualPurchaseDialog.test.ts OrderAllocationDialog.test.ts ShipmentQuantityDialog.test.ts BusinessTraceDialog.test.ts CustomerDialog.test.ts SupplierDialog.test.ts ImportPanel.test.ts
```

- [ ] **Step 2: 运行相关后端测试**：

```powershell
mvn -pl app -Dtest=ProcurementWorkflowApiTest,SalesOrderCommandApiTest,WorkbenchQueryApiTest test
```

- [ ] **Step 3: 运行 `npm run build`**，预期退出码 0。
- [ ] **Step 4: 重建本地容器**：`docker compose up -d --build`。
- [ ] **Step 5: 浏览器逐页检查采购复核、采购收货、库存分配、订单发货、售后和业务追踪，验证三字段顺序、长编号完整换行、无产品名称及无遮挡。
- [ ] **Step 6: 只暂存本计划新增/修改的独立文件；若共享文件含用户既有改动，保留工作区状态并在交付说明中列出，不混入提交。
