# 产品多供应商报价 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 允许一个产品关联多家供应商，并在产品管理用“供应商名称：采购价格”的可变列表展示全部有效报价，同时隐藏指定的三个固定价格列。

**Architecture:** 以 `sku_supplier_config` 作为唯一事实来源，将唯一约束从 `sku_id` 改为 `(sku_id, supplier_id)`。供应商保存仅增删本供应商的组合关系；产品分页查询保持一产品一行，并用相关子查询返回供应商报价 JSON，前端用专用单元格渲染数组。

**Tech Stack:** MySQL 8.4、Flyway、Spring Boot/JdbcTemplate、JUnit/MockMvc、Vue 3/TypeScript、Vitest、Docker Compose。

## Global Constraints

- 直接在当前 `main` 分支实施，不创建工作树。
- `data/` 是本地数据库备份，禁止暂存或提交。
- 旧的 `current_cost`、`factory_price` 和 `sales_minimum_order_quantity` 字段继续保留；仅从产品列表隐藏成本单价、转厂价格和价格差异。
- 同一 `(sku_id, supplier_id)` 最多一条关系；停用关系可被重新启用。
- 供应商报价只包含有效关系、有效产品和有效供应商。
- 供应商报价按供应商名称、供应商 ID 稳定排序。

---

### Task 1: 数据库支持多供应商关系

**Files:**
- Create: `backend/app/src/main/resources/db/migration/V38__allow_multiple_suppliers_per_sku.sql`
- Modify: `backend/app/src/test/resources/supplier-management-schema.sql`
- Modify: `backend/app/src/test/resources/workbench-query-schema.sql`

**Interfaces:**
- Produces: `UNIQUE(sku_id, supplier_id)`，替代 `UNIQUE(sku_id)`。
- Preserves: 所有既有 `sku_supplier_config` 数据。

- [ ] **Step 1: 写失败的多供应商 API 测试**

在 `SupplierManagementApiTest` 新增测试：先创建第二个供应商并关联 `skuId=101`，再断言 `/api/products/101/suppliers` 返回原供应商和新供应商两项；同时断言数据库中该 SKU 有两条启用关系。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=SupplierManagementApiTest test`

Expected: FAIL；现有 `sku_id` 唯一约束或保存逻辑导致只能保留一个供应商。

- [ ] **Step 3: 添加迁移和测试表约束**

迁移中删除 `uk_sku_supplier_config_sku`，增加 `uk_sku_supplier_config_sku_supplier (sku_id, supplier_id)`；测试 schema 使用组合唯一约束。

- [ ] **Step 4: 运行数据库相关测试确认约束生效**

Run: `mvn -Dtest=SupplierManagementApiTest test`

Expected: 关系表允许同一 SKU 的两个不同供应商，但测试仍可能因保存 SQL 覆盖其他供应商而失败。

### Task 2: 供应商保存只维护自己的产品关系

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/SupplierManagementService.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/SupplierManagementApiTest.java`

**Interfaces:**
- Consumes: 组合唯一关系 `(sku_id, supplier_id)`。
- Produces: `replaceProducts(long supplierId, List<SupplierProductConfigRequest>)` 只停用和更新当前 `supplierId` 的关系。

- [ ] **Step 1: 补充失败测试**

新增测试覆盖：供应商 A 和 B 都关联 SKU 101；更新 A 删除 SKU 101 后，B 的关系仍启用；A 再次添加后复用并启用原组合，且没有重复记录。

- [ ] **Step 2: 运行测试确认失败原因**

Run: `mvn -Dtest=SupplierManagementApiTest test`

Expected: FAIL；当前 `UPDATE ... WHERE sku_id=?` 会把其他供应商关系改成当前供应商。

- [ ] **Step 3: 最小实现**

把关系更新条件改为 `WHERE sku_id=? AND supplier_id=?`，参数顺序包含组合键；当前供应商未提交的关系仍由现有批量停用语句处理。

- [ ] **Step 4: 验证通过**

Run: `mvn -Dtest=SupplierManagementApiTest test`

Expected: PASS。

### Task 3: 产品列表返回全部供应商报价

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/WorkbenchQueryApiTest.java`
- Modify: `backend/app/src/test/resources/workbench-query-schema.sql`

**Interfaces:**
- Produces: 产品列表每行新增 `supplierQuotes: Array<{supplierId:number,supplierName:string,purchasePrice:number}>`。
- Preserves: 产品分页一产品一行，不因多供应商产生重复行。

- [ ] **Step 1: 写失败查询测试**

测试数据为同一产品插入两个不同供应商报价，调用 `/api/workbench/product` 后断言 `total=1`、`items.length=1`，且 `supplierQuotes` 按名称返回两项和各自价格。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=WorkbenchQueryApiTest test`

Expected: FAIL；当前产品主查询直接连接关系表，会返回重复产品行且没有报价数组。

- [ ] **Step 3: 实现相关子查询和 JSON 解析**

产品主查询移除 `sku_supplier_config/supplier` 的外连接和单供应商列，增加按产品聚合的 JSON 字符串列；在分页结果标准化后将其解析为 `supplierQuotes` 数组。空关系返回空数组。

- [ ] **Step 4: 验证产品查询**

Run: `mvn -Dtest=WorkbenchQueryApiTest,SupplierManagementApiTest test`

Expected: PASS。

### Task 4: 产品管理显示可变报价列表

**Files:**
- Modify: `frontend/src/modules/module-config.ts`
- Modify: `frontend/src/modules/module-config.test.ts`
- Modify: `frontend/src/api/workbench.ts`
- Modify: `frontend/src/components/ModuleListPage.vue`
- Modify: `frontend/src/components/ModuleListPage.test.ts`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Consumes: `supplierQuotes: SupplierQuote[]`。
- Produces: “供应商报价”单元格，每项格式 `供应商名称：¥价格`。

- [ ] **Step 1: 写失败配置测试**

断言产品字段包含 `supplierQuotes` 和“供应商报价”，不包含 `currentCost`、`factoryPrice`、`priceDifference`，仍包含 `salesMinimumOrderQuantity`。

- [ ] **Step 2: 写失败渲染测试**

用包含两个报价的产品行挂载 `ModuleListPage`，断言同一单元格显示两行“供应商甲：¥100”“供应商乙：¥105”；空数组显示“—”。

- [ ] **Step 3: 运行测试确认失败**

Run: `npm test -- --run src/modules/module-config.test.ts src/components/ModuleListPage.test.ts`

Expected: FAIL；配置仍含三个价格列，通用文本渲染不能显示报价数组。

- [ ] **Step 4: 最小实现列表与样式**

更新产品列定义；新增 `SupplierQuote` 类型；为 `supplierQuotes` 添加专用模板分支和 `supplier-quotes` 样式，单元格宽度设为约 260px，价格最多保留四位有效小数并使用人民币符号。

- [ ] **Step 5: 验证前端目标测试**

Run: `npm test -- --run src/modules/module-config.test.ts src/components/ModuleListPage.test.ts`

Expected: PASS。

### Task 5: 全量验证、本地部署与提交

**Files:**
- Verify only: all changed files

**Interfaces:**
- Produces: 本地 `http://localhost` 运行最新版，GitHub `main` 包含实现提交。

- [ ] **Step 1: 后端完整测试**

Run: `mvn test` in `backend/app`

Expected: BUILD SUCCESS，0 failures，0 errors。

- [ ] **Step 2: 前端完整测试和构建**

Run: `npm test -- --run` and `npm run build` in `frontend`

Expected: 全部测试通过，Vite build exit 0。

- [ ] **Step 3: 差异与提交范围检查**

Run: `git diff --check`、`git status --short`。

Expected: 无空白错误；`data/` 保持未跟踪且不暂存。

- [ ] **Step 4: 重建本地 Docker**

Run: `docker compose build backend frontend`，再运行 `docker compose up -d --force-recreate backend frontend`。

Expected: MySQL 卷不重建；Flyway V38 成功；三个服务正常运行。

- [ ] **Step 5: 运行态检查**

检查 `/api/system/health` 返回 200；验证 Flyway 最新版本为 38；从实际产品 API 验证同一产品可返回多项 `supplierQuotes`；确认浏览器加载的新 JS 包含“供应商报价”。

- [ ] **Step 6: 中文提交并推送**

仅暂存实现文件，提交信息使用“支持产品多供应商报价展示”，推送 `origin main`。
