# 管理页面排序与弹窗关闭 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为所有管理列表的适合字段增加稳定的业务排序，并统一修复弹窗关闭、取消和 Esc 行为。

**Architecture:** 前端 `module-config.ts` 声明可排序表头，后端 `WorkbenchQueryService` 通过白名单把公开排序键映射为 SQL 表达式；状态类字段使用 `CASE` 表达式实现业务流程顺序。弹窗关闭继续由全局关闭守卫统一协调，但移除“主按钮禁用等于保存中”的错误推断，并以显式状态和标准关闭入口实现一致行为。

**Tech Stack:** Vue 3、TypeScript、Vitest、Spring Boot 3、JdbcTemplate、JUnit 5、Docker Compose。

## Global Constraints

- 图片、备注、地址、产品配置、物料规格、供应商报价、产品汇总等长文本或组合列不排序。
- 状态字段按业务流程顺序排序，未知状态排在已知状态之后。
- 相同主排序值必须追加稳定 ID 次级排序。
- 点击遮罩不关闭弹窗。
- 右上关闭、底部取消和 Esc 均可关闭；只有实际保存期间禁止关闭。
- 未修改直接关闭，已修改需确认放弃。
- 不提交仓库根目录未跟踪的 `data/`。

---

### Task 1: 补齐前端列表可排序字段

**Files:**
- Modify: `frontend/src/modules/module-config.ts`
- Test: `frontend/src/modules/module-config.test.ts`
- Test: `frontend/src/components/ModuleListPage.test.ts`

**Interfaces:**
- Consumes: `ModuleDefinition.sortable: string[]` 与 `ModuleListPage.orderBy(field: string)`。
- Produces: 每个短字段对应一个后端公开排序键，排除字段保持空字符串。

- [ ] **Step 1: 写失败配置测试**

在 `module-config.test.ts` 为九个模块分别断言设计文档中的可排序字段具有非空排序键，并断言 `productImage`、`remark`、`address`、`configuration`、`productConfiguration`、`supplierQuotes`、`productSummary` 等排除字段为空。

- [ ] **Step 2: 运行测试确认 RED**

Run: `cd frontend && npm test -- --run src/modules/module-config.test.ts`

Expected: FAIL，指出当前多个短字段的 `sortable` 仍为空。

- [ ] **Step 3: 补齐前端排序配置**

逐项更新 `moduleDefinitions` 的 `sortable` 数组，保证 `columns`、`fields`、`sortable` 三者长度和索引完全一致；订单日期使用 `orderDate`，不要错误复用 `createdAt`。

- [ ] **Step 4: 验证点击表头传递排序参数**

在 `ModuleListPage.test.ts` 选择一个新增排序字段表头，点击后断言 `loadModule` 请求参数包含对应 `sort` 与 `direction=asc`，再次点击为 `desc`。

- [ ] **Step 5: 运行前端定向测试确认 GREEN**

Run: `cd frontend && npm test -- --run src/modules/module-config.test.ts src/components/ModuleListPage.test.ts`

Expected: PASS。

### Task 2: 扩展后端排序白名单和稳定排序

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java`
- Test: `backend/app/src/test/java/com/internalops/workbench/WorkbenchQueryApiTest.java`
- Test: `backend/app/src/test/java/com/internalops/workbench/InventoryWorkbenchQueryApiTest.java`
- Test: `backend/app/src/test/java/com/internalops/aftersales/AfterSalesOrderOptionsQueryTest.java`

**Interfaces:**
- Consumes: GET `/api/workbench/{module}?sort=<key>&direction=<asc|desc>`。
- Produces: 设计文档列出的公开排序键映射到安全 SQL 表达式。

- [ ] **Step 1: 写失败 API 排序测试**

为新增文本、数值和日期排序键插入乱序数据，分别请求升序和降序，断言返回顺序；至少覆盖客户合同截止日期、产品品牌与分类、供应商产品数、订单日期与销售人员、售后数量、库存数量/库龄/日期、采购预计到货日和财务业务类型。

- [ ] **Step 2: 运行后端定向测试确认 RED**

Run: `mvn -f backend/pom.xml -pl app -am -Dtest=WorkbenchQueryApiTest,InventoryWorkbenchQueryApiTest,AfterSalesOrderOptionsQueryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，新增键当前回退到默认修改时间排序。

- [ ] **Step 3: 补齐每个 ModuleSpec 的 sorts 映射**

在 `registerModules()` 中为前端公开键添加对应的真实列或计算表达式。组合视图只引用其对外别名；相关子查询数量直接复用 SELECT 中等价表达式。不得把客户端 sort 内容直接拼入 SQL。

- [ ] **Step 4: 保证稳定次级排序**

检查列表 SQL 的 `ORDER BY` 生成逻辑：主排序表达式与方向之后必须追加当前模块的稳定 ID tie-breaker；主排序本身为 ID 时不得生成破坏语义的重复项。

- [ ] **Step 5: 运行后端定向测试确认 GREEN**

Run: `mvn -f backend/pom.xml -pl app -am -Dtest=WorkbenchQueryApiTest,InventoryWorkbenchQueryApiTest,AfterSalesOrderOptionsQueryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

### Task 3: 实现状态字段业务流程排序

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java`
- Test: `backend/app/src/test/java/com/internalops/workbench/WorkbenchQueryApiTest.java`
- Test: `backend/app/src/test/java/com/internalops/workbench/InventoryWorkbenchQueryApiTest.java`

**Interfaces:**
- Consumes: 各模块现有状态编码。
- Produces: `status`、`paymentStatus`、`receiptStatus`、`contractStatus`、`role` 和分类枚举的业务序位 SQL `CASE` 表达式。

- [ ] **Step 1: 写业务流程顺序失败测试**

以乱序插入订单、售后、采购、付款、收货、财务、合同和用户角色记录，请求 `direction=asc`，断言设计文档中的业务顺序；另测 `desc` 反向和未知状态在升序时位于已知状态之后。

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -f backend/pom.xml -pl app -am -Dtest=WorkbenchQueryApiTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，当前状态按编码文字或默认时间排序。

- [ ] **Step 3: 添加显式 CASE 排序表达式**

为各状态键定义独立、可读的 `CASE` 表达式，例如订单 `DRAFT=10`、`PENDING_CUSTOMER_PAYMENT=20`、`WAITING_STOCK=30`、`READY_TO_SHIP=40`、`SHIPPED=50`、`ELSE=999`；其他模块依设计文档建立相同层级策略。

- [ ] **Step 4: 修正未知状态在降序时的位置**

ORDER BY 生成器应把“是否未知”作为固定的首级排序，再按业务序位应用用户方向，使未知状态无论升降序都位于已知状态之后；随后追加稳定 ID。

- [ ] **Step 5: 运行状态排序测试确认 GREEN**

Run: `mvn -f backend/pom.xml -pl app -am -Dtest=WorkbenchQueryApiTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

### Task 4: 修复全局关闭拦截器根因

**Files:**
- Modify: `frontend/src/composables/useUnsavedChangesGuard.ts`
- Test: `frontend/src/composables/useUnsavedChangesGuard.test.ts`
- Test: `frontend/src/components/ReceiptDialog.test.ts`

**Interfaces:**
- Consumes: `.dialog-card`、关闭/取消按钮和表单输入事件。
- Produces: 业务主按钮禁用不再影响关闭；用户修改后仍需确认。

- [ ] **Step 1: 写截图问题的失败回归测试**

挂载 `ReceiptDialog`，令未收金额为零使“确认登记”禁用，点击右上“关闭”与底部“取消操作”，分别断言仍发出 `close`。在关闭守卫测试中构造含禁用 `.primary-action` 的弹窗，断言关闭点击不被阻止。

- [ ] **Step 2: 运行测试确认 RED**

Run: `cd frontend && npm test -- --run src/composables/useUnsavedChangesGuard.test.ts src/components/ReceiptDialog.test.ts`

Expected: FAIL，全局守卫因禁用主按钮调用 `preventDefault()` 和 `stopImmediatePropagation()`。

- [ ] **Step 3: 删除错误推断**

从 `useGlobalDialogCloseGuard()` 删除 `dialog.querySelector('.primary-action:disabled')` 分支。不得用按钮是否禁用推断保存状态；组件自己的 `saving` 已负责禁用关闭按钮和 `close()` 防护。

- [ ] **Step 4: 运行回归测试确认 GREEN**

Run: `cd frontend && npm test -- --run src/composables/useUnsavedChangesGuard.test.ts src/components/ReceiptDialog.test.ts`

Expected: PASS。

### Task 5: 统一 Esc 和修改确认行为

**Files:**
- Modify: `frontend/src/composables/useUnsavedChangesGuard.ts`
- Modify: `frontend/src/App.vue`
- Modify: 必要的 `frontend/src/components/*Dialog.vue`
- Test: `frontend/src/App.test.ts`
- Test: `frontend/src/composables/useUnsavedChangesGuard.test.ts`
- Test: 受影响的 `frontend/src/components/*Dialog.test.ts`

**Interfaces:**
- Consumes: 文档级 `keydown`、当前最上层 `.dialog-card`、各组件 `close` emit 和 `saving`。
- Produces: Esc 与按钮关闭走同一守卫；遮罩无 click-close 处理。

- [ ] **Step 1: 写统一关闭行为失败测试**

测试 clean 弹窗按 Esc 关闭、dirty 弹窗按 Esc 弹确认、拒绝确认后保留、确认后关闭；测试点击 `.dialog-mask` 不关闭；测试保存中组件的关闭按钮与 Esc 均不关闭。对只读图片弹窗验证 Esc 直接关闭。

- [ ] **Step 2: 运行测试确认 RED**

Run: `cd frontend && npm test -- --run src/App.test.ts src/composables/useUnsavedChangesGuard.test.ts src/components/ProductGalleryDialog.test.ts`

Expected: FAIL，当前没有统一 Esc 关闭入口。

- [ ] **Step 3: 在统一守卫实现最上层弹窗 Esc 请求**

为弹窗关闭按钮增加稳定的 `data-dialog-close` 标记；捕获 Escape 后定位 DOM 中最后一个可见 `.dialog-card`，触发其标准关闭按钮，使 dirty 确认与组件保存中防护仍走同一链路。不得给 `.dialog-mask` 添加关闭点击处理。

- [ ] **Step 4: 统一组件关闭入口**

为所有业务弹窗的右上关闭和底部取消补齐 `type="button"`、`data-dialog-close` 及相同 close handler；只读弹窗只需直接 emit。保存按钮不得携带关闭标记。

- [ ] **Step 5: 运行统一关闭测试确认 GREEN**

Run: `cd frontend && npm test -- --run src/App.test.ts src/composables/useUnsavedChangesGuard.test.ts src/components/ProductGalleryDialog.test.ts src/components/ReceiptDialog.test.ts`

Expected: PASS。

### Task 6: 完整验证、本地部署与服务器发布

**Files:**
- Verify: all changed frontend/backend files
- Preserve: `data/`

**Interfaces:**
- Produces: 可在本地与 `192.168.60.169:1234` 使用的新版本。

- [ ] **Step 1: 运行完整后端测试**

Run: `mvn -f backend/pom.xml test`

Expected: BUILD SUCCESS，0 failures。

- [ ] **Step 2: 运行完整前端测试和构建**

Run: `cd frontend && npm test -- --run`

Expected: 所有测试 PASS。

Run: `cd frontend && npm run build`

Expected: exit 0。

- [ ] **Step 3: 本地容器验证**

Run: `docker compose --progress plain build backend frontend`

Run: `docker compose up -d --force-recreate backend frontend`

检查 `docker compose ps`、后端启动日志与 `http://localhost`；浏览器验证至少订单状态排序、库存数量排序、收款弹窗关闭和 Esc，且点击遮罩不关闭。

- [ ] **Step 4: 检查改动并提交**

Run: `git diff --check && git status --short`

仅暂存代码、测试和计划文件，不暂存 `data/`。提交信息使用中文：`统一管理页面排序与弹窗关闭行为`。

- [ ] **Step 5: 推送并部署服务器**

推送 `main` 到 GitHub；服务器 `/opt/stacks/gmt-inventory-system` 执行 `git pull --ff-only origin main`，利用 Docker 缓存重建前后端并替换容器。不得重建或删除 MySQL 数据卷。

- [ ] **Step 6: 服务器验收**

确认服务器 HEAD 等于本地提交，三个容器运行且 MySQL healthy，后端 Flyway 校验成功，`http://192.168.60.169:1234/` 返回 HTTP 200。
