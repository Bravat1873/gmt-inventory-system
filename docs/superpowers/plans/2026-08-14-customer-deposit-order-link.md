# 客户打款关联订单与流水展示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 客户打款可选关联当前客户订单，审核入账后流水展示关联单据号，并将操作时间格式化到秒。

**Architecture:** 后端在客户资金域提供当前客户可关联订单查询，并在提交打款时校验订单归属和状态，复用现有 `source_type/source_id/source_no` 保存来源。前端使用现有 `FuzzyPicker` 选择订单，以纯展示函数格式化流水时间，不新增数据库迁移。

**Tech Stack:** Java 17、Spring Boot、JdbcTemplate、JUnit 5、Vue 3、TypeScript、Vitest、Vue Test Utils。

## Global Constraints

- 关联订单为选填；不选时必须允许作为预付款提交。
- 只能关联当前客户且状态不为 `CANCELLED` 的销售订单，草稿订单允许关联。
- 资金流水表头使用“关联单据号”。
- 操作时间格式固定为 `yyyy-MM-dd HH:mm:ss`。
- 不改变余额、审核权限、月度汇总和订单收款扣减规则。

---

### Task 1: 后端订单选项与打款关联校验

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/customerfund/CustomerFundRequestCommand.java`
- Modify: `backend/app/src/main/java/com/internalops/customerfund/CustomerFundController.java`
- Modify: `backend/app/src/main/java/com/internalops/customerfund/CustomerFundQueryService.java`
- Modify: `backend/app/src/main/java/com/internalops/customerfund/CustomerFundService.java`
- Test: `backend/app/src/test/java/com/internalops/customerfund/CustomerFundApiTest.java`

**Interfaces:**
- Produces: `GET /api/customers/{customerId}/funds/orders` returning `[{id, orderNo, status, orderDate}]`.
- Produces: `CustomerFundRequestCommand(..., Long orderId)` and optional JSON `orderId`.
- Persists: selected order as `source_type='SALES_ORDER'`, `source_id=<orderId>`.

- [ ] **Step 1: Write failing API and service tests**

Add tests that insert two customers, valid/draft/cancelled orders, then assert:

```java
mockMvc.perform(get("/api/customers/{id}/funds/orders", customerId))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data[0].orderNo").value("SO-VALID"));

mockMvc.perform(post("/api/customers/{id}/funds/deposits", customerId)
    .contentType(MediaType.APPLICATION_JSON)
    .content("""{"amount":100,"paymentDate":"2026-08-14","paymentMethod":"银行转账","orderId":%d}""".formatted(orderId)))
    .andExpect(status().isOk());
```

Also assert another customer's order and a `CANCELLED` order return HTTP 400, while omitted `orderId` still returns HTTP 200.

- [ ] **Step 2: Run the focused backend test and verify RED**

Run:

```powershell
mvn -pl app -am "-Dtest=CustomerFundApiTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: compilation or assertions fail because `orderId` and the order-options endpoint do not exist.

- [ ] **Step 3: Extend the command and query endpoint**

Change the command record to:

```java
public record CustomerFundRequestCommand(BigDecimal amount, LocalDate paymentDate,
        String paymentMethod, String referenceNo, String remark, Long orderId) {}
```

Add query method and controller mapping. Also add `Map.entry("orderno","orderNo")` and `Map.entry("orderdate","orderDate")` to the existing `camel` name map so the JSON field names match the frontend interface:

```java
public List<Map<String,Object>> orderOptions(long customerId) {
    return camel(jdbc.queryForList("SELECT id,order_no AS orderNo,status,order_date AS orderDate FROM sales_order WHERE customer_id=? AND status<>'CANCELLED' ORDER BY updated_at DESC,id DESC", customerId));
}

@GetMapping("/customers/{customerId}/funds/orders")
public ApiResponse<List<Map<String,Object>>> orderOptions(@PathVariable long customerId) {
    return ApiResponse.ok(queries.orderOptions(customerId));
}
```

- [ ] **Step 4: Validate and persist the optional order source**

In `submitDeposit`, when `command.orderId()` is non-null, load:

```java
Map<String,Object> order = one("SELECT customer_id,order_no,status FROM sales_order WHERE id=?", command.orderId());
if (number(order, "customer_id") != customerId) throw new IllegalArgumentException("只能关联当前客户的订单");
if ("CANCELLED".equals(string(order.get("status")))) throw new IllegalArgumentException("已取消订单不能关联打款");
```

Pass `"SALES_ORDER"` and `command.orderId()` to `insertRequest`; leave both null for prepayment. Extend `sourceNo` so `SALES_ORDER` reads `sales_order.order_no`, while preserving `AFTER_SALES` behavior.

- [ ] **Step 5: Run backend tests and commit**

Run the command from Step 2. Expected: all `CustomerFundApiTest` tests pass.

```powershell
git add backend/app/src/main/java/com/internalops/customerfund backend/app/src/test/java/com/internalops/customerfund/CustomerFundApiTest.java
git commit -m "增加客户打款关联订单校验"
```

---

### Task 2: 前端可选订单选择器与提交载荷

**Files:**
- Modify: `frontend/src/api/customer-funds.ts`
- Modify: `frontend/src/components/CustomerFundsDialog.vue`
- Modify: `frontend/src/components/CustomerFundsDialog.test.ts`

**Interfaces:**
- Consumes: `GET /api/customers/{id}/funds/orders` from Task 1.
- Sends: `DepositCommand.orderId?: number`.
- Uses: existing `FuzzyPicker` with `FuzzyPickerOption[]`.

- [ ] **Step 1: Write failing component tests**

Mock `loadCustomerFundOrders` with current-customer options and assert the selector exists. Select an option, submit a deposit, and assert:

```ts
expect(api.submitCustomerDeposit).toHaveBeenCalledWith(7, expect.objectContaining({
  amount: 100,
  orderId: 31
}))
```

Add a second test that submits without selection and asserts `orderId` is `undefined`.

- [ ] **Step 2: Run the focused frontend test and verify RED**

```powershell
npm test -- --run src/components/CustomerFundsDialog.test.ts
```

Expected: FAIL because the order API, selector, and `orderId` payload are missing.

- [ ] **Step 3: Add API types and loader**

```ts
export interface FundOrderOption { id:number; orderNo:string; status:string; orderDate?:string }
export interface DepositCommand { amount:number; paymentDate:string; paymentMethod:string; referenceNo?:string; remark?:string; orderId?:number }
export const loadCustomerFundOrders = (id:number) => request<FundOrderOption[]>(`/api/customers/${id}/funds/orders`)
```

- [ ] **Step 4: Add optional order picker to the deposit form**

Load options alongside overview/request/ledger/summary data. Add `orderId` state and map options to labels containing order number, date, and status. Render:

```vue
<label class="fund-order-field"><span>关联订单号（选填）</span>
  <FuzzyPicker v-model="form.orderId" :options="orderOptions" placeholder="搜索当前客户订单号；预付款可不选" />
</label>
```

Include `orderId: form.orderId ?? undefined` in `submitCustomerDeposit`; reset it to null after successful submission.

- [ ] **Step 5: Run frontend component tests and commit**

Run the command from Step 2. Expected: all component tests pass.

```powershell
git add frontend/src/api/customer-funds.ts frontend/src/components/CustomerFundsDialog.vue frontend/src/components/CustomerFundsDialog.test.ts
git commit -m "增加客户打款关联订单选择"
```

---

### Task 3: 流水列文案与秒级时间格式

**Files:**
- Create: `frontend/src/utils/date-time.ts`
- Create: `frontend/src/utils/date-time.test.ts`
- Modify: `frontend/src/components/CustomerFundsDialog.vue`
- Modify: `frontend/src/components/CustomerFundsDialog.test.ts`

**Interfaces:**
- Produces: `formatDateTimeToSecond(value?: string): string`.
- Consumes: ISO local timestamps such as `2026-08-14T16:39:51.288`.

- [ ] **Step 1: Write failing formatter tests**

```ts
expect(formatDateTimeToSecond('2026-08-14T16:39:51.288')).toBe('2026-08-14 16:39:51')
expect(formatDateTimeToSecond(undefined)).toBe('—')
```

Extend the dialog test with a ledger item and assert the header contains“关联单据号”, does not contain“业务单号”, and shows `2026-08-14 16:39:51`.

- [ ] **Step 2: Run tests and verify RED**

```powershell
npm test -- --run src/utils/date-time.test.ts src/components/CustomerFundsDialog.test.ts
```

Expected: FAIL because the formatter and new display are absent.

- [ ] **Step 3: Implement the formatter and update the ledger table**

```ts
export function formatDateTimeToSecond(value?: string) {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}
```

Import the helper, rename the table header to“关联单据号”, and render `formatDateTimeToSecond(item.operatedAt)`.

- [ ] **Step 4: Run full verification**

```powershell
npm test -- --run src/components/CustomerFundsDialog.test.ts src/utils/date-time.test.ts
npm run build
mvn -pl app -am "-Dtest=CustomerFundApiTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: all tests pass and both production builds exit 0.

- [ ] **Step 5: Commit the display change**

```powershell
git add frontend/src/utils/date-time.ts frontend/src/utils/date-time.test.ts frontend/src/components/CustomerFundsDialog.vue frontend/src/components/CustomerFundsDialog.test.ts
git commit -m "统一客户资金流水单据号与时间格式"
```