# 订单类型与售后联动 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将销售订单类型统一为“工程订单、零售订单、前置订单”三选一，并在订单管理及售后管理中展示、排序和联动该字段。

**Architecture:** `sales_order.order_type` 是唯一数据源，售后记录继续只保存 `sales_order_id`，所有售后查询通过关联订单实时读取类型。数据库迁移只归一化可明确识别的历史值；新增和修改订单由后端白名单及前端下拉共同限制，未知旧值保留并要求在订单修改时纠正。

**Tech Stack:** Java 17、Spring Boot、JdbcTemplate、Flyway、JUnit 5、Vue 3、TypeScript、Vitest。

---

## Task 1: 归一化历史订单类型

**Files:**
- Create: `backend/app/src/main/resources/db/migration/V39__normalize_sales_order_types.sql`
- Modify: `backend/app/src/test/java/com/internalops/workbench/WorkbenchSchemaTest.java`

- [ ] **Step 1: 编写失败的迁移结构测试**

在 `WorkbenchSchemaTest` 中读取 V39 脚本，断言脚本同时覆盖下列映射，并且没有把所有未知值统一覆盖：

```text
PROJECT / 工程订单 -> 工程订单
RETAIL / Sales / 销售 / 零售订单 -> 零售订单
前置订单 -> 前置订单
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -f backend/pom.xml -pl app -Dtest=WorkbenchSchemaTest test`

Expected: FAIL，因为 V39 尚不存在。

- [ ] **Step 3: 实现幂等的数据迁移**

创建 V39，使用 `TRIM` 和大小写不敏感匹配，仅更新明确映射的旧值；空值和未知值保持原样。不要给列增加会阻止历史数据启动的数据库 CHECK 约束。

- [ ] **Step 4: 再次运行结构测试**

Run: `mvn -f backend/pom.xml -pl app -Dtest=WorkbenchSchemaTest test`

Expected: PASS。

- [ ] **Step 5: 提交迁移**

```bash
git add backend/app/src/main/resources/db/migration/V39__normalize_sales_order_types.sql backend/app/src/test/java/com/internalops/workbench/WorkbenchSchemaTest.java
git commit -m "归一化历史订单类型"
```

## Task 2: 后端严格校验订单类型

**Files:**
- Modify: `backend/app/src/test/java/com/internalops/workbench/SalesOrderCommandApiTest.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/SalesOrderRequest.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/SalesOrderCommandService.java`

- [ ] **Step 1: 为新增订单编写三种合法值与非法值测试**

覆盖：三个中文值分别可保存；空白、`PROJECT`、`Sales` 和任意文本返回 400，且不写入订单。

- [ ] **Step 2: 为修改订单编写校验测试**

覆盖：历史未知值可被读取，但提交修改时必须改为三个合法值之一；合法修改成功，非法修改返回 400。

- [ ] **Step 3: 运行接口测试并确认失败**

Run: `mvn -f backend/pom.xml -pl app -Dtest=SalesOrderCommandApiTest test`

Expected: 新增的非法值用例 FAIL。

- [ ] **Step 4: 实现单一白名单校验**

在订单命令层集中定义允许值，保存前对 `orderType` 做 `trim` 并校验；错误信息明确写为“订单类型只能选择工程订单、零售订单或前置订单”。避免让控制器和服务各维护一份不同列表。

- [ ] **Step 5: 运行接口测试**

Run: `mvn -f backend/pom.xml -pl app -Dtest=SalesOrderCommandApiTest test`

Expected: PASS。

- [ ] **Step 6: 提交后端校验**

```bash
git add backend/app/src/main/java/com/internalops/workbench/SalesOrderRequest.java backend/app/src/main/java/com/internalops/workbench/SalesOrderCommandService.java backend/app/src/test/java/com/internalops/workbench/SalesOrderCommandApiTest.java
git commit -m "限制订单类型为三种业务选项"
```

## Task 3: 订单新增和修改改为必选下拉

**Files:**
- Modify: `frontend/src/components/OrderDialog.test.ts`
- Modify: `frontend/src/components/OrderDialog.vue`

- [ ] **Step 1: 编写下拉与必选行为测试**

断言新增订单初始值为空、页面只有三个选项、未选择不能保存；修改合法旧订单时回显对应类型；修改未知或空白旧订单时显示未选择并要求纠正。

- [ ] **Step 2: 运行组件测试并确认失败**

Run: `npm --prefix frontend test -- --run src/components/OrderDialog.test.ts`

Expected: FAIL，因为当前是自由文本且新增默认“工程订单”。

- [ ] **Step 3: 实现固定选项下拉**

将订单类型输入框替换为 `select`，选项固定为三个中文值并增加“请选择订单类型”占位项；新增表单使用空值，不自动替用户选择。提交前的前端校验给出一致的中文提示。

- [ ] **Step 4: 运行订单组件相关测试**

Run: `npm --prefix frontend test -- --run src/components/OrderDialog.test.ts src/components/OrderDialog.layout.test.ts src/components/OrderDialog.minimum-order.test.ts`

Expected: PASS。

- [ ] **Step 5: 提交订单表单改动**

```bash
git add frontend/src/components/OrderDialog.vue frontend/src/components/OrderDialog.test.ts
git commit -m "将订单类型改为必选下拉"
```

## Task 4: 订单列表展示并按业务顺序排序

**Files:**
- Modify: `frontend/src/modules/module-config.test.ts`
- Modify: `frontend/src/modules/module-config.ts`
- Modify: `backend/app/src/test/java/com/internalops/workbench/WorkbenchQueryApiTest.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java`

- [ ] **Step 1: 编写订单列表字段测试**

在前端配置测试中断言“订单类型”位于客户之后、支持排序；在后端查询测试中断言响应包含 `orderType`。

- [ ] **Step 2: 编写业务排序测试**

准备工程、零售、前置、未知和空白类型，断言升序为工程→零售→前置→未知/空白；降序保持已知类型反向且未知/空白仍置后。

- [ ] **Step 3: 运行测试并确认失败**

Run: `mvn -f backend/pom.xml -pl app -Dtest=WorkbenchQueryApiTest test`

Run: `npm --prefix frontend test -- --run src/modules/module-config.test.ts`

Expected: 新字段或业务排序用例 FAIL。

- [ ] **Step 4: 实现列表字段与 CASE 排序**

在模块配置的客户列后加入 `orderType`；在查询服务为订单类型定义受控排序表达式。使用 `CASE` 给三个已知值固定权重，同时用额外权重保证未知和空值无论升降序都在末尾，不能把请求中的字段名直接拼接进 SQL。

- [ ] **Step 5: 运行前后端定向测试**

Run: `mvn -f backend/pom.xml -pl app -Dtest=WorkbenchQueryApiTest test`

Run: `npm --prefix frontend test -- --run src/modules/module-config.test.ts`

Expected: PASS。

- [ ] **Step 6: 提交订单列表改动**

```bash
git add frontend/src/modules/module-config.ts frontend/src/modules/module-config.test.ts backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java backend/app/src/test/java/com/internalops/workbench/WorkbenchQueryApiTest.java
git commit -m "在订单列表展示并排序订单类型"
```

## Task 5: 售后接口从原订单联动类型

**Files:**
- Modify: `backend/app/src/test/java/com/internalops/aftersales/AfterSalesOrderOptionsQueryTest.java`
- Modify: `backend/app/src/test/java/com/internalops/aftersales/AfterSalesQueryServiceTest.java`
- Modify: `backend/app/src/main/java/com/internalops/aftersales/AfterSalesQueryService.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/WorkbenchQueryApiTest.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java`

- [ ] **Step 1: 编写售后订单选项和详情测试**

断言 `/after-sales/order-options` 返回原订单的 `orderType`，售后详情也返回当前关联订单的 `orderType`；修改原订单类型后再次查询售后详情，应立即得到新类型，证明售后没有复制保存字段。

- [ ] **Step 2: 编写售后列表字段与排序测试**

断言售后列表通过订单关联返回 `orderType`，并按工程→零售→前置、未知最后的业务顺序排序。

- [ ] **Step 3: 运行测试并确认失败**

Run: `mvn -f backend/pom.xml -pl app -Dtest=AfterSalesOrderOptionsQueryTest,AfterSalesQueryServiceTest,WorkbenchQueryApiTest test`

Expected: 新增的 `orderType` 和排序断言 FAIL。

- [ ] **Step 4: 扩展售后查询**

在订单选项、售后详情和售后列表查询中从 `sales_order.order_type` 取值；同步修正聚合查询的 `GROUP BY`。不要给 `after_sales` 表新增订单类型列。

- [ ] **Step 5: 实现售后类型排序**

复用订单列表相同的受控 CASE 排序规则，并保证未知、空白置后。

- [ ] **Step 6: 运行售后及工作台测试**

Run: `mvn -f backend/pom.xml -pl app -Dtest=AfterSalesOrderOptionsQueryTest,AfterSalesQueryServiceTest,WorkbenchQueryApiTest test`

Expected: PASS。

- [ ] **Step 7: 提交售后接口改动**

```bash
git add backend/app/src/main/java/com/internalops/aftersales/AfterSalesQueryService.java backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java backend/app/src/test/java/com/internalops/aftersales/AfterSalesOrderOptionsQueryTest.java backend/app/src/test/java/com/internalops/aftersales/AfterSalesQueryServiceTest.java backend/app/src/test/java/com/internalops/workbench/WorkbenchQueryApiTest.java
git commit -m "售后查询联动原订单类型"
```

## Task 6: 售后列表及新增修改页面展示订单类型

**Files:**
- Modify: `frontend/src/api/after-sales.ts`
- Create: `frontend/src/components/AfterSalesDialog.test.ts`
- Modify: `frontend/src/components/AfterSalesDialog.vue`
- Modify: `frontend/src/modules/module-config.test.ts`
- Modify: `frontend/src/modules/module-config.ts`

- [ ] **Step 1: 编写售后弹窗联动测试**

覆盖：订单下拉项显示“订单号 · 客户 · 类型”；选择订单后基本信息区显示只读订单类型；新增时未选订单显示“未设置”；编辑和查看时显示接口返回类型；未知或空白统一显示“未设置”；该字段不能编辑。

- [ ] **Step 2: 编写售后列表配置测试**

断言“订单类型”列位于客户之后并可排序。

- [ ] **Step 3: 运行前端测试并确认失败**

Run: `npm --prefix frontend test -- --run src/components/AfterSalesDialog.test.ts src/modules/module-config.test.ts`

Expected: FAIL，因为 API 类型和页面尚未提供 `orderType`。

- [ ] **Step 4: 扩展前端类型和售后弹窗**

给售后订单选项与详情响应增加 `orderType?: string | null`；通过当前选中订单或详情计算只读展示值。不要在售后保存 payload 中加入 `orderType`。

- [ ] **Step 5: 增加售后列表列**

在客户之后增加订单类型列并启用排序，未知和空白值显示“未设置”。保持现有表格固定行高与横向滚动布局。

- [ ] **Step 6: 运行前端定向测试**

Run: `npm --prefix frontend test -- --run src/components/AfterSalesDialog.test.ts src/modules/module-config.test.ts`

Expected: PASS。

- [ ] **Step 7: 提交售后页面改动**

```bash
git add frontend/src/api/after-sales.ts frontend/src/components/AfterSalesDialog.vue frontend/src/components/AfterSalesDialog.test.ts frontend/src/modules/module-config.ts frontend/src/modules/module-config.test.ts
git commit -m "在售后页面展示订单类型"
```

## Task 7: 全量验证、浏览器验收与发布准备

**Files:**
- Verify only unless tests reveal an in-scope defect.

- [ ] **Step 1: 运行后端全量测试**

Run: `mvn -f backend/pom.xml test`

Expected: 全部 PASS，测试数不少于当前基线 197。

- [ ] **Step 2: 运行前端全量测试和构建**

Run: `npm --prefix frontend test -- --run`

Expected: 全部 PASS，测试数不少于当前基线 156。

Run: `npm --prefix frontend run build`

Expected: 构建成功，无 TypeScript 错误。

- [ ] **Step 3: 本地 Docker 启动并检查迁移**

Run: `docker compose up -d --build`

Run: `docker compose ps`

Expected: 数据库、后端和前端容器健康，Flyway V39 成功执行，原有 Docker 数据卷未被重建或清空。

- [ ] **Step 4: 用浏览器完成关键流程验收**

检查：新增订单不选类型不能保存；三个类型均可保存；订单列表显示并按业务顺序排序；新增售后订单选项带类型且选择后只读显示；售后查看/修改与列表均显示原订单当前类型；未知旧值显示“未设置”；售后表格右侧无再次遮挡。

- [ ] **Step 5: 检查提交范围**

Run: `git status --short`

Expected: 仅保留用户已有的未跟踪 `data/`，不得加入提交。

- [ ] **Step 6: 提交必要的验收修正**

仅当验收阶段产生修正时提交：

```bash
git add <本功能实际修正的文件>
git commit -m "完善订单类型联动验收细节"
```

- [ ] **Step 7: 推送与服务器更新（经用户确认后）**

```bash
git push origin main
```

随后在 `192.168.60.169:/opt/stacks/gmt-inventory-system` 拉取 `main`，使用服务器现有 Docker Compose 镜像配置重建服务，并再次检查容器、迁移和页面。不得删除或重建数据库数据卷。
