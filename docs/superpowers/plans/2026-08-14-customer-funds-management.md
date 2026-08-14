# 客户资金管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为每个客户建立可审核、可追溯的资金账户，并把余额展示、订单收款扣减、售后退款和日/月/年汇总接入现有业务页面。

**Architecture:** 使用 `customer_fund_account` 保存可用余额与乐观锁版本，使用不可变的 `customer_fund_request` 表达打款/退款审核，使用 `customer_fund_ledger` 保存所有生效变动。所有余额变动通过一个事务服务执行并写入变动前后余额；页面通过独立资金 API 接入，现有订单和售后服务只调用该事务服务。

**Tech Stack:** Java 17、Spring Boot 3.3、JdbcTemplate、Flyway、MySQL/H2、Vue 3、TypeScript、Vitest、Maven/JUnit 5。

## Global Constraints

- 订单保存不自动扣减客户余额，余额覆盖率低于 100% 只提醒、不阻止生成正式订单。
- 订单登记收款只能使用客户余额，金额不得超过客户余额或订单未收金额。
- 打款与售后退款只有财务或管理员审核通过后生效；审核人可以审核本人提交的记录。
- 生效流水不可修改或删除，错误只能通过带原因的冲正流水修复。
- 客户余额不得小于零，所有金额使用 `DECIMAL(19,4)` / `BigDecimal`。
- 弹窗使用固定高度和内部滚动，不拉高列表行；点击遮罩不关闭。

---

### Task 1: 资金账户、申请与不可变流水数据库结构

**Files:**
- Create: `backend/app/src/main/resources/db/migration/V41__customer_funds_management.sql`
- Create: `backend/app/src/test/java/com/internalops/customerfund/CustomerFundSchemaTest.java`

**Interfaces:**
- Produces: `customer_fund_account(customer_id, balance, version)`、`customer_fund_request`、`customer_fund_ledger` 三张表。

- [ ] **Step 1: 写失败的结构测试**

在 `CustomerFundSchemaTest` 中使用 `@SpringBootTest` 和 `JdbcTemplate`，断言三张表存在，并验证账户余额检查约束、申请状态/类型字段、流水原记录引用和唯一业务键：

```java
assertThat(columns("CUSTOMER_FUND_ACCOUNT")).contains("CUSTOMER_ID", "BALANCE", "VERSION");
assertThat(columns("CUSTOMER_FUND_REQUEST")).contains("REQUEST_TYPE", "STATUS", "AMOUNT", "REVIEWED_BY");
assertThat(columns("CUSTOMER_FUND_LEDGER")).contains("DIRECTION", "BALANCE_BEFORE", "BALANCE_AFTER", "REVERSES_LEDGER_ID");
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl app -am -Dtest=CustomerFundSchemaTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，提示 `CUSTOMER_FUND_ACCOUNT` 不存在。

- [ ] **Step 3: 创建迁移**

迁移必须包含：

```sql
CREATE TABLE customer_fund_account (
  customer_id BIGINT PRIMARY KEY,
  balance DECIMAL(19,4) NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_fund_account_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
  CONSTRAINT ck_fund_account_non_negative CHECK (balance >= 0)
);
INSERT INTO customer_fund_account(customer_id) SELECT id FROM customer;

CREATE TABLE customer_fund_request (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, customer_id BIGINT NOT NULL,
  request_type VARCHAR(30) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  amount DECIMAL(19,4) NOT NULL, payment_date DATE, payment_method VARCHAR(60),
  reference_no VARCHAR(100), source_type VARCHAR(30), source_id BIGINT,
  remark VARCHAR(1000), review_comment VARCHAR(1000), submitted_by BIGINT,
  submitted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), reviewed_by BIGINT,
  reviewed_at DATETIME(3), version INT NOT NULL DEFAULT 0,
  CONSTRAINT fk_fund_request_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
  CONSTRAINT ck_fund_request_amount CHECK (amount > 0),
  INDEX idx_fund_request_customer_status (customer_id,status,submitted_at)
);

CREATE TABLE customer_fund_ledger (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, customer_id BIGINT NOT NULL,
  entry_type VARCHAR(30) NOT NULL, direction VARCHAR(10) NOT NULL,
  amount DECIMAL(19,4) NOT NULL, balance_before DECIMAL(19,4) NOT NULL,
  balance_after DECIMAL(19,4) NOT NULL, request_id BIGINT,
  source_type VARCHAR(30), source_id BIGINT, source_no VARCHAR(80),
  reverses_ledger_id BIGINT, reason VARCHAR(1000), operated_by BIGINT,
  operated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_fund_ledger_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
  CONSTRAINT fk_fund_ledger_request FOREIGN KEY (request_id) REFERENCES customer_fund_request(id),
  CONSTRAINT fk_fund_ledger_reversal FOREIGN KEY (reverses_ledger_id) REFERENCES customer_fund_ledger(id),
  CONSTRAINT uk_fund_ledger_request UNIQUE (request_id),
  INDEX idx_fund_ledger_customer_time (customer_id,operated_at,id)
);
```

同时为未来新增客户增加应用层账户初始化，不添加数据库触发器。

- [ ] **Step 4: 运行结构测试**

Run: `mvn -pl app -am -Dtest=CustomerFundSchemaTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/app/src/main/resources/db/migration/V41__customer_funds_management.sql backend/app/src/test/java/com/internalops/customerfund/CustomerFundSchemaTest.java
git commit -m "建立客户资金账户与流水结构"
```

### Task 2: 余额记账、审核与冲正领域服务

**Files:**
- Create: `backend/app/src/main/java/com/internalops/customerfund/CustomerFundRequestCommand.java`
- Create: `backend/app/src/main/java/com/internalops/customerfund/CustomerFundReviewCommand.java`
- Create: `backend/app/src/main/java/com/internalops/customerfund/CustomerFundReversalCommand.java`
- Create: `backend/app/src/main/java/com/internalops/customerfund/CustomerFundService.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/CustomerManagementService.java`
- Create: `backend/app/src/test/java/com/internalops/customerfund/CustomerFundServiceTest.java`
- Create: `backend/app/src/test/resources/customer-fund-schema.sql`

**Interfaces:**
- Produces: `submitDeposit(long customerId, CustomerFundRequestCommand)`、`review(long requestId, CustomerFundReviewCommand)`、`debitForOrder(long orderId, BigDecimal amount, String method)`、`submitAfterSalesRefund(long afterSalesId, BigDecimal amount, String remark)`、`reverse(long ledgerId, CustomerFundReversalCommand)`。

- [ ] **Step 1: 写领域失败测试**

覆盖：新增客户自动建零余额账户；待审核打款不改余额；财务/管理员可审核自己提交的打款；普通用户不可审核；重复审核只生效一次；扣减不能超过余额；同一订单登记产生出账流水；退款审核入账；同一流水只能冲正一次。

```java
assertThat(service.balance(customerId)).isEqualByComparingTo("0.0000");
long requestId = service.submitDeposit(customerId, new CustomerFundRequestCommand(new BigDecimal("1000"), date, "银行转账", "BANK-1", null));
assertThat(service.balance(customerId)).isZero();
service.review(requestId, new CustomerFundReviewCommand(true, "确认到账"));
assertThat(service.balance(customerId)).isEqualByComparingTo("1000.0000");
assertThatThrownBy(() -> service.debitForOrder(orderId, new BigDecimal("1001"), "客户余额"))
    .hasMessageContaining("客户余额不足");
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl app -am -Dtest=CustomerFundServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，类或方法不存在。

- [ ] **Step 3: 实现事务服务**

`CustomerFundService` 使用 `SELECT ... FOR UPDATE` 锁定账户；统一私有方法：

```java
private long post(long customerId, String entryType, String direction,
                  BigDecimal amount, Long requestId, String sourceType,
                  Long sourceId, String sourceNo, Long reversesId, String reason) {
    Account account = lockedAccount(customerId);
    BigDecimal after = "IN".equals(direction)
        ? account.balance().add(amount) : account.balance().subtract(amount);
    if (after.signum() < 0) throw new IllegalArgumentException("客户余额不足");
    updateAccount(account, after);
    return insertLedger(account.balance(), after, amount, direction, entryType,
        requestId, sourceType, sourceId, sourceNo, reversesId, reason);
}
```

审核先用 `WHERE id=? AND status='PENDING' AND version=?` 原子更新状态，再调用 `post`；拒绝不记账。权限读取 `CurrentUser`，仅允许 `ADMIN`、`FINANCE`。冲正读取并锁定原流水，检查不存在引用该流水的 `REVERSAL` 后按相反方向记账。

`CustomerManagementService.create` 在插入客户后执行：

```java
jdbc.update("INSERT INTO customer_fund_account(customer_id,balance,version) VALUES(?,0,0)", id);
```

- [ ] **Step 4: 运行领域测试**

Run: `mvn -pl app -am -Dtest=CustomerFundServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/app/src/main/java/com/internalops/customerfund backend/app/src/main/java/com/internalops/workbench/CustomerManagementService.java backend/app/src/test/java/com/internalops/customerfund backend/app/src/test/resources/customer-fund-schema.sql
git commit -m "实现客户资金审核与不可变记账"
```

### Task 3: 资金管理查询、汇总与导出 API

**Files:**
- Create: `backend/app/src/main/java/com/internalops/customerfund/CustomerFundController.java`
- Create: `backend/app/src/main/java/com/internalops/customerfund/CustomerFundQueryService.java`
- Create: `backend/app/src/test/java/com/internalops/customerfund/CustomerFundApiTest.java`

**Interfaces:**
- Produces endpoints: `GET /api/customers/{id}/funds/overview`、`GET /requests`、`GET /ledger`、`GET /summary?period=DAY|MONTH|YEAR&from=&to=`、`GET /summary/export`、`POST /deposits`、`POST /requests/{id}/review`、`POST /ledger/{id}/reverse`。

- [ ] **Step 1: 写 API 失败测试**

使用 MockMvc 验证概览字段 `balance/orderOutstandingAmount/coverageRatio/pendingAmount`；列表分页；汇总仅统计已生效流水；CSV 响应包含 UTF-8 BOM 和中文表头；普通用户审核返回 403，财务和管理员返回 200。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl app -am -Dtest=CustomerFundApiTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL with 404。

- [ ] **Step 3: 实现查询与控制器**

概览中的订单未收金额统一按现有财务口径计算：非草稿订单的应收金额减 `customer_receipt` 合计；覆盖率用 `balance / outstanding * 100`，未收为零返回 `100`。汇总按 `operated_at` 截止时间求期初余额，并按流水类型聚合：

```sql
SUM(CASE WHEN entry_type='CUSTOMER_DEPOSIT' THEN amount ELSE 0 END) deposit_amount,
SUM(CASE WHEN entry_type='ORDER_RECEIPT' THEN amount ELSE 0 END) receipt_amount,
SUM(CASE WHEN entry_type='AFTER_SALES_REFUND' THEN amount ELSE 0 END) refund_amount,
SUM(CASE WHEN entry_type='REVERSAL' THEN CASE direction WHEN 'IN' THEN amount ELSE -amount END ELSE 0 END) reversal_amount
```

导出调用同一查询结果，避免另设统计口径。

- [ ] **Step 4: 运行 API 测试**

Run: `mvn -pl app -am -Dtest=CustomerFundApiTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/app/src/main/java/com/internalops/customerfund backend/app/src/test/java/com/internalops/customerfund
git commit -m "提供客户资金查询汇总与审核接口"
```

### Task 4: 订单登记收款改为扣减客户余额

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/FinanceWorkflowService.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/FinanceActionRequest.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/FinanceWorkflowServiceTest.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/FinanceWorkflowPartialReceiptTest.java`
- Modify: `backend/app/src/test/resources/finance-workbench-schema.sql`

**Interfaces:**
- Consumes: `CustomerFundService.debitForOrder(long, BigDecimal, String)`。
- Produces: 原 `POST /api/finance/orders/{id}/receipt` 保持路径兼容，响应增加 `customerBalance`。

- [ ] **Step 1: 修改测试表达新规则**

测试先向客户资金账户存入 1000，再登记 300，断言账户余额 700、`customer_receipt` 为 300、流水类型为 `ORDER_RECEIPT`；余额 200 时登记 300 返回“客户余额不足”；订单未收 100 时登记 101 返回“不能超过订单未收金额”。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl app -am -Dtest=FinanceWorkflowServiceTest,FinanceWorkflowPartialReceiptTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，现实现未扣客户余额。

- [ ] **Step 3: 原子接入资金服务**

`receipt` 锁定订单并取得 `customer_id`，校验未收金额后调用 `debitForOrder`，再插入 `customer_receipt`。删除“外部收款直接入账”的语义，保留 `paymentMethod` 作为流水说明；三个动作处于同一事务。

- [ ] **Step 4: 运行测试**

Run: `mvn -pl app -am -Dtest=FinanceWorkflowServiceTest,FinanceWorkflowPartialReceiptTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/app/src/main/java/com/internalops/workbench/FinanceWorkflowService.java backend/app/src/main/java/com/internalops/workbench/FinanceActionRequest.java backend/app/src/test/java/com/internalops/workbench/FinanceWorkflowServiceTest.java backend/app/src/test/java/com/internalops/workbench/FinanceWorkflowPartialReceiptTest.java backend/app/src/test/resources/finance-workbench-schema.sql
git commit -m "订单收款改用客户余额扣减"
```

### Task 5: 售后退款申请与审核入账

**Files:**
- Create: `backend/app/src/main/java/com/internalops/aftersales/AfterSalesRefundRequest.java`
- Modify: `backend/app/src/main/java/com/internalops/aftersales/AfterSalesCommandService.java`
- Modify: `backend/app/src/main/java/com/internalops/aftersales/AfterSalesQueryService.java`
- Modify: `backend/app/src/main/java/com/internalops/aftersales/AfterSalesController.java`
- Create: `backend/app/src/test/java/com/internalops/aftersales/AfterSalesRefundApiTest.java`

**Interfaces:**
- Consumes: `CustomerFundService.submitAfterSalesRefund(...)` 与通用 review API。
- Produces: `GET /api/after-sales/{id}/refund-suggestion`、`POST /api/after-sales/{id}/refunds`。

- [ ] **Step 1: 写退款失败测试**

构造原订单销售价 25、实际收退数量 3，断言建议退款 75；提交最终退款 70 后余额不变且申请待审核；财务审核后余额增加 70；换货单或未收退货时拒绝创建退款。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl app -am -Dtest=AfterSalesRefundApiTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL with 404。

- [ ] **Step 3: 实现退款建议和申请**

建议金额 SQL 使用 `after_sales_return_line.received_quantity * sales_order_item.sale_price`；最终金额必须大于 0，可以高于或低于建议金额；当最终金额与建议金额不一致时必须填写调整原因并写入审核记录。创建 `AFTER_SALES_REFUND` 待审核申请，审核复用 Task 2 的入账逻辑。

- [ ] **Step 4: 运行退款测试**

Run: `mvn -pl app -am -Dtest=AfterSalesRefundApiTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/app/src/main/java/com/internalops/aftersales backend/app/src/test/java/com/internalops/aftersales/AfterSalesRefundApiTest.java
git commit -m "增加售后退款审核入账"
```

### Task 6: 前端资金 API 与客户资金管理弹窗

**Files:**
- Create: `frontend/src/api/customer-funds.ts`
- Create: `frontend/src/api/customer-funds.test.ts`
- Create: `frontend/src/components/CustomerFundsDialog.vue`
- Create: `frontend/src/components/CustomerFundsDialog.test.ts`
- Modify: `frontend/src/components/ModuleListPage.vue`
- Modify: `frontend/src/components/ModuleListPage.test.ts`
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/modules/module-config.ts`

**Interfaces:**
- Consumes: Task 3 全部资金 API。
- Produces: 客户行 `funds` 事件和固定高度资金管理弹窗。

- [ ] **Step 1: 写组件失败测试**

断言客户行显示“资金管理”；弹窗包含概览、打款登记、审核记录、资金流水、汇总报表五个标签；低覆盖率使用 `.fund-warning`；遮罩点击不触发关闭；财务/管理员看到审核与冲正按钮；列表流水使用单行省略和详情展开，不增加客户列表行高。

- [ ] **Step 2: 运行测试确认失败**

Run: `npm test -- CustomerFundsDialog.test.ts ModuleListPage.test.ts customer-funds.test.ts`

Expected: FAIL，组件/API 不存在。

- [ ] **Step 3: 实现 API 与弹窗**

定义 `FundOverview`、`FundRequest`、`FundLedger`、`FundSummaryRow` 类型；弹窗使用：

```vue
<div class="dialog-mask"><section class="fund-dialog" @click.stop>
  <header>客户资金管理 <button @click="$emit('close')">关闭</button></header>
  <nav><!-- 五个标签 --></nav>
  <main class="fund-dialog__body"><!-- 当前标签内部滚动 --></main>
</section></div>
```

样式固定 `max-height: min(88vh, 900px)`，`fund-dialog__body { overflow: auto; min-height: 0; }`；遮罩不绑定关闭事件。客户列表列增加余额、订单未收金额、覆盖率、待审核金额，资金数据由工作台查询返回，避免逐行请求。

- [ ] **Step 4: 运行组件测试**

Run: `npm test -- CustomerFundsDialog.test.ts ModuleListPage.test.ts customer-funds.test.ts`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/api/customer-funds.ts frontend/src/api/customer-funds.test.ts frontend/src/components/CustomerFundsDialog.vue frontend/src/components/CustomerFundsDialog.test.ts frontend/src/components/ModuleListPage.vue frontend/src/components/ModuleListPage.test.ts frontend/src/App.vue frontend/src/modules/module-config.ts
git commit -m "增加客户资金管理页面"
```

### Task 7: 订单余额覆盖提示和余额收款弹窗

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/SalesOrderController.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/SalesOrderCommandApiTest.java`
- Modify: `frontend/src/api/workbench.ts`
- Modify: `frontend/src/components/OrderDialog.vue`
- Modify: `frontend/src/components/OrderDialog.test.ts`
- Modify: `frontend/src/components/ReceiptDialog.vue`
- Modify: `frontend/src/components/ReceiptDialog.test.ts`

**Interfaces:**
- Produces: 订单客户选项包含 `fundBalance`；订单详情包含 `customerBalance/orderAmount/fundCoverageRatio`。

- [ ] **Step 1: 写订单 UI 与 API 失败测试**

断言选择客户后显示“客户余额 ¥… / 订单金额 ¥… / 覆盖比例 …%”；修改数量或售价会实时更新订单金额和比例；低于 100% 变红但保存按钮可用。收款弹窗标题为“使用客户余额登记收款”，默认金额为 `min(customerBalance, outstandingAmount)`，超限时前端阻止提交。

- [ ] **Step 2: 运行测试确认失败**

Run backend: `mvn -pl app -am -Dtest=SalesOrderCommandApiTest -Dsurefire.failIfNoSpecifiedTests=false test`

Run frontend: `npm test -- OrderDialog.test.ts ReceiptDialog.test.ts`

Expected: FAIL，资金字段或提示不存在。

- [ ] **Step 3: 实现资金展示**

订单金额使用现有明细 `quantity * salePrice` 的 computed；覆盖率只做展示：

```ts
const orderAmount = computed(() => form.items.reduce((sum, item) => sum + item.quantity * item.salePrice, 0))
const coverageRatio = computed(() => orderAmount.value <= 0 ? null : customerBalance.value / orderAmount.value * 100)
```

收款提交继续调用现有 receipt API，但去掉外部到账语义。后端工作台订单列表/详情通过客户账户联表返回余额。

- [ ] **Step 4: 运行订单测试**

Run backend: `mvn -pl app -am -Dtest=SalesOrderCommandApiTest -Dsurefire.failIfNoSpecifiedTests=false test`

Run frontend: `npm test -- OrderDialog.test.ts ReceiptDialog.test.ts`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/app/src/main/java/com/internalops/workbench/SalesOrderController.java backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java backend/app/src/test/java/com/internalops/workbench/SalesOrderCommandApiTest.java frontend/src/api/workbench.ts frontend/src/components/OrderDialog.vue frontend/src/components/OrderDialog.test.ts frontend/src/components/ReceiptDialog.vue frontend/src/components/ReceiptDialog.test.ts
git commit -m "订单展示客户余额覆盖比例"
```

### Task 8: 售后退款弹窗与综合回归

**Files:**
- Modify: `frontend/src/api/after-sales.ts`
- Create: `frontend/src/components/AfterSalesRefundDialog.vue`
- Create: `frontend/src/components/AfterSalesRefundDialog.test.ts`
- Modify: `frontend/src/components/ModuleListPage.vue`
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/App.test.ts`

**Interfaces:**
- Consumes: Task 5 退款建议和提交 API、Task 3 通用审核 API。

- [ ] **Step 1: 写退款 UI 失败测试**

断言纯退货且已确认收货的售后单显示“申请退款”；弹窗展示退货数量、原销售单价、建议退款金额、可编辑最终退款金额和备注；提交后提示“退款申请已提交，等待财务审核”；换货单不显示入口。

- [ ] **Step 2: 运行测试确认失败**

Run: `npm test -- AfterSalesRefundDialog.test.ts App.test.ts`

Expected: FAIL，退款组件或事件不存在。

- [ ] **Step 3: 实现售后退款前端**

接入 `loadRefundSuggestion` 和 `submitAfterSalesRefund`；弹窗同样使用固定高度、内部滚动且不支持遮罩关闭。提交成功后关闭弹窗并刷新售后列表和客户资金数据。

- [ ] **Step 4: 执行完整验证**

Run: `npm test`

Expected: 全部前端测试 PASS。

Run: `npm run build`

Expected: TypeScript 与 Vite 构建成功。

Run: `mvn test`

Expected: Maven reactor `BUILD SUCCESS`，0 failures，0 errors。

- [ ] **Step 5: 人工业务验收**

启动 Docker 后依次验证：客户打款待审核→审核入账→订单显示覆盖率→登记收款扣余额→售后退货退款审核→客户余额回充→冲正→日/月/年汇总一致。浏览器宽度 1366px 下确认客户、订单、售后弹窗无右侧遮挡和列表行高膨胀。

- [ ] **Step 6: 提交**

```bash
git add frontend/src/api/after-sales.ts frontend/src/components/AfterSalesRefundDialog.vue frontend/src/components/AfterSalesRefundDialog.test.ts frontend/src/components/ModuleListPage.vue frontend/src/App.vue frontend/src/App.test.ts
git commit -m "接入售后退款与完成资金管理回归"
```

## Self-Review

- Spec coverage: 客户余额、打款审核、自审允许、订单只展示覆盖率、余额登记收款、售后退款、冲正、审计、日/月/年汇总、导出和界面布局均有对应任务。
- Placeholder scan: 无 TBD、TODO 或未指定测试的占位步骤。
- Type consistency: 所有余额写入统一通过 `CustomerFundService`；前端统一使用资金 API 类型；订单与售后只消费已定义接口。
