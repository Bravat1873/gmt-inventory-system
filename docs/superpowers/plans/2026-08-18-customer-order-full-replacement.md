# 客户与销售订单全量替换 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 全量替换服务器客户和销售订单，并将空外部订单号的订单明细按“客户编码 + 订单日期 + 订单类型”自动分组。

**Architecture:** 先扩展销售订单解析和校验，使空外部订单号生成稳定分组键；再新增事务化全量替换服务，复用现有客户提交、订单提交、库存锁定和采购缺口流程。部署后先备份数据库，再使用两份真实工作簿执行预览和一次性替换，最后核对 9 个客户、3 张订单和 11 条明细。

**Tech Stack:** Java 21、Spring Boot、JdbcTemplate、Apache POI、JUnit 5、MySQL 8.4、Vue 3、Docker Compose。

## Global Constraints

- 订单分组键固定为客户编码、订单日期、订单类型。
- 同组订单级字段冲突时整批拒绝，不自动选取第一行。
- 客户表 `Sheet1` 的 9 条记录是客户全量数据源。
- 订单表 `订单导入` 的 11 条记录预计生成 3 张订单。
- 服务器现有销售订单全部删除。
- 供应商、产品主数据和实际库存数量不得删除。
- 删除和导入前必须创建数据库备份。
- 数据写入阶段必须事务化；失败时客户和订单整体回滚。
- 不绕过现有库存锁定、供需余量和采购缺口业务服务。

---

### Task 1: 空外部订单号自动分组

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/importing/SalesOrderExcelParser.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/SalesOrderImportValidationService.java`
- Test: `backend/app/src/test/java/com/internalops/importing/SalesOrderExcelParserTest.java`
- Test: `backend/app/src/test/java/com/internalops/importing/SalesOrderImportValidationServiceTest.java`

**Interfaces:**
- Consumes: Excel 行中的 `customerCode`、`orderDate`、`orderType`、`externalOrderNo`。
- Produces: `SalesOrderExcelParser.groupKey(ParsedImportRow): String`；有外部订单号时继续使用外部号，无外部号时使用规范化三字段键。

- [ ] **Step 1: 写入解析器失败测试**

构造 5 行：同客户同日期同类型 2 行、同客户不同日期 1 行、同客户不同类型 1 行、另一客户 1 行，全部外部订单号为空；断言得到 4 个分组，第一组含 2 条明细。

```java
assertThat(groups).hasSize(4);
assertThat(groups.get("AUTO|B.TW JUNQIAO|2026-01-15|工程订单")).hasSize(2);
```

- [ ] **Step 2: 确认测试失败**

Run: `mvn -pl backend/app -Dtest=SalesOrderExcelParserTest test`

Expected: FAIL，当前解析器要求外部订单号必填或无法稳定分组。

- [ ] **Step 3: 实现规范化分组键**

```java
String groupKey(ParsedImportRow row) {
    String external = text(row, "externalOrderNo");
    if (!external.isBlank()) return "EXTERNAL|" + external.trim();
    return "AUTO|" + normalize(text(row, "customerCode"))
        + "|" + date(row, "orderDate")
        + "|" + normalize(text(row, "orderType"));
}
```

删除“外部订单号必填”错误；外部号非空时保留现有重复校验。

- [ ] **Step 4: 写入同组冲突失败测试**

同一自动分组键的两行使用不同销售员，断言错误包含具体 Excel 行号和“同组销售员不一致”。

- [ ] **Step 5: 实现并验证冲突校验**

Run:

```bash
mvn -pl backend/app -Dtest=SalesOrderExcelParserTest,SalesOrderImportValidationServiceTest test
```

Expected: PASS。

---

### Task 2: 客户全量替换与真实客户工作簿回归

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/importing/CustomerExcelParser.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ImportValidationService.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ImportCommitService.java`
- Test: `backend/app/src/test/java/com/internalops/importing/ExcelImportParserTest.java`
- Test: `backend/app/src/test/java/com/internalops/importing/ImportValidationServiceTest.java`
- Test: `backend/app/src/test/java/com/internalops/importing/ImportCommitServiceTest.java`

**Interfaces:**
- Consumes: `Sheet1` 的客户类型、名称、地址、三类联系人和开票资料。
- Produces: 由客户类型和纳税人识别号生成的唯一客户编码；全量替换提交结果 `customersReplaced = 9`。

- [ ] **Step 1: 增加真实表结构测试数据**

测试标题必须接受工作簿中的“纳悦人识别号”列名作为“纳税人识别号”的兼容别名，并保持电话为文本。

```java
assertThat(row.taxpayerId()).isEqualTo("91440113791007443X");
assertThat(row.invoicePhone()).isEqualTo("020-34802982");
```

- [ ] **Step 2: 确认测试失败并实现列名兼容**

Run: `mvn -pl backend/app -Dtest=ExcelImportParserTest test`

Expected: 先 FAIL，再在 `CustomerExcelParser` 的标题映射中加入兼容别名后 PASS。

- [ ] **Step 3: 写入全量替换事务测试**

准备旧客户及相关可安全替换数据，提交 9 条新客户，断言旧客户消失、新客户为 9 条；模拟第 5 条写入异常，断言旧客户仍完整存在。

- [ ] **Step 4: 实现客户替换入口**

在 `ImportCommitService` 增加受事务保护的方法：

```java
CustomerReplaceResult replaceCustomers(UUID validatedBatchId, String operator)
```

该方法只能接受无校验错误的 CUSTOMER 批次，先处理依赖关系，再调用现有客户写入映射；不得直接拼接 Excel 值执行 SQL。

- [ ] **Step 5: 运行客户导入测试**

Run:

```bash
mvn -pl backend/app -Dtest=ExcelImportParserTest,ImportValidationServiceTest,ImportCommitServiceTest test
```

Expected: PASS。

---

### Task 3: 订单删除与客户订单一体化替换服务

**Files:**
- Create: `backend/app/src/main/java/com/internalops/importing/CustomerOrderFullReplaceService.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ImportController.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/SalesOrderImportCommitService.java`
- Test: `backend/app/src/test/java/com/internalops/importing/CustomerOrderFullReplaceServiceTest.java`
- Test: `backend/app/src/test/java/com/internalops/importing/ImportCommitApiTest.java`

**Interfaces:**
- Consumes: 已验证的客户批次 ID、销售订单批次 ID、操作者。
- Produces: `FullReplaceResult(int customers, int orders, int orderLines)`，预期为 `(9, 3, 11)`。

- [ ] **Step 1: 写入整体回滚失败测试**

测试准备旧订单、旧客户和库存数据；模拟订单第 2 组提交失败，断言旧客户、旧订单、库存锁定和派生数据全部恢复。

- [ ] **Step 2: 写入成功替换失败测试**

断言：

```java
assertThat(result.customers()).isEqualTo(9);
assertThat(result.orders()).isEqualTo(3);
assertThat(result.orderLines()).isEqualTo(11);
assertThat(actualInventoryAfter).isEqualTo(actualInventoryBefore);
```

- [ ] **Step 3: 实现依赖感知的订单删除**

在事务内按数据库外键关系删除订单派生数据、订单明细和订单本体；保留产品、供应商和实际库存，再调用现有库存/供需重算逻辑。删除 SQL 必须使用固定表名和参数化条件，不接受用户输入表名。

- [ ] **Step 4: 实现全量替换编排**

```java
@Transactional
public FullReplaceResult replace(UUID customerBatchId, UUID orderBatchId, String operator) {
    requireValidated(customerBatchId, CUSTOMER);
    requireValidated(orderBatchId, SALES_ORDER);
    deleteExistingOrderGraph();
    int customers = importCommitService.replaceCustomers(customerBatchId, operator).count();
    var orders = salesOrderImportCommitService.commitGrouped(orderBatchId, operator);
    return new FullReplaceResult(customers, orders.orderCount(), orders.lineCount());
}
```

- [ ] **Step 5: 暴露受管理员权限保护的接口**

新增请求体：

```java
record CustomerOrderFullReplaceRequest(UUID customerBatchId, UUID orderBatchId) {}
```

接口仅允许管理员调用，拒绝包含校验错误、已提交或类型不匹配的批次。

- [ ] **Step 6: 运行服务和 API 测试**

Run:

```bash
mvn -pl backend/app -Dtest=CustomerOrderFullReplaceServiceTest,ImportCommitApiTest,SalesOrderImportCommitServiceTest test
```

Expected: PASS。

---

### Task 4: 前端全量替换确认流程

**Files:**
- Modify: `frontend/src/api/workbench.ts`
- Modify: `frontend/src/components/ImportPanel.vue`
- Test: `frontend/src/components/ImportPanel.test.ts`

**Interfaces:**
- Consumes: 客户预览批次、订单预览批次、管理员二次确认。
- Produces: 一次性调用全量替换接口，并显示客户、订单、明细统计。

- [ ] **Step 1: 写入失败测试**

挂载销售订单导入面板，加载两个有效批次，断言按钮文案明确为“全量替换客户和订单”；未勾选“我确认删除服务器现有订单”时按钮禁用。

- [ ] **Step 2: 运行测试并确认失败**

Run: `npm test -- ImportPanel.test.ts`

Expected: FAIL，当前界面没有一体化替换操作。

- [ ] **Step 3: 实现 API 与确认 UI**

```ts
export function replaceCustomersAndOrders(customerBatchId: string, orderBatchId: string) {
  return request('/api/imports/customer-order-full-replace', {
    method: 'POST',
    body: JSON.stringify({ customerBatchId, orderBatchId })
  })
}
```

界面必须显示删除警告、两个批次摘要和预期统计；不可只用普通“确认保存”文案。

- [ ] **Step 4: 验证前端测试**

Run: `npm test -- ImportPanel.test.ts`

Expected: PASS。

---

### Task 5: 真实文件预览、全量验证与部署执行

**Files:**
- Verify: `客户信息20260818.xlsx`
- Verify: `销售订单批量导入模板-已下单待发货.xlsx`
- Verify: backend and frontend files from Tasks 1–4

**Interfaces:**
- Consumes: 两份真实工作簿和通过测试的全量替换功能。
- Produces: 数据库备份文件、9 个客户、3 张订单、11 条订单明细。

- [ ] **Step 1: 运行完整聚焦测试**

Run:

```bash
mvn -pl backend/app -Dtest=SalesOrderExcelParserTest,SalesOrderImportValidationServiceTest,ExcelImportParserTest,ImportValidationServiceTest,ImportCommitServiceTest,SalesOrderImportCommitServiceTest,CustomerOrderFullReplaceServiceTest,ImportCommitApiTest test
cd frontend
npm test -- ImportPanel.test.ts
npm run build
```

Expected: 全部 PASS，构建 exit code 0。

- [ ] **Step 2: 使用真实文件执行只读预览**

Expected:

```text
客户：9 条，0 错误
订单：3 组，11 条明细，0 错误
```

任何错误都必须先修复映射或源数据，不得进入删除阶段。

- [ ] **Step 3: 部署应用但不立即删除数据**

上传当前项目并运行：

```bash
docker compose build backend frontend
docker compose up -d
curl -fsS http://localhost:1234/api/system/health
```

Expected: 应用和数据库正常。

- [ ] **Step 4: 创建服务器数据库备份**

在服务器生成带时间戳的 MySQL dump，确认文件非空并记录绝对路径。备份完成前不得调用全量替换接口。

- [ ] **Step 5: 执行一次性全量替换**

依次上传并预览客户文件、订单文件，再提交两个验证批次到全量替换接口。不得手工运行删除 SQL。

- [ ] **Step 6: 核对数据库和业务结果**

只读查询并断言：

```text
customers = 9
sales_orders = 3
sales_order_items = 11
```

抽查三个订单分组的客户、日期、类型、产品、数量和价格；核对实际库存未被删除，未发货、供需余量和采购缺口已重算。

- [ ] **Step 7: 最终健康检查**

Run:

```bash
curl -fsS http://localhost:1234/api/system/health
docker compose ps
```

Expected: 应用与数据库正常，三个容器均为 `Up`。
