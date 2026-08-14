# 供应商产品多版采购信息 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为同一供应商产品关系保存多条采购信息，采购下单只使用供应商报价并默认最新记录，同时提供产品全名悬浮预览。

**Architecture:** 保留 `sku_supplier_config` 作为唯一供应关系，新增独立的采购信息历史表，并在采购单明细保存采购信息 ID 与价格快照。供应商页面按产品分组维护可变采购信息列表；手工采购按产品、供应商、采购信息三级选择，后端以采购信息记录为可信价格来源。

**Tech Stack:** Java 17、Spring Boot、JdbcTemplate、Flyway、MySQL 8、JUnit 5、Vue 3、TypeScript、Vitest。

## Global Constraints

- 采购管理不得再使用或显示成本单价、转厂价格、`CURRENT_COST`、`FACTORY_PRICE` 或 `priceSource`。
- 一个供应商产品关系至少需要一条有效采购信息，移除产品关系时允许整体停用。
- 新采购单必须保存采购信息 ID 和采购单价快照，历史采购单不得因报价修改而变化。
- 同一范围内默认记录统一按 `updated_at DESC, id DESC` 选择。
- 保留现有 Docker 数据卷，不删除或重建数据库。
- 不修改或提交现有未跟踪的 `data/`。

---

### Task 1: 建立采购信息历史数据模型

**Files:**
- Create: `backend/app/src/main/resources/db/migration/V40__supplier_purchase_info_history.sql`
- Modify: `backend/app/src/test/java/com/internalops/workbench/WorkbenchSchemaTest.java`

**Interfaces:**
- Produces: 表 `sku_supplier_purchase_info`；列 `purchase_order_item.supplier_purchase_info_id` 与 `procurement_suggestion_item.supplier_purchase_info_id`。

- [ ] **Step 1: 编写失败的迁移结构测试**

在 `WorkbenchSchemaTest` 增加测试，读取 V40 并断言包含：新表主键、`supplier_product_config_id` 外键、`purchase_price`、`moq`、`lead_time_days`、`enabled`、`created_at`、`updated_at`、`version`，以及采购单明细的可空外键。断言迁移包含从现有 `sku_supplier_config` 回填首条采购信息的 `INSERT ... SELECT`。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -f backend/pom.xml -pl app -Dtest=WorkbenchSchemaTest test`

Expected: FAIL，因为 V40 尚不存在。

- [ ] **Step 3: 创建迁移脚本**

V40 使用如下结构：

```sql
CREATE TABLE sku_supplier_purchase_info (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_product_config_id BIGINT NOT NULL,
    purchase_price DECIMAL(18,4) NOT NULL,
    moq INT NOT NULL,
    lead_time_days INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_purchase_info_supplier_product
      FOREIGN KEY (supplier_product_config_id) REFERENCES sku_supplier_config(id),
    INDEX idx_purchase_info_latest (supplier_product_config_id, enabled, updated_at, id)
);

INSERT INTO sku_supplier_purchase_info(
  supplier_product_config_id,purchase_price,moq,lead_time_days,enabled,created_at,updated_at)
SELECT id,purchase_price,moq,lead_time_days,enabled,created_at,updated_at
FROM sku_supplier_config;

ALTER TABLE purchase_order_item
  ADD COLUMN supplier_purchase_info_id BIGINT NULL,
  ADD CONSTRAINT fk_purchase_item_supplier_info
    FOREIGN KEY (supplier_purchase_info_id) REFERENCES sku_supplier_purchase_info(id);

ALTER TABLE procurement_suggestion_item
  ADD COLUMN supplier_purchase_info_id BIGINT NULL,
  ADD CONSTRAINT fk_suggestion_item_supplier_info
    FOREIGN KEY (supplier_purchase_info_id) REFERENCES sku_supplier_purchase_info(id);
```

- [ ] **Step 4: 运行迁移测试**

Run: `mvn -f backend/pom.xml -pl app -Dtest=WorkbenchSchemaTest test`

Expected: PASS。

- [ ] **Step 5: 提交数据模型**

```bash
git add backend/app/src/main/resources/db/migration/V40__supplier_purchase_info_history.sql backend/app/src/test/java/com/internalops/workbench/WorkbenchSchemaTest.java
git commit -m "新增供应商采购信息历史表"
```

### Task 2: 供应商接口支持每个产品多条采购信息

**Files:**
- Create: `backend/app/src/main/java/com/internalops/workbench/SupplierPurchaseInfoRequest.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/SupplierProductConfigRequest.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/SupplierManagementService.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/SupplierManagementApiTest.java`

**Interfaces:**
- Produces: `SupplierPurchaseInfoRequest(Long id, BigDecimal purchasePrice, Integer moq, Integer leadTimeDays, Integer version)`。
- Produces: `SupplierProductConfigRequest(Long skuId, List<SupplierPurchaseInfoRequest> purchaseInfos)`。
- Produces: 供应商详情 `products[].purchaseInfos[]`，按 `updatedAt DESC, id DESC`。

- [ ] **Step 1: 编写多采购信息保存失败测试**

在 `SupplierManagementApiTest` 提交同一 `skuId` 下两条 `purchaseInfos`，断言创建后详情返回一个产品组和两条记录；修改其中一条后断言 ID 不变、`version` 增加、`updatedAt` 更新；新增第三条后断言三条都存在。

- [ ] **Step 2: 编写校验与软删除失败测试**

覆盖空采购信息列表、负价格、零起订量、负交货天数、重复采购信息 ID、错误版本均返回 400；从请求中删除一条已保存记录后，该记录 `enabled=FALSE`，历史行仍存在。

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn -f backend/pom.xml -pl app -Dtest=SupplierManagementApiTest test`

Expected: FAIL，因为请求仍是单条价格结构。

- [ ] **Step 4: 实现嵌套请求与集中校验**

新增 record：

```java
public record SupplierPurchaseInfoRequest(
        Long id, BigDecimal purchasePrice, Integer moq,
        Integer leadTimeDays, Integer version) {}

public record SupplierProductConfigRequest(
        Long skuId, List<SupplierPurchaseInfoRequest> purchaseInfos) {}
```

验证每个产品只有一个分组、每组至少一条信息，每条价格最多四位小数且非负、MOQ 大于零、交货天数非负。

- [ ] **Step 5: 实现关系和采购信息保存**

`replaceProducts` 先按 `(supplier_id, sku_id)` upsert 供应关系，再按采购信息 ID 更新或插入；更新 SQL 必须同时限定关系 ID 和版本。未出现在请求中的旧采购信息设为 `enabled=FALSE`，不得物理删除。

- [ ] **Step 6: 实现分组详情查询**

`supplierDetail` 返回每个产品一次，并嵌套：

```json
{"skuId":1,"purchaseInfos":[
  {"id":11,"purchasePrice":237.73,"moq":500,"leadTimeDays":45,
   "updatedAt":"2026-08-14T10:30:00","version":0}
]}
```

- [ ] **Step 7: 运行供应商测试**

Run: `mvn -f backend/pom.xml -pl app -Dtest=SupplierManagementApiTest test`

Expected: PASS。

- [ ] **Step 8: 提交供应商接口**

```bash
git add backend/app/src/main/java/com/internalops/workbench/SupplierPurchaseInfoRequest.java backend/app/src/main/java/com/internalops/workbench/SupplierProductConfigRequest.java backend/app/src/main/java/com/internalops/workbench/SupplierManagementService.java backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java backend/app/src/test/java/com/internalops/workbench/SupplierManagementApiTest.java
git commit -m "支持供应商产品多条采购信息"
```

### Task 3: 供应商修改页展示可变采购信息列表和产品全名

**Files:**
- Modify: `frontend/src/api/workbench.ts`
- Modify: `frontend/src/components/SupplierDialog.vue`
- Modify: `frontend/src/components/SupplierDialog.test.ts`
- Modify: `frontend/src/components/EntityDialog.test.ts`

**Interfaces:**
- Consumes: `products[].purchaseInfos[]`。
- Produces: `SupplierPurchaseInfoConfig { id?, purchasePrice, moq, leadTimeDays, updatedAt?, version? }`。

- [ ] **Step 1: 编写产品全名悬浮失败测试**

渲染超长产品名称，断言可见文本仍位于单行省略容器，且容器 `title` 等于完整的“产品编号 · 产品名称 · 型号/规格”文本，不增加表格行高。

- [ ] **Step 2: 编写可变列表失败测试**

详情返回同一产品两条采购信息，断言页面显示两行、各自修改时间；点击“新增采购信息”增加空行并显示“保存后生成”；删除已保存行后提交 payload 不再包含该 ID；同一产品不会重复成两个产品组。

- [ ] **Step 3: 运行组件测试确认失败**

Run: `npm --prefix frontend test -- --run src/components/SupplierDialog.test.ts src/components/EntityDialog.test.ts`

Expected: FAIL，因为当前每个产品只有一条采购信息。

- [ ] **Step 4: 更新前端类型**

在 `workbench.ts` 定义：

```ts
export interface SupplierPurchaseInfoConfig {
  id?: number
  purchasePrice: number
  moq: number
  leadTimeDays: number
  updatedAt?: string
  version?: number
}
export interface SupplierProductConfig {
  skuId: number
  purchaseInfos: SupplierPurchaseInfoConfig[]
}
```

- [ ] **Step 5: 实现分组列表界面**

每个产品组使用固定宽度、单行省略的产品名称元素并设置完整 `title`。在组内渲染采购信息行：采购单价、MOQ、交货天数、修改时间、删除；组标题处增加“新增采购信息”。修改时间为空显示“保存后生成”。

- [ ] **Step 6: 实现提交转换和前端校验**

提交时保持 `id`、`version`，移除纯展示字段；阻止空列表、非法价格、MOQ 和交货天数保存，并定位到具体产品和采购信息序号。

- [ ] **Step 7: 运行供应商页面测试**

Run: `npm --prefix frontend test -- --run src/components/SupplierDialog.test.ts src/components/EntityDialog.test.ts`

Expected: PASS。

- [ ] **Step 8: 提交供应商页面**

```bash
git add frontend/src/api/workbench.ts frontend/src/components/SupplierDialog.vue frontend/src/components/SupplierDialog.test.ts frontend/src/components/EntityDialog.test.ts
git commit -m "在供应商页面维护多版采购信息"
```

### Task 4: 产品供应商选项返回多版采购信息并默认最新

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/SupplierManagementService.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/SupplierManagementApiTest.java`
- Modify: `frontend/src/api/workbench.ts`

**Interfaces:**
- Produces: `ProductSupplierOption.purchaseInfos: SupplierPurchaseInfoOption[]`。
- Produces: `latestPurchaseInfo` 为 `purchaseInfos[0]`。

- [ ] **Step 1: 编写供应商选项排序失败测试**

为同一供应商产品插入三条有效采购信息和一条停用信息，调用 `/api/products/{skuId}/suppliers`，断言只返回有效记录并按 `updatedAt DESC, id DESC`；供应商只出现一次，`latestPurchaseInfo` 等于第一条。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -f backend/pom.xml -pl app -Dtest=SupplierManagementApiTest test`

Expected: FAIL，当前接口只返回单个 `purchasePrice/moq/leadTimeDays`。

- [ ] **Step 3: 实现供应商选项聚合**

查询供应关系和有效采购信息，Java 侧按供应商分组构造：

```json
{"supplierId":2,"supplierName":"供应商A",
 "purchaseInfos":[{"id":8,"purchasePrice":220,"moq":100,"leadTimeDays":15,"updatedAt":"..."}],
 "latestPurchaseInfo":{"id":8,"purchasePrice":220,"moq":100,"leadTimeDays":15,"updatedAt":"..."}}
```

- [ ] **Step 4: 更新前端选项类型**

移除 `ProductSupplierOption` 顶层的单一价格字段，改为 `purchaseInfos` 和 `latestPurchaseInfo`。

- [ ] **Step 5: 运行后端测试和 TypeScript 构建**

Run: `mvn -f backend/pom.xml -pl app -Dtest=SupplierManagementApiTest test`

Run: `npm --prefix frontend run build`

Expected: PASS。

- [ ] **Step 6: 提交选项接口**

```bash
git add backend/app/src/main/java/com/internalops/workbench/SupplierManagementService.java backend/app/src/test/java/com/internalops/workbench/SupplierManagementApiTest.java frontend/src/api/workbench.ts
git commit -m "返回供应商多版采购信息选项"
```

### Task 5: 手工采购移除成本价和转厂价并选择供应商采购信息

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/ManualPurchaseRequest.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/ProcurementWorkflowService.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/ProcurementWorkflowApiTest.java`
- Modify: `frontend/src/api/workbench.ts`
- Modify: `frontend/src/components/ManualPurchaseDialog.vue`
- Modify: `frontend/src/components/ManualPurchaseDialog.test.ts`

**Interfaces:**
- Consumes: `supplierPurchaseInfoId`。
- Produces: 新采购单明细的 `supplier_purchase_info_id` 与 `purchase_price` 快照。

- [ ] **Step 1: 编写后端采购信息可信来源失败测试**

覆盖：合法采购信息创建成功并保存 ID/价格快照；采购信息不属于供应商或产品、已停用、不存在、数量低于 MOQ 均返回 400；请求即使伪造采购单价也不能改变数据库价格。

- [ ] **Step 2: 编写移除旧价格来源失败测试**

提交仅含旧 `priceSource=CURRENT_COST` 或 `FACTORY_PRICE` 且无 `supplierPurchaseInfoId` 的请求，断言返回 400；测试服务代码不再查询 SKU 的 `current_cost,factory_price`。

- [ ] **Step 3: 运行后端测试确认失败**

Run: `mvn -f backend/pom.xml -pl app -Dtest=ProcurementWorkflowApiTest test`

Expected: 新增用例 FAIL。

- [ ] **Step 4: 修改请求和后端保存逻辑**

请求改为：

```java
public record ManualPurchaseRequest(
    long supplierId, long skuId, long supplierPurchaseInfoId,
    int quantity, LocalDate expectedArrivalDate, String remark) {}
```

服务通过采购信息 ID 联查供应关系，验证供应商、产品、启用状态和 MOQ；使用数据库 `purchase_price` 创建采购单，并写入 `purchase_order_item.supplier_purchase_info_id`。

- [ ] **Step 5: 编写前端三级选择失败测试**

选择产品后选择供应商，断言采购信息下拉默认第一条最新记录；切换旧记录后 MOQ、交货天数和预计到货日期变化；页面不存在“成本单价”“转厂价格”“价格来源”；提交包含 `supplierPurchaseInfoId`，不包含 `priceSource`。

- [ ] **Step 6: 运行前端测试确认失败**

Run: `npm --prefix frontend test -- --run src/components/ManualPurchaseDialog.test.ts`

Expected: FAIL，当前仍展示两个 SKU 价格来源。

- [ ] **Step 7: 实现手工采购三级选择**

供应商选中后设置 `selectedPurchaseInfo = supplier.purchaseInfos[0]`。采购信息下拉显示：

```text
¥237.73｜起订 500｜交货 45 天｜2026-08-14 10:30
```

切换记录后，以该记录的 `leadTimeDays` 从当天重新计算预计到货日期；用户后续仍可手工改日期。

- [ ] **Step 8: 更新前端请求类型与提交数据**

`ManualPurchaseData` 删除 `purchasePrice` 和 `priceSource`，增加 `supplierPurchaseInfoId: number`。

- [ ] **Step 9: 运行前后端定向测试**

Run: `mvn -f backend/pom.xml -pl app -Dtest=ProcurementWorkflowApiTest test`

Run: `npm --prefix frontend test -- --run src/components/ManualPurchaseDialog.test.ts`

Expected: PASS。

- [ ] **Step 10: 提交手工采购改动**

```bash
git add backend/app/src/main/java/com/internalops/workbench/ManualPurchaseRequest.java backend/app/src/main/java/com/internalops/workbench/ProcurementWorkflowService.java backend/app/src/test/java/com/internalops/workbench/ProcurementWorkflowApiTest.java frontend/src/api/workbench.ts frontend/src/components/ManualPurchaseDialog.vue frontend/src/components/ManualPurchaseDialog.test.ts
git commit -m "采购下单改用供应商采购信息"
```

### Task 6: 自动采购建议使用最新有效采购信息

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/ProcurementWorkflowService.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/ProcurementWorkflowApiTest.java`

**Interfaces:**
- Consumes: 最新规则 `updated_at DESC, id DESC`。

- [ ] **Step 1: 编写自动建议失败测试**

同一供应关系插入新旧两条记录，断言建议使用新记录的价格、MOQ 和交货天数；同一产品多家供应商时，断言选择全局修改时间最新的有效采购信息且缺口只覆盖一次；最新记录停用时回退到下一条有效记录。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -f backend/pom.xml -pl app -Dtest=ProcurementWorkflowApiTest test`

Expected: FAIL，当前直接读取供应关系表旧价格列并可能重复缺口。

- [ ] **Step 3: 实现确定性最新记录查询**

使用窗口函数或 `NOT EXISTS` 选择每个 SKU 的一条候选：先按采购信息 `updated_at DESC, id DESC`，同时只连接启用的供应关系和采购信息。确保一个销售缺口行只产生一个候选供应商。

- [ ] **Step 4: 保存建议来源快照**

建议明细继续保存建议价格和日期，并将所选记录写入 V40 已增加的 `procurement_suggestion_item.supplier_purchase_info_id`；确认建议生成采购单时，将该 ID 传递到采购单明细。不得在确认阶段重新选择“当前最新”记录，以保证建议生成与采购单确认使用同一价格来源。

- [ ] **Step 5: 运行采购工作流测试**

Run: `mvn -f backend/pom.xml -pl app -Dtest=ProcurementWorkflowApiTest test`

Expected: PASS。

- [ ] **Step 6: 提交自动建议改动**

```bash
git add backend/app/src/main/resources/db/migration/V40__supplier_purchase_info_history.sql backend/app/src/main/java/com/internalops/workbench/ProcurementWorkflowService.java backend/app/src/test/java/com/internalops/workbench/ProcurementWorkflowApiTest.java backend/app/src/test/java/com/internalops/workbench/WorkbenchSchemaTest.java
git commit -m "自动采购使用最新供应商报价"
```

### Task 7: 全量验证、本地 Docker 启动与浏览器验收

**Files:**
- Verify only unless an in-scope regression is found.

**Interfaces:**
- Verifies: 数据迁移、API、前端交互和本地运行环境。

- [ ] **Step 1: 运行后端全量测试**

Run: `mvn -f backend/pom.xml test`

Expected: 全部 PASS，测试数不少于当前基线 197。

- [ ] **Step 2: 运行前端全量测试**

Run: `npm --prefix frontend test -- --run`

Expected: 全部 PASS，测试数不少于当前基线 158。

- [ ] **Step 3: 运行前端生产构建**

Run: `npm --prefix frontend run build`

Expected: 构建成功，无 TypeScript 错误。

- [ ] **Step 4: 检查变更范围**

Run: `git diff --check`

Run: `git status --short`

Expected: 无空白错误；`data/` 保持未跟踪且未提交。

- [ ] **Step 5: 重建并启动本地 Docker**

Run: `docker compose build`

Run: `docker compose up -d`

Run: `docker compose ps`

Expected: MySQL 健康，后端和前端运行；后端日志显示 40 个迁移且 schema 版本为 40，现有数据库卷保持不变。

- [ ] **Step 6: 浏览器验收**

访问 `http://localhost/` 并检查：产品全名悬浮；同一产品新增、修改、删除多条采购信息；修改时间显示；手工采购只显示供应商采购信息；默认最新；切换后 MOQ 与预计到货联动；页面无成本单价、转厂价格和价格来源；保存后的采购单价格保持快照。

- [ ] **Step 7: 提交验收修正**

仅当验收产生必要修正时：

```bash
git add <本功能实际修正文件>
git commit -m "完善多版采购信息验收细节"
```
