# 自动采购部分成功与缺配置提醒 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让有有效供应商采购信息的缺货 SKU 正常生成 QR，同时在采购管理明确展示无法生成 QR 的待补配置 SKU。

**Architecture:** 后端把缺货 SKU 独立分类，生成服务返回部分成功结果；新增实时只读查询接口聚合待补配置物料，不持久化提醒。前端采购页面独立加载提醒，用固定高度可展开面板展示，并提供进入供应商管理的导航。

**Tech Stack:** Java 17、Spring Boot、JdbcTemplate、JUnit 5、MockMvc、Vue 3、TypeScript、Vitest、Docker Compose。

## Global Constraints

- 不新增提醒数据库表，不迁移或清空现有业务数据。
- 有配置 SKU 必须继续采用 `采购单价 × max(采购缺口, 最小起购量)` 最低总额供应商。
- 无配置 SKU 不得阻塞其他 SKU，也不得生成不完整 QR。
- 同一 SKU 的异常按实时缺口聚合，订单号去重。
- 采购列表数据行高度保持不变。

---

### Task 1: 采购生成部分成功

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/ProcurementWorkflowService.java`
- Test: `backend/app/src/test/java/com/internalops/workbench/ProcurementWorkflowApiTest.java`

**Interfaces:**
- Consumes: `ProcurementRecommendationService.recommend(long, int, List<Candidate>)`
- Produces: `generate()` 返回 `suggestionIds`、`count`、`unconfiguredCount`、`unconfiguredItems`

- [ ] **Step 1: 写混合物料失败测试**

在测试夹具中增加一个无有效采购信息的缺货 SKU，同时保留 SKU 101 的有效采购信息，调用 `/api/procurement/generate`，断言 SKU 101 生成 QR、请求为 200、`unconfiguredCount=1`，异常项包含无配置 SKU 和缺口。

```java
assertThat(json.path("data.count").asInt()).isEqualTo(1);
assertThat(json.path("data.unconfiguredCount").asInt()).isEqualTo(1);
assertThat(json.path("data.unconfiguredItems").get(0).path("shortageQuantity").asInt()).isEqualTo(4);
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend/app && mvn -Dtest=ProcurementWorkflowApiTest#generatesConfiguredSkuAndReportsUnconfiguredSku test`

Expected: FAIL，当前服务抛出“存在未配置有效供应商采购信息的缺货产品”，请求不是部分成功。

- [ ] **Step 3: 实现最小分类逻辑**

把每个 SKU 的候选查询结果独立处理；候选为空时构造 `LinkedHashMap` 异常项并 `continue`，候选存在时保持原推荐和分组逻辑。返回结果使用 `LinkedHashMap`，同时包含 QR 和异常数据。

```java
if (candidates.isEmpty()) {
    unconfiguredItems.add(unconfiguredItem(skuEntry.getKey(), shortage, skuEntry.getValue()));
    continue;
}
```

- [ ] **Step 4: 增加全无配置和重复触发测试**

断言全无配置返回 200、`count=0`；连续调用不会产生 QR，异常项仍按 SKU 唯一返回。

- [ ] **Step 5: 运行采购工作流测试**

Run: `cd backend/app && mvn -Dtest=ProcurementWorkflowApiTest test`

Expected: 全部 PASS。

- [ ] **Step 6: 提交**

```bash
git add backend/app/src/main/java/com/internalops/workbench/ProcurementWorkflowService.java backend/app/src/test/java/com/internalops/workbench/ProcurementWorkflowApiTest.java
git commit -m "自动采购支持部分成功"
```

### Task 2: 待补供应商配置实时查询

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/ProcurementController.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/ProcurementWorkflowService.java`
- Test: `backend/app/src/test/java/com/internalops/workbench/ProcurementWorkflowApiTest.java`

**Interfaces:**
- Produces: `GET /api/procurement/unconfigured-shortages`
- Produces: `List<Map<String,Object>> unconfiguredShortages()`

- [ ] **Step 1: 写聚合查询失败测试**

创建两个 `WAITING_STOCK` 订单明细指向同一个无配置 SKU，调用查询接口，断言只返回一个 SKU、缺口求和、`orderNumbers` 去重。

```java
mvc.perform(get("/api/procurement/unconfigured-shortages").cookie(session))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.length()").value(1))
    .andExpect(jsonPath("$.data[0].shortageQuantity").value(7));
```

- [ ] **Step 2: 运行测试确认 404**

Run: `cd backend/app && mvn -Dtest=ProcurementWorkflowApiTest#listsUnconfiguredShortagesGroupedBySku test`

Expected: FAIL，接口不存在。

- [ ] **Step 3: 实现实时查询**

查询当前 `WAITING_STOCK` 且 `uncovered_quantity > 0` 的订单明细，并使用 `NOT EXISTS` 排除具有有效供应商、启用关系和启用采购信息的 SKU；Java 侧按 SKU 聚合缺口与 `LinkedHashSet<String>` 订单号。

- [ ] **Step 4: 增加补配置后消失测试**

插入启用的供应商关系和采购信息后再次查询，断言对应 SKU 不再返回；再次调用 `generate()` 应生成 QR。

- [ ] **Step 5: 运行测试并提交**

Run: `cd backend/app && mvn -Dtest=ProcurementWorkflowApiTest test`

```bash
git add backend/app/src/main/java/com/internalops/workbench/ProcurementController.java backend/app/src/main/java/com/internalops/workbench/ProcurementWorkflowService.java backend/app/src/test/java/com/internalops/workbench/ProcurementWorkflowApiTest.java
git commit -m "新增待补供应商配置查询"
```

### Task 3: 采购管理异常提醒

**Files:**
- Modify: `frontend/src/api/workbench.ts`
- Create: `frontend/src/components/ProcurementConfigurationAlert.vue`
- Create: `frontend/src/components/ProcurementConfigurationAlert.test.ts`
- Modify: `frontend/src/components/ModuleListPage.vue`
- Modify: `frontend/src/App.vue`
- Test: `frontend/src/components/ModuleListPage.test.ts`

**Interfaces:**
- Consumes: `loadUnconfiguredProcurementShortages(): Promise<UnconfiguredProcurementShortage[]>`
- Produces: `ProcurementConfigurationAlert` 事件 `navigateSupplier`

- [ ] **Step 1: 写提醒组件失败测试**

Mock 两个异常 SKU，断言摘要显示数量，展开后显示产品、缺口、订单号；点击按钮发出 `navigateSupplier`。

```ts
expect(wrapper.text()).toContain('2 个缺货产品')
await wrapper.get('[data-test="toggle-procurement-alert"]').trigger('click')
expect(wrapper.text()).toContain('DD20260800002')
```

- [ ] **Step 2: 运行测试确认组件不存在**

Run: `cd frontend && npm test -- --run src/components/ProcurementConfigurationAlert.test.ts`

Expected: FAIL，组件无法导入。

- [ ] **Step 3: 实现 API 类型与提醒组件**

类型包含 `skuId`、`skuCode`、`productName`、`shortageQuantity`、`orderNumbers`。组件独立加载、折叠；明细容器设置固定 `max-height` 和 `overflow:auto`，不影响表格行高。

- [ ] **Step 4: 接入采购页面与导航**

仅当 `module.key === 'purchase'` 时在工具栏下渲染提醒；通过 App 的 `selectModule('supplier')` 处理导航。列表重载时同时刷新提醒。

- [ ] **Step 5: 运行组件及列表测试**

Run: `cd frontend && npm test -- --run src/components/ProcurementConfigurationAlert.test.ts src/components/ModuleListPage.test.ts src/App.test.ts`

Expected: 全部 PASS。

- [ ] **Step 6: 提交**

```bash
git add frontend/src/api/workbench.ts frontend/src/components/ProcurementConfigurationAlert.vue frontend/src/components/ProcurementConfigurationAlert.test.ts frontend/src/components/ModuleListPage.vue frontend/src/App.vue frontend/src/components/ModuleListPage.test.ts
git commit -m "采购管理显示待补供应商配置"
```

### Task 4: 全量验证与本机更新

**Files:**
- Verify only; do not modify database volumes.

**Interfaces:**
- Consumes: Tasks 1-3 complete behavior.

- [ ] **Step 1: 后端全量测试**

Run: `cd backend/app && mvn test`

Expected: 0 failures, 0 errors。

- [ ] **Step 2: 前端全量测试和构建**

Run: `cd frontend && npm test`

Run: `cd frontend && npm run build`

Expected: tests PASS，Vite build success。

- [ ] **Step 3: Docker 原地重建**

Run: `docker compose build backend frontend`

Run: `docker compose up -d`

Expected: MySQL 数据卷不重建；前后端容器使用新镜像启动。

- [ ] **Step 4: 运行态核对**

检查 `http://localhost`，验证已有 P50 配置时能生成 QR；F70 等无配置物料显示在采购管理提醒中，不阻塞 P50。

- [ ] **Step 5: 最终提交检查**

Run: `git status --short`

Expected: 仅保留用户已有未跟踪 `data/`，没有构建生成文件或未提交代码。
