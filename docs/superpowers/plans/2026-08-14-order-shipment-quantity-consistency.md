# 订单发货数量统一与锁定释放 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 统一订单全景、库存分配、确认发货的数量字段，并让分批及整单发货同步扣减订单明细锁定数量与库存总锁定数量。

**Architecture:** 以 `sales_order_item` 作为订单数量、已发货数量、本单锁定数量和缺货数量的唯一事实来源，以 `inventory_balance` 作为仓库实际库存与总锁定数量的事实来源。发货服务在单一事务内同步更新两张表；前端三个组件仅做统一名称映射，不引入第二套计算口径。

**Tech Stack:** Java 17、Spring Boot、JdbcTemplate、JUnit 5、AssertJ、Vue 3、TypeScript、Vitest、Vue Test Utils、MySQL 8.4、Docker Compose。

## Global Constraints

- 统一名称必须使用：订单数量、已发货数量、未发货数量、本单锁定数量、缺货数量、实际库存数量、未锁定库存数量、本次发货数量、发货后未发货数量。
- 本次发货数量不得超过 `min(未发货数量, 本单锁定数量)`。
- 发货必须在同一事务内同步扣减订单明细锁定数量、库存总锁定数量和实际库存数量。
- 订单完成发货时必须释放该明细超过未发货数量的遗留锁定库存。
- 不修改或提交工作区中现有的 `data/` 和单号功能未跟踪文件。

---

### Task 1: 分批发货同步扣减并清理锁定库存

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/ShipmentQuantityService.java`
- Test: `backend/app/src/test/java/com/internalops/workbench/ShipmentQuantityServiceTest.java`

**Interfaces:**
- Consumes: `ShipmentQuantityService.update(long, ShipmentQuantityRequest)` 和现有库存流水 `InventoryAllocationService.tx(...)`。
- Produces: 发货后 `sales_order_item.locked_quantity`、`uncovered_quantity` 与 `inventory_balance.locked_quantity` 一致的事务行为。

- [ ] **Step 1: 写入失败回归测试**

新增使用 H2 测试库或完整 JdbcTemplate 交互断言的测试，构造订单数量 800、已发货 400、本单锁定 500、实际库存 450、库存总锁定 500，本次发货 400，并断言最终：

```java
assertThat(line.get("shipped_quantity")).isEqualTo(800);
assertThat(line.get("locked_quantity")).isEqualTo(0);
assertThat(line.get("uncovered_quantity")).isEqualTo(0);
assertThat(balance.get("actual_quantity")).isEqualTo(50);
assertThat(balance.get("locked_quantity")).isEqualTo(0);
```

再新增“本次发货超过本单锁定数量时不写入任何变化”的测试。

- [ ] **Step 2: 运行测试并确认按预期失败**

Run: `mvn -f backend/pom.xml -pl app -am -Dtest=ShipmentQuantityServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，失败值显示订单明细 `locked_quantity` 仍为旧值，证明复现现有缺陷。

- [ ] **Step 3: 实现最小事务修复**

在每条明细发货时计算：

```java
int remainingAfter = ordered - target;
int lockedAfterShipment = locked - delta;
int excessLocked = Math.max(0, lockedAfterShipment - remainingAfter);
int finalLocked = lockedAfterShipment - excessLocked;
int lockedReduction = delta + excessLocked;
int uncovered = Math.max(0, remainingAfter - finalLocked);
```

库存表同步执行 `actual_quantity -= delta`、`locked_quantity -= lockedReduction`；订单明细同步写入 `shipped_quantity=target`、`locked_quantity=finalLocked`、`uncovered_quantity=uncovered`。库存流水的锁定变化量使用 `-lockedReduction`，实际库存变化量使用 `-delta`。写入前校验实际库存、库存总锁定数量和本单锁定数量均足够。

- [ ] **Step 4: 运行定向测试并确认通过**

Run: `mvn -f backend/pom.xml -pl app -am -Dtest=ShipmentQuantityServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS，新增与原有测试全部通过。

- [ ] **Step 5: 提交**

```bash
git add backend/app/src/main/java/com/internalops/workbench/ShipmentQuantityService.java backend/app/src/test/java/com/internalops/workbench/ShipmentQuantityServiceTest.java
git commit -m "修复分批发货锁定库存释放"
```

### Task 2: 整单发货归零订单锁定数量

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/FulfillmentService.java`
- Test: `backend/app/src/test/java/com/internalops/workbench/SalesOrderCommandApiTest.java`

**Interfaces:**
- Consumes: `FulfillmentService.ship(long, ShippingRequest)`。
- Produces: 整单发货后每条 `sales_order_item.locked_quantity=0` 且库存总锁定量同步减少。

- [ ] **Step 1: 写入失败 API 回归测试**

在现有销售订单测试数据上执行整单发货后查询：

```java
assertThat(jdbc.queryForObject(
    "SELECT locked_quantity FROM sales_order_item WHERE sales_order_id=? AND line_no=?",
    Integer.class, orderId, 10000)).isZero();
```

同时断言库存余额的锁定数量按发货数量减少，订单状态为 `SHIPPED`。

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -f backend/pom.xml -pl app -am -Dtest=SalesOrderCommandApiTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，订单明细锁定数量仍为发货前数值。

- [ ] **Step 3: 实现最小修复**

将整单发货的订单明细更新改为同时归零锁定数量：

```java
jdbc.update("UPDATE sales_order_item SET shipped_quantity=quantity,locked_quantity=0,uncovered_quantity=0,version=version+1 WHERE id=?", itemId);
```

保留对 `locked_quantity == 未发货数量` 的校验及现有库存流水行为。

- [ ] **Step 4: 运行定向测试并确认通过**

Run: `mvn -f backend/pom.xml -pl app -am -Dtest=SalesOrderCommandApiTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/app/src/main/java/com/internalops/workbench/FulfillmentService.java backend/app/src/test/java/com/internalops/workbench/SalesOrderCommandApiTest.java
git commit -m "修复整单发货锁定数量归零"
```

### Task 3: 统一三个订单流程的数量名称

**Files:**
- Modify: `frontend/src/components/BusinessTraceDialog.vue`
- Modify: `frontend/src/components/OrderAllocationDialog.vue`
- Modify: `frontend/src/components/ShipmentQuantityDialog.vue`
- Test: `frontend/src/components/BusinessTraceDialog.test.ts`
- Test: `frontend/src/components/OrderAllocationDialog.test.ts`
- Test: `frontend/src/components/ShipmentQuantityDialog.test.ts`

**Interfaces:**
- Consumes: 现有 API 字段 `quantity`、`shippedQuantity`、`remainingQuantity`、`lockedQuantity`、`uncoveredQuantity`、`actualQuantity`、`availableQuantity`。
- Produces: 三个界面一致的中文字段，不改变 API 字段名与请求结构。

- [ ] **Step 1: 先修改组件测试形成失败断言**

三个测试分别要求出现统一名称，并排除旧名称：

```ts
expect(wrapper.text()).toContain('订单数量')
expect(wrapper.text()).toContain('已发货数量')
expect(wrapper.text()).toContain('本单锁定数量')
expect(wrapper.text()).toContain('未锁定库存数量')
expect(wrapper.text()).not.toContain('累计已发')
expect(wrapper.text()).not.toContain('当前锁定')
expect(wrapper.text()).not.toContain('可用库存')
expect(wrapper.text()).not.toContain('发货后剩余')
```

发货组件额外断言“本次发货数量”和“发货后未发货数量”，业务全景额外断言“未发货数量”和“缺货数量”。

- [ ] **Step 2: 运行三个测试并确认失败**

Run: `npm --prefix frontend test -- --run src/components/BusinessTraceDialog.test.ts src/components/OrderAllocationDialog.test.ts src/components/ShipmentQuantityDialog.test.ts`

Expected: FAIL，输出显示旧名称仍存在或统一名称缺失。

- [ ] **Step 3: 最小化替换界面文案**

`BusinessTraceDialog.vue` 将订单详情映射为“订单数量 / 已发货数量 / 未发货数量 / 本单锁定数量 / 缺货数量 / 未锁定库存数量”；`OrderAllocationDialog.vue` 替换表头“订单数 / 已发货 / 实际库存 / 当前锁定 / 未锁定库存”；`ShipmentQuantityDialog.vue` 替换顶部及明细中的“累计已发 / 本次发货 / 发货后剩余 / 可用库存”。保留原 TypeScript 属性和计算函数。

- [ ] **Step 4: 运行定向前端测试并确认通过**

Run: `npm --prefix frontend test -- --run src/components/BusinessTraceDialog.test.ts src/components/OrderAllocationDialog.test.ts src/components/ShipmentQuantityDialog.test.ts`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/components/BusinessTraceDialog.vue frontend/src/components/OrderAllocationDialog.vue frontend/src/components/ShipmentQuantityDialog.vue frontend/src/components/BusinessTraceDialog.test.ts frontend/src/components/OrderAllocationDialog.test.ts frontend/src/components/ShipmentQuantityDialog.test.ts
git commit -m "统一订单发货流程数量字段"
```

### Task 4: 全量验证与本地 Docker 验收

**Files:**
- No production file changes expected.

**Interfaces:**
- Consumes: Tasks 1–3 的后端事务和前端文案。
- Produces: 可访问的本地项目与完整验证证据。

- [ ] **Step 1: 执行完整验证**

Run:

```bash
mvn -f backend/pom.xml test
npm --prefix frontend test -- --run
npm --prefix frontend run build
git diff --check
```

Expected: 后端、前端全部测试通过，生产构建成功，`git diff --check` 无输出。

- [ ] **Step 2: 重建并启动本地服务**

Run: `docker compose up -d --build`

Expected: MySQL 数据卷保留，frontend、backend、mysql 容器均为 `Up`，MySQL 为 `healthy`。

- [ ] **Step 3: 浏览器验收**

登录 `http://localhost/`，依次打开订单业务全景、分配库存、确认发货，确认统一名称展示；使用测试订单执行分批发货，刷新后确认本单锁定数量按本次发货数量减少，完成发货后归零。

- [ ] **Step 4: 最终状态检查**

Run: `git status --short`

Expected: 仅显示任务开始前已有的 `data/` 和单号功能未跟踪文件，不出现本任务未提交文件。
