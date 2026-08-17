# 供应商产品空采购字段与采购提醒布局 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让供应商弹窗始终显示已关联产品，采购单价、最小起购量、交货天数可保持为空并后续补填，同时确保不完整关系不参与采购建议，并修复采购提醒展开裁切。

**Architecture:** 通过 V44 将 config/history 两张关系表的三个采购字段改为 nullable；后端保留供应商—产品关系，但只把三字段完整的报价视为有效采购信息。前端用 `number | null` 贯穿读取、编辑和提交，空关系列表合成一个可编辑空行。采购提醒自身不参与 Flex 收缩，下方表格继续承担滚动。

**Tech Stack:** MySQL/Flyway、Spring Boot/JdbcTemplate、JUnit、Vue 3/TypeScript/Vitest、Docker Compose。

## Global Constraints

- 现有非空采购单价、最小起购量和交货天数不得被清空。
- 新增供应商产品关系的三个采购字段默认保存为 `NULL`，不得用 `0` 或 `1` 代替未填写。
- 任一采购字段为空时，该关系不得生成采购建议，并继续出现在未配置提醒中。
- 服务器产品全量替换不属于本计划。
- `data/`、`outputs/`、本地临时 Compose 文件与服务器备份文件不得提交。

---

### Task 1: 数据库允许采购字段为空

**Files:**
- Create: `backend/app/src/main/resources/db/migration/V44__allow_incomplete_supplier_purchase_info.sql`
- Modify: `backend/app/src/test/java/com/internalops/workbench/WorkbenchSchemaTest.java`
- Modify: `backend/app/src/test/resources/supplier-management-schema.sql`
- Modify: `backend/app/src/test/resources/procurement-workflow-schema.sql`
- Modify: `backend/app/src/test/resources/product-replace-import-schema.sql`

**Interfaces:**
- Produces: `sku_supplier_config.purchase_price/moq/lead_time_days` 与 `sku_supplier_purchase_info` 同名字段均可为 `NULL`，无默认 0/1。

- [ ] **Step 1: 写失败的 schema 测试**

在 `WorkbenchSchemaTest` 断言两张表的三个字段 `IS_NULLABLE='YES'`，且 `lead_time_days` 的 `COLUMN_DEFAULT IS NULL`。

- [ ] **Step 2: 验证 RED**

Run: `mvn -Dmaven.repo.local=.m2 -pl app -Dtest=WorkbenchSchemaTest test`

Expected: FAIL，现有字段仍为 `NOT NULL` 或默认 `0`。

- [ ] **Step 3: 添加 V44 最小迁移**

```sql
ALTER TABLE sku_supplier_config
    MODIFY purchase_price DECIMAL(18,4) NULL,
    MODIFY moq INT NULL,
    MODIFY lead_time_days INT NULL DEFAULT NULL;

ALTER TABLE sku_supplier_purchase_info
    MODIFY purchase_price DECIMAL(18,4) NULL,
    MODIFY moq INT NULL,
    MODIFY lead_time_days INT NULL DEFAULT NULL;
```

同步测试 schema 的列定义；保留现有非负/正数 CHECK，MySQL 对 `NULL` 允许通过。

- [ ] **Step 4: 验证 GREEN 并提交**

Run: `mvn -Dmaven.repo.local=.m2 -pl app -Dtest=WorkbenchSchemaTest test`

Expected: PASS。

Commit: `允许供应商采购字段为空`

### Task 2: 后端保存并返回空采购信息

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/SupplierManagementService.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ProductReplaceImportService.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/SupplierManagementApiTest.java`
- Modify: `backend/app/src/test/java/com/internalops/importing/ProductReplaceImportServiceTest.java`

**Interfaces:**
- Consumes: Task 1 nullable schema。
- Produces: supplier detail 中的关联产品即使没有采购历史也存在；三个字段类型保持 nullable；导入关系不再写 `0/1/0`。

- [ ] **Step 1: 写供应商 API 失败测试**

新增用例：保存一个关联产品且三个字段均 `null`；详情响应 `products` 包含产品并返回空字段；再次提交 `12.50/20/7` 后详情返回补填值。

- [ ] **Step 2: 写产品替换失败测试**

断言缺失供应商价格的导入关系写入 config/history 时三个字段均为 `NULL`，而不是 `0/1/0`。

- [ ] **Step 3: 验证 RED**

Run: `mvn -Dmaven.repo.local=.m2 -pl app -Dtest=SupplierManagementApiTest,ProductReplaceImportServiceTest test`

Expected: FAIL，当前校验拒绝空字段或导入写入默认数值。

- [ ] **Step 4: 实现 nullable 保存与详情回退**

- `SupplierManagementService.validate` 仅校验非空值：价格 `>=0`、MOQ `>0`、交货天数 `>=0`。
- `price`/整数绑定使用 `setObject(..., null)` 或等价 nullable 写法。
- 没有 purchase history 时仍由 config 返回产品，并给出一条三字段均空的可编辑视图。
- `ProductReplaceImportService` 创建 config 与初始 purchase-info 时使用源值或 `NULL`，不调用 `decimalOrZero`，MOQ/交货天数不写默认值。

- [ ] **Step 5: 验证 GREEN 并提交**

Run: `mvn -Dmaven.repo.local=.m2 -pl app -Dtest=SupplierManagementApiTest,ProductReplaceImportServiceTest test`

Expected: PASS。

Commit: `支持供应商产品采购字段后续补填`

### Task 3: 采购流程只使用完整采购信息

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/ProcurementWorkflowService.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/ProcurementWorkflowApiTest.java`

**Interfaces:**
- Consumes: nullable purchase-info。
- Produces: 完整信息判定 `purchase_price IS NOT NULL AND moq IS NOT NULL AND lead_time_days IS NOT NULL`。

- [ ] **Step 1: 写失败测试**

分别建立价格空、MOQ 空、交货天数空的三条供应关系；断言 `/unconfigured-shortages` 仍返回对应产品，`generate` 不创建采购建议。补齐三字段后断言提醒消失且可生成建议。

- [ ] **Step 2: 验证 RED**

Run: `mvn -Dmaven.repo.local=.m2 -pl app -Dtest=ProcurementWorkflowApiTest test`

Expected: FAIL 或 nullable 映射异常。

- [ ] **Step 3: 最小实现**

在候选报价 SQL 与 `NOT EXISTS` 有效关系 SQL 中同时加入：

```sql
AND pi.purchase_price IS NOT NULL
AND pi.moq IS NOT NULL
AND pi.lead_time_days IS NOT NULL
```

保留原有 enabled、最新记录与数值范围条件。

- [ ] **Step 4: 验证 GREEN 并提交**

Run: `mvn -Dmaven.repo.local=.m2 -pl app -Dtest=ProcurementWorkflowApiTest test`

Expected: PASS。

Commit: `过滤未完整配置的供应商采购信息`

### Task 4: 供应商弹窗展示空字段并允许补填

**Files:**
- Modify: `frontend/src/api/workbench.ts`
- Modify: `frontend/src/components/SupplierDialog.vue`
- Modify: `frontend/src/components/SupplierDialog.test.ts`

**Interfaces:**
- Consumes: 后端 nullable supplier detail/request。
- Produces: `purchasePrice/moq/leadTimeDays: number | null`；`numberOrNull` 将空字符串、`undefined`、`null` 统一为 `null`。

- [ ] **Step 1: 写失败测试**

覆盖四个行为：`purchaseInfos=[]` 仍显示产品；新增产品三个输入框为空；空保存 payload 三字段为 `null`；重新填写后提交数值。

- [ ] **Step 2: 验证 RED**

Run: `npm test -- SupplierDialog.test.ts`

Expected: FAIL，当前空数组不渲染且 `Number('')` 变为 0。

- [ ] **Step 3: 最小实现**

```ts
const numberOrNull = (value: unknown): number | null => {
  if (value === '' || value === null || value === undefined) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}
```

- 类型改为 `number | null`。
- 新产品/新报价默认三个字段均 `null`。
- 初始化时若产品的 `purchaseInfos` 为空，合成一个空编辑行。
- 校验只拒绝非空的非法值；允许三项为空或部分为空保存。

- [ ] **Step 4: 验证 GREEN 并提交**

Run: `npm test -- SupplierDialog.test.ts`

Expected: PASS。

Commit: `显示供应商关联产品的空采购字段`

### Task 5: 修复采购配置提醒展开裁切

**Files:**
- Modify: `frontend/src/components/ProcurementConfigurationAlert.vue`
- Modify: `frontend/src/components/ProcurementConfigurationAlert.test.ts`

**Interfaces:**
- Produces: alert 根元素不可 Flex 收缩，内部详情仍按现有规则滚动。

- [ ] **Step 1: 写失败测试**

断言根元素样式契约包含 `flex: 0 0 auto`，展开后明细和“前往供应商管理”按钮仍存在且不被父 alert 裁切。

- [ ] **Step 2: 验证 RED**

Run: `npm test -- ProcurementConfigurationAlert.test.ts`

Expected: FAIL，根元素当前允许收缩。

- [ ] **Step 3: 最小布局修复**

```css
.procurement-configuration-alert {
  flex: 0 0 auto;
  overflow: hidden;
}
```

不修改页面整体高度，不移除圆角裁切。

- [ ] **Step 4: 验证 GREEN 并提交**

Run: `npm test -- ProcurementConfigurationAlert.test.ts`

Expected: PASS。

Commit: `修复采购配置提醒展开裁切`

### Task 6: 全量回归、本地启动与部署准备

**Files:**
- Verify only; no unrelated changes.

**Interfaces:**
- Consumes: Tasks 1-5。
- Produces: 可部署的 `main`，危险产品替换开关保持关闭。

- [ ] **Step 1: 后端回归**

Run: `mvn -Dmaven.repo.local=.m2 -pl app -am test`

Expected: 全部相关测试通过；若共享 H2 schema 导致已知独立 ERROR，单独运行 supplier/import/procurement/schema 测试并记录证据。

- [ ] **Step 2: 前端回归与构建**

Run: `npm test`

Run: `npm run build`

Expected: tests PASS，build SUCCESS。

- [ ] **Step 3: 数据库迁移验证**

使用原 `gmt-inventory_mysql_data` 启动，确认 V44 成功且已有供应商关系数值未变化；新增空关系可保存并后续补填。

- [ ] **Step 4: 浏览器烟测**

验证供应商产品显示、三个空输入、保存/补填、采购提醒展开完整、采购列表滚动。

- [ ] **Step 5: 最终提交**

只提交计划内文件；确认 `data/`、`outputs/`、临时 Compose 均未暂存。

Commit: `完成供应商采购信息空值与布局修复`
