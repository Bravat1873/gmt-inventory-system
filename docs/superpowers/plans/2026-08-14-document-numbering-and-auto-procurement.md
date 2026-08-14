# 单据编号与自动采购确认 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 DD、SH、QR、CG 提供并发安全的月度流水号，并在供需余量为负时自动维护最低预计总价的待确认采购建议，由采购人员确认后生成正式采购单。

**Architecture:** 新建独立 `DocumentNumberService` 管理数据库月序列，所有业务服务只请求业务前缀，不自行拼接 UUID。新建 `AutoProcurementSuggestionService` 接收受影响 SKU 集合，基于供需快照和供应商采购信息计算建议、按供应商聚合 QR，并通过采购管理现有建议/确认流程生成 CG。

**Tech Stack:** Java 17、Spring Boot 3、JdbcTemplate、MySQL 8.4、Flyway、JUnit 5、Vue 3、TypeScript、Vitest。

## Global Constraints

- 新单号格式固定为 `前缀 + yyyyMM + 五位序号`；前缀为 `DD`、`SH`、`QR`、`CG`。
- 四类序列独立，按 `Asia/Shanghai` 月份重置，范围 `00001` 至 `99999`。
- 历史单号不回填、不修改。
- 建议采购数量必须为 `max（采购缺口，供应商最小起购量）`。
- 推荐供应商按预计总价、采购单价、最小起购量、供应商编号依次升序选择。
- 同供应商的产品合并为一张有效 QR，不同供应商拆分。
- 自动流程只能生成 QR，采购人员确认后才生成 CG 并计入在途。
- 已人工修改的 QR 不得被自动重算静默覆盖。
- 当前工作区已有供应商多版采购信息的未提交修改；实施前必须先确认和保留这些改动，不得 restore、覆盖或混入无关提交。
- 根目录 `data/` 不提交。

---

### Task 1: 建立并发安全的月度单号服务

**Files:**
- Create: `backend/app/src/main/resources/db/migration/V41__document_number_sequences.sql`
- Create: `backend/app/src/main/java/com/internalops/numbering/DocumentNumberService.java`
- Create: `backend/app/src/test/java/com/internalops/numbering/DocumentNumberServiceTest.java`
- Create: `backend/app/src/test/resources/document-number-schema.sql`

**Interfaces:**
- Produces: `String DocumentNumberService.next(DocumentType type, LocalDate businessDate)`。
- Produces: `DocumentType` values `SALES_ORDER("DD")`、`AFTER_SALES("SH")`、`PROCUREMENT_REVIEW("QR")`、`PURCHASE_ORDER("CG")`。

- [ ] **Step 1: Write the failing numbering tests**

测试同类型同月返回 `DD20260800001`、`DD20260800002`，不同类型各自从 `00001` 开始，九月至少返回 `DD20260900001`；并发 20 次调用必须得到 20 个唯一且连续的编号；预置 `99999` 后下一次调用抛出“本月单号已达到 99999 上限”。

- [ ] **Step 2: Run tests to verify RED**

Run: `mvn -f backend/pom.xml -pl app -am "-Dtest=DocumentNumberServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: FAIL，服务和序列表尚不存在。

- [ ] **Step 3: Add the sequence table**

迁移创建：

```sql
CREATE TABLE document_number_sequence (
  document_type VARCHAR(32) NOT NULL,
  year_month CHAR(6) NOT NULL,
  current_value INT NOT NULL,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (document_type, year_month),
  CONSTRAINT ck_document_number_value CHECK (current_value BETWEEN 0 AND 99999)
);
```

- [ ] **Step 4: Implement atomic allocation**

`next()` 使用 `INSERT ... VALUES(..., LAST_INSERT_ID(1)) ON DUPLICATE KEY UPDATE current_value=LAST_INSERT_ID(current_value+1)`，随后读取连接级 `SELECT LAST_INSERT_ID()`；超过上限时回滚。格式化使用 `DateTimeFormatter.ofPattern("yyyyMM")` 和 `%05d`，业务日期由调用方按上海时区传入。

- [ ] **Step 5: Run tests to verify GREEN**

Run: `mvn -f backend/pom.xml -pl app -am "-Dtest=DocumentNumberServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS。

### Task 2: 替换 DD、SH 和人工 CG 单号生成

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/SalesOrderCommandService.java`
- Modify: `backend/app/src/main/java/com/internalops/aftersales/AfterSalesCommandService.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/ProcurementWorkflowService.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/SalesOrderCommandApiTest.java`
- Modify: `backend/app/src/test/java/com/internalops/aftersales/AfterSalesCommandApiTest.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/ProcurementWorkflowApiTest.java`

**Interfaces:**
- Consumes: `DocumentNumberService.next(...)`。
- Produces: 新销售订单 `DDyyyyMMnnnnn`，新售后单 `SHyyyyMMnnnnn`，手工采购确认生成 `CGyyyyMMnnnnn`。

- [ ] **Step 1: Write failing API assertions**

固定测试业务日期为 2026-08，分别创建两条订单、售后和采购单，断言编号精确匹配 `DD20260800001/2`、`SH20260800001/2`、`CG20260800001/2`；预置旧格式记录并断言它保持原值。

- [ ] **Step 2: Run tests to verify RED**

Run: `mvn -f backend/pom.xml -pl app -am "-Dtest=SalesOrderCommandApiTest,AfterSalesCommandApiTest,ProcurementWorkflowApiTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: FAIL，当前仍生成 SO、AS、PO 加 UUID。

- [ ] **Step 3: Inject and use DocumentNumberService**

三个服务删除对应 UUID 单号拼接，只在创建事务内部调用编号服务。采购服务的所有正式采购单入口必须统一使用 `PURCHASE_ORDER`，避免手工创建和 QR 确认走不同规则。

- [ ] **Step 4: Run tests to verify GREEN**

Run: `mvn -f backend/pom.xml -pl app -am "-Dtest=SalesOrderCommandApiTest,AfterSalesCommandApiTest,ProcurementWorkflowApiTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS。

### Task 3: 扩展 QR 建议数据模型和审计快照

**Files:**
- Create: `backend/app/src/main/resources/db/migration/V42__auto_procurement_reviews.sql`
- Modify: `backend/app/src/test/resources/procurement-workflow-schema.sql`
- Modify: `backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/WorkbenchQueryApiTest.java`

**Interfaces:**
- Produces QR header fields: `suggestion_no`、`supplier_id nullable`、`status`、`system_managed`、`manually_edited`、`review_reason`、`version`。
- Produces QR line snapshots: `shortage_quantity`、`minimum_order_quantity`、`suggested_quantity`、`purchase_price`、`estimated_amount`、`source_balance_version`。

- [ ] **Step 1: Write failing query tests**

插入一张待确认 QR 和两条明细，查询采购管理后断言返回 QR 单号、供应商、产品种数、总数量、预计总金额、状态和异常提醒。

- [ ] **Step 2: Run tests to verify RED**

Run: `mvn -f backend/pom.xml -pl app -am "-Dtest=WorkbenchQueryApiTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: FAIL，新快照字段尚不存在。

- [ ] **Step 3: Add migration and constraints**

在现有 `procurement_suggestion` / `procurement_suggestion_item` 上增量加字段，不删除当前供应商多版采购信息字段；为 `suggestion_no` 建唯一索引，为“有效待确认产品”增加可检测重复的索引。兼容已有 DRAFT 建议，将 `system_managed=FALSE`。

- [ ] **Step 4: Extend purchase list projection**

采购列表对 QR 返回 `recordType=REVIEW`，计算 `productCount`、`suggestedQuantity` 和 `estimatedAmount`；正式采购维持 `recordType=PURCHASE`。关键词可搜索 QR、CG、供应商和产品。

- [ ] **Step 5: Run tests to verify GREEN**

Run: `mvn -f backend/pom.xml -pl app -am "-Dtest=WorkbenchQueryApiTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS。

### Task 4: 实现最低总价采购推荐算法

**Files:**
- Create: `backend/app/src/main/java/com/internalops/procurement/ProcurementRecommendationService.java`
- Create: `backend/app/src/test/java/com/internalops/procurement/ProcurementRecommendationServiceTest.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/SupplyDemandQueryService.java`

**Interfaces:**
- Consumes: `SupplyDemandSnapshot` 与当前有效的供应商采购信息版本。
- Produces: `Recommendation(skuId, supplierId, shortageQuantity, minimumOrderQuantity, suggestedQuantity, purchasePrice, estimatedAmount, reason)`。

- [ ] **Step 1: Write failing algorithm tests**

覆盖缺口 6 时供应商甲 `10×max(6,20)=200`、乙 `15×max(6,6)=90`，必须选择乙；覆盖同总价按单价、MOQ、供应商 ID 决胜；覆盖无有效采购信息返回异常 recommendation；覆盖供需余量非负不返回建议。

- [ ] **Step 2: Run tests to verify RED**

Run: `mvn -f backend/pom.xml -pl app -am "-Dtest=ProcurementRecommendationServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: FAIL，推荐服务不存在。

- [ ] **Step 3: Implement pure recommendation logic**

金额使用 `BigDecimal`，数量使用整数；过滤禁用供应商、无当前采购信息、单价小于零或 MOQ 小于一的记录。比较器严格按 `estimatedAmount`、`purchasePrice`、`minimumOrderQuantity`、`supplierId`。

- [ ] **Step 4: Run tests to verify GREEN**

Run: `mvn -f backend/pom.xml -pl app -am "-Dtest=ProcurementRecommendationServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS。

### Task 5: 实现实时 QR 创建、更新、合并与关闭

**Files:**
- Create: `backend/app/src/main/java/com/internalops/procurement/AutoProcurementSuggestionService.java`
- Create: `backend/app/src/test/java/com/internalops/procurement/AutoProcurementSuggestionServiceTest.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/SalesOrderCommandService.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/InventoryAllocationService.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/ProcurementWorkflowService.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/ShipmentQuantityService.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ImportCommitService.java`

**Interfaces:**
- Produces: `void recalculate(Collection<Long> skuIds, Trigger trigger)`。
- Consumes: Task 4 recommendations and Task 1 `PROCUREMENT_REVIEW` numbers。

- [ ] **Step 1: Write failing lifecycle tests**

覆盖首次负数创建 QR；相同 SKU 再次重算更新原行且不重复；同供应商多 SKU 合并；推荐供应商变化时移动未人工修改行；人工修改行只标记需复核；缺口消失标记行和空 QR 为无需采购；已确认/驳回 QR 不修改。

- [ ] **Step 2: Run tests to verify RED**

Run: `mvn -f backend/pom.xml -pl app -am "-Dtest=AutoProcurementSuggestionServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: FAIL，自动建议服务不存在。

- [ ] **Step 3: Implement idempotent recalculation**

事务内按 SKU ID 排序锁定有效 QR 行，先计算全部 recommendation，再按 supplierId 分组。新分组分配 QR 单号；已有系统管理分组更新快照；无供应商异常统一进入 supplier_id 为空的 QR。任何明细写入前再次检查有效 QR 中是否已有该 SKU。

- [ ] **Step 4: Wire domain triggers after successful writes**

各业务服务只把本次受影响 SKU 传给 `recalculate`。触发点至少包括订单创建/修改、库存导入或调整、库存分配变化、发货、采购确认和采购入库。自动重算失败必须记录可重试事件，不能回滚已经成功的库存或订单业务操作。

- [ ] **Step 5: Run tests to verify GREEN**

Run: `mvn -f backend/pom.xml -pl app -am "-Dtest=AutoProcurementSuggestionServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS。

### Task 6: 实现采购人员复核、修改、驳回和确认

**Files:**
- Create: `backend/app/src/main/java/com/internalops/procurement/ProcurementReviewController.java`
- Create: `backend/app/src/main/java/com/internalops/procurement/ProcurementReviewService.java`
- Create: `backend/app/src/test/java/com/internalops/procurement/ProcurementReviewApiTest.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/ProcurementWorkflowService.java`

**Interfaces:**
- GET `/api/procurement/reviews/{id}` returns header, candidates, snapshots and current versions.
- PUT `/api/procurement/reviews/{id}` accepts supplier, quantities, arrival date, remark and `version`.
- POST `/api/procurement/reviews/{id}/reject` accepts reason and `version`.
- POST `/api/procurement/reviews/{id}/confirm` accepts `version` and produces CG detail.

- [ ] **Step 1: Write failing API tests**

覆盖读取候选供应商；修改数量小于 MOQ 返回 400；修改后金额重算；驳回要求原因；确认重新校验供需和采购信息；数据变化返回 409 并标记需复核；成功确认只生成一张 CG、复制快照、标记 QR 已确认并增加在途；并发第二次确认返回 409。

- [ ] **Step 2: Run tests to verify RED**

Run: `mvn -f backend/pom.xml -pl app -am "-Dtest=ProcurementReviewApiTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: FAIL，复核 API 不存在。

- [ ] **Step 3: Implement review commands**

更新使用乐观锁 `WHERE id=? AND version=?`。确认事务顺序为：锁 QR → 重读供需 → 验证候选采购信息版本与 MOQ → 分配 CG → 写采购单和明细快照 → 增加在途 → 更新 QR 状态。任一步失败整笔回滚。

- [ ] **Step 4: Run tests to verify GREEN**

Run: `mvn -f backend/pom.xml -pl app -am "-Dtest=ProcurementReviewApiTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS。

### Task 7: 增加采购管理 QR 确认界面与库存跳转

**Files:**
- Modify: `frontend/src/api/workbench.ts`
- Modify: `frontend/src/components/ModuleListPage.vue`
- Modify: `frontend/src/components/ModuleListPage.test.ts`
- Create: `frontend/src/components/ProcurementReviewDialog.vue`
- Create: `frontend/src/components/ProcurementReviewDialog.test.ts`
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/App.test.ts`
- Modify: `frontend/src/modules/module-config.ts`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Produces purchase views `review | purchase | all` with default `review`.
- Consumes Task 6 review APIs.
- Produces inventory QR link carrying `module=purchase&view=review&reviewId=<id>`。

- [ ] **Step 1: Write failing UI tests**

测试采购页默认待确认视图和数量提醒；QR 行显示编号、供应商、产品种数、总数量、总金额和异常；点击确认打开明细；切换候选供应商更新 MOQ、数量和金额；低于 MOQ 阻止提交；驳回需原因；409 展示“供需已变化，请刷新”；库存负数行可跳转对应 QR。

- [ ] **Step 2: Run tests to verify RED**

Run: `cd frontend && npm test -- --run src/components/ModuleListPage.test.ts src/components/ProcurementReviewDialog.test.ts src/App.test.ts`

Expected: FAIL，QR 复核界面尚不存在。

- [ ] **Step 3: Implement purchase views and dialog**

复用现有列表固定行高；异常状态使用红色提示但不拉高行。弹窗逐行显示推荐依据，数量和预计到货日可编辑；供应商候选使用现有 `FuzzyPicker` 模式。保存、驳回和确认成功后关闭并刷新采购列表。

- [ ] **Step 4: Implement inventory link**

库存供需余量小于零时，除采购缺口外显示 QR 编号；点击只导航到采购管理待确认视图，不在库存页提供确认按钮。

- [ ] **Step 5: Run tests to verify GREEN**

Run: `cd frontend && npm test -- --run src/components/ModuleListPage.test.ts src/components/ProcurementReviewDialog.test.ts src/App.test.ts`

Expected: PASS。

### Task 8: 完整验证、提交和部署

**Files:**
- Verify all files changed by Tasks 1-7.
- Preserve all pre-existing dirty files and `data/` outside the agreed feature commits.

- [ ] **Step 1: Run full backend tests**

Run: `mvn -f backend/pom.xml test`

Expected: BUILD SUCCESS，0 failures。

- [ ] **Step 2: Run full frontend tests and build**

Run: `cd frontend && npm test -- --run`

Expected: all tests PASS。

Run: `cd frontend && npm run build`

Expected: exit 0。

- [ ] **Step 3: Verify migrations and numbering on MySQL**

使用 Docker MySQL 执行 V41、V42，验证 Flyway 校验、编号唯一约束和确认事务；创建测试 DD/SH/QR/CG 后回滚或使用专用测试库，不污染正式业务数据。

- [ ] **Step 4: Review and commit only intended files**

Run: `git diff --check` and `git status --short`。逐文件暂存；不得使用 `git add .`，不得暂存 `data/` 或进入本计划前已有但未归属本功能的改动。提交信息使用中文。

- [ ] **Step 5: Push and deploy server**

推送 `main` 后，服务器 `/opt/stacks/gmt-inventory-system` 快进拉取并利用 Docker 缓存重建前后端。不得删除或重建 MySQL 数据卷。

- [ ] **Step 6: Server acceptance**

确认服务器 HEAD 与本地一致、MySQL healthy、Flyway 到 V42、前后端容器正常、HTTP 200；现场创建一条测试缺口时确认生成 QR，采购人员确认后生成 CG 且在途和供需余量正确变化。
