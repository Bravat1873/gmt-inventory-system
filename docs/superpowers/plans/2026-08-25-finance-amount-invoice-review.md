# Finance Amount Invoice Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a finance review workflow where sales orders and purchase orders can register money first, add invoices later, and finance confirms final amounts and invoice numbers without overwriting original user input.

**Architecture:** Keep money records and invoice records as independent reviewable ledgers. Workbench list pages create `PENDING` records; finance review reads a per-order summary, approves or rejects individual records, and official totals only use approved finance-confirmed values.

**Tech Stack:** Java 17, Spring Boot 3.3, JdbcTemplate, Flyway SQL migrations, Vue 3 Composition API, Vitest, Docker Compose.

## Global Constraints

- Original business-entered money amount must remain in `amount`.
- Finance-entered final money amount must be stored in `confirmed_amount`.
- Original business-entered invoice number must remain in `invoice_no`.
- Finance-entered final invoice number must be stored in `confirmed_invoice_no`.
- Money records and invoice records both use `PENDING`, `APPROVED`, and `REJECTED`.
- Only `APPROVED` records count toward official paid, received, or confirmed invoice totals.
- If legacy `review_status` is empty, treat the record as `APPROVED`.
- If legacy `confirmed_amount` is empty, use the original amount field for official totals.
- Rejected records remain visible in history and do not affect official totals.
- Do not add OCR, tax-platform verification, or automatic hard blocking for amount mismatch.

---

## File Structure

- Modify `backend/app/src/main/resources/db/migration/V60__finance_confirmed_values.sql`: add finance confirmation columns to money and invoice tables.
- Modify `backend/app/src/main/java/com/internalops/workbench/FinanceReviewRequest.java`: carry `confirmedAmount` and `confirmedInvoiceNo`.
- Modify `backend/app/src/main/java/com/internalops/workbench/FinanceReviewService.java`: approve and reject receipt, payment, and invoice records with confirmed values.
- Modify `backend/app/src/main/java/com/internalops/workbench/FinanceInvoiceService.java`: create invoices as `PENDING`, list all invoice review states.
- Modify `backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java`: compute finance and purchase list totals from approved confirmed values.
- Modify `backend/app/src/main/java/com/internalops/workbench/FinanceController.java`: expose invoice review and review-summary endpoints.
- Test `backend/app/src/test/java/com/internalops/workbench/FinanceReviewApiTest.java`: finance review behavior.
- Test `backend/app/src/test/java/com/internalops/workbench/FinanceInvoiceApiTest.java`: invoice pending/approved/rejected behavior.
- Test `backend/app/src/test/java/com/internalops/workbench/ProcurementWorkflowApiTest.java`: purchase paid totals ignore pending and rejected records.
- Modify `frontend/src/components/FinanceReviewDialog.vue`: show money and invoice review rows, confirmed amount inputs, confirmed invoice number inputs.
- Modify `frontend/src/components/InvoiceDialog.vue`: save invoices as pending records and show review status history.
- Modify `frontend/src/components/PaymentDialog.vue` and `frontend/src/components/ReceiptDialog.vue`: keep money-only entry.
- Modify `frontend/src/App.vue`: wire finance review summary and refresh behavior.
- Test `frontend/src/components/FinanceReviewDialog.test.ts`, `frontend/src/components/InvoiceDialog.test.ts`, `frontend/src/components/PaymentDialog.test.ts`, `frontend/src/components/ReceiptDialog.test.ts`, `frontend/src/App.test.ts`.

---

### Task 1: Database Migration For Confirmed Finance Values

**Files:**
- Create: `backend/app/src/main/resources/db/migration/V60__finance_confirmed_values.sql`
- Modify: `backend/app/src/test/resources/finance-workbench-schema.sql`
- Modify: `backend/app/src/test/resources/procurement-workflow-schema.sql`
- Modify: `backend/app/src/test/resources/sales-command-schema.sql`

**Interfaces:**
- Produces: columns `confirmed_amount`, `confirmed_invoice_no`, `review_status`, `review_remark`, `reviewed_by`, `reviewed_at` exist on invoice tables.
- Produces: columns `confirmed_amount` exists on `customer_receipt` and `supplier_payment`.

- [ ] **Step 1: Write the failing schema assertions**

Add assertions in `FinanceInvoiceApiTest` or a new schema-focused method inside that file:

```java
@Test
void invoiceTablesExposeFinanceReviewColumns() {
    assertThat(jdbc.queryForList("SHOW COLUMNS FROM sales_invoice LIKE 'confirmed_invoice_no'")).isNotEmpty();
    assertThat(jdbc.queryForList("SHOW COLUMNS FROM sales_invoice LIKE 'confirmed_amount'")).isNotEmpty();
    assertThat(jdbc.queryForList("SHOW COLUMNS FROM sales_invoice LIKE 'review_status'")).isNotEmpty();
    assertThat(jdbc.queryForList("SHOW COLUMNS FROM purchase_invoice LIKE 'confirmed_invoice_no'")).isNotEmpty();
    assertThat(jdbc.queryForList("SHOW COLUMNS FROM purchase_invoice LIKE 'confirmed_amount'")).isNotEmpty();
    assertThat(jdbc.queryForList("SHOW COLUMNS FROM purchase_invoice LIKE 'review_status'")).isNotEmpty();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
mvn -pl app "-Dtest=FinanceInvoiceApiTest#invoiceTablesExposeFinanceReviewColumns" test
```

Expected: FAIL because the new columns do not exist in test schema.

- [ ] **Step 3: Add migration**

Create `backend/app/src/main/resources/db/migration/V60__finance_confirmed_values.sql`:

```sql
ALTER TABLE customer_receipt
    ADD COLUMN confirmed_amount DECIMAL(18,2) NULL AFTER amount;

ALTER TABLE supplier_payment
    ADD COLUMN confirmed_amount DECIMAL(18,2) NULL AFTER amount;

ALTER TABLE sales_invoice
    ADD COLUMN confirmed_invoice_no VARCHAR(120) NULL AFTER invoice_no,
    ADD COLUMN confirmed_amount DECIMAL(18,2) NULL AFTER tax_included_amount,
    ADD COLUMN review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER invoice_remark,
    ADD COLUMN review_remark VARCHAR(500) NULL AFTER review_status,
    ADD COLUMN reviewed_by BIGINT NULL AFTER review_remark,
    ADD COLUMN reviewed_at DATETIME NULL AFTER reviewed_by;

ALTER TABLE purchase_invoice
    ADD COLUMN confirmed_invoice_no VARCHAR(120) NULL AFTER invoice_no,
    ADD COLUMN confirmed_amount DECIMAL(18,2) NULL AFTER tax_included_amount,
    ADD COLUMN review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER invoice_remark,
    ADD COLUMN review_remark VARCHAR(500) NULL AFTER review_status,
    ADD COLUMN reviewed_by BIGINT NULL AFTER review_remark,
    ADD COLUMN reviewed_at DATETIME NULL AFTER reviewed_by;

UPDATE customer_receipt SET confirmed_amount = amount WHERE confirmed_amount IS NULL AND COALESCE(review_status, 'APPROVED') = 'APPROVED';
UPDATE supplier_payment SET confirmed_amount = amount WHERE confirmed_amount IS NULL AND COALESCE(review_status, 'APPROVED') = 'APPROVED';
UPDATE sales_invoice SET confirmed_invoice_no = invoice_no, confirmed_amount = tax_included_amount, review_status = 'APPROVED' WHERE review_status IS NULL OR review_status = '';
UPDATE purchase_invoice SET confirmed_invoice_no = invoice_no, confirmed_amount = tax_included_amount, review_status = 'APPROVED' WHERE review_status IS NULL OR review_status = '';
```

- [ ] **Step 4: Update test schemas**

Mirror the same columns in:

```text
backend/app/src/test/resources/finance-workbench-schema.sql
backend/app/src/test/resources/procurement-workflow-schema.sql
backend/app/src/test/resources/sales-command-schema.sql
```

- [ ] **Step 5: Run schema test to verify it passes**

Run:

```powershell
mvn -pl app "-Dtest=FinanceInvoiceApiTest#invoiceTablesExposeFinanceReviewColumns" test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/app/src/main/resources/db/migration/V60__finance_confirmed_values.sql backend/app/src/test/resources/finance-workbench-schema.sql backend/app/src/test/resources/procurement-workflow-schema.sql backend/app/src/test/resources/sales-command-schema.sql backend/app/src/test/java/com/internalops/workbench/FinanceInvoiceApiTest.java
git commit -m "添加财务确认金额和发票复核字段"
```

---

### Task 2: Backend Money Review Uses Finance Confirmed Amount

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/FinanceReviewRequest.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/FinanceReviewService.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/ProcurementWorkflowService.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java`
- Test: `backend/app/src/test/java/com/internalops/workbench/FinanceReviewApiTest.java`
- Test: `backend/app/src/test/java/com/internalops/workbench/ProcurementWorkflowApiTest.java`

**Interfaces:**
- Consumes: `confirmed_amount` columns from Task 1.
- Produces: `FinanceReviewRequest.confirmedAmount()` available to review services.
- Produces: official money total expression `COALESCE(confirmed_amount, amount)` for approved records.

- [ ] **Step 1: Write failing review test**

Add to `FinanceReviewApiTest`:

```java
@Test
void approvingPaymentStoresConfirmedAmountWithoutOverwritingOriginalAmount() throws Exception {
    Cookie session = login();
    long purchaseId = createManualPurchase(session, 10);

    mvc.perform(post("/api/procurement/purchases/{id}/payment", purchaseId).cookie(session)
                    .contentType("application/json")
                    .content("{\"amount\":260.00,\"paymentMethod\":\"银行转账\"}"))
            .andExpect(status().isOk());

    long paymentId = jdbc.queryForObject("SELECT id FROM supplier_payment WHERE purchase_order_id=?", Long.class, purchaseId);
    mvc.perform(post("/api/finance/orders/payments/{id}/review", paymentId).cookie(session)
                    .contentType("application/json")
                    .content("{\"approved\":true,\"confirmedAmount\":250.00,\"reviewRemark\":\"按到账金额确认\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
            .andExpect(jsonPath("$.data.confirmedAmount").value(250.0));

    Map<String, Object> row = jdbc.queryForMap("SELECT amount,confirmed_amount,review_status FROM supplier_payment WHERE id=?", paymentId);
    assertThat(row.get("amount")).isEqualTo(new java.math.BigDecimal("260.00"));
    assertThat(row.get("confirmed_amount")).isEqualTo(new java.math.BigDecimal("250.00"));
    assertThat(row.get("review_status")).isEqualTo("APPROVED");
}
```

- [ ] **Step 2: Run test to verify it fails**

```powershell
mvn -pl app "-Dtest=FinanceReviewApiTest#approvingPaymentStoresConfirmedAmountWithoutOverwritingOriginalAmount" test
```

Expected: FAIL because `confirmedAmount` is not yet used.

- [ ] **Step 3: Extend request record**

Change `FinanceReviewRequest` to:

```java
package com.internalops.workbench;

import java.math.BigDecimal;

public record FinanceReviewRequest(
        boolean approved,
        BigDecimal confirmedAmount,
        String confirmedInvoiceNo,
        String reviewRemark
) {}
```

- [ ] **Step 4: Update money approve SQL**

In `FinanceReviewService`, implement money approval with confirmed amount:

```java
private Map<String,Object> approveMoney(String table, long id, BigDecimal confirmedAmount, String remark) {
    if (confirmedAmount == null || confirmedAmount.signum() <= 0) {
        throw new IllegalArgumentException("财务确认金额必须大于 0");
    }
    jdbc.update("UPDATE " + table + " SET review_status='APPROVED',confirmed_amount=?,reviewed_by=?,reviewed_at=?,review_remark=? WHERE id=?",
            confirmedAmount, CurrentUser.required().id(), LocalDateTime.now(), clean(remark), id);
    return Map.of("id", id, "reviewStatus", "APPROVED", "confirmedAmount", confirmedAmount);
}
```

Use it from `reviewReceipt` and `reviewPayment`. For receipt refund logic, keep the existing positive and negative checks, but calculate official totals with `COALESCE(confirmed_amount, amount)`.

- [ ] **Step 5: Update official total queries**

Replace money total expressions in `ProcurementWorkflowService` and `WorkbenchQueryService`:

```sql
SELECT COALESCE(SUM(COALESCE(confirmed_amount, amount)),0)
FROM supplier_payment
WHERE purchase_order_id=? AND COALESCE(review_status,'APPROVED')='APPROVED'
```

For sales receipts:

```sql
SELECT COALESCE(SUM(COALESCE(confirmed_amount, amount)),0)
FROM customer_receipt
WHERE sales_order_id=? AND COALESCE(review_status,'APPROVED')='APPROVED'
```

- [ ] **Step 6: Run focused backend tests**

```powershell
mvn -pl app "-Dtest=FinanceReviewApiTest,ProcurementWorkflowApiTest" test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/app/src/main/java/com/internalops/workbench/FinanceReviewRequest.java backend/app/src/main/java/com/internalops/workbench/FinanceReviewService.java backend/app/src/main/java/com/internalops/workbench/ProcurementWorkflowService.java backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java backend/app/src/test/java/com/internalops/workbench/FinanceReviewApiTest.java backend/app/src/test/java/com/internalops/workbench/ProcurementWorkflowApiTest.java
git commit -m "支持财务确认资金金额"
```

---

### Task 3: Backend Invoice Review And Finance Summary

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/FinanceInvoiceService.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/FinanceReviewService.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/FinanceController.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java`
- Test: `backend/app/src/test/java/com/internalops/workbench/FinanceInvoiceApiTest.java`
- Test: `backend/app/src/test/java/com/internalops/workbench/FinanceWorkbenchQueryApiTest.java`

**Interfaces:**
- Consumes: invoice review columns from Task 1.
- Produces: `POST /api/finance/orders/invoices/{id}/review`.
- Produces: `GET /api/finance/orders/{type}/{id}/review-summary`.

- [ ] **Step 1: Write failing invoice review test**

Add to `FinanceInvoiceApiTest`:

```java
@Test
void approvingInvoiceStoresConfirmedInvoiceNumberAndAmount() throws Exception {
    Cookie session = login();
    long purchaseId = createManualPurchase(session, 10);

    mvc.perform(post("/api/finance/orders/PAYABLE/{id}/invoices", purchaseId).cookie(session)
                    .contentType("application/json")
                    .content("{\"invoiceNo\":\"INV-RAW-1\",\"invoiceDate\":\"2026-08-25\",\"taxIncludedAmount\":122.00,\"invoiceRemark\":\"业务补录\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reviewStatus").value("PENDING"));

    long invoiceId = jdbc.queryForObject("SELECT id FROM purchase_invoice WHERE purchase_order_id=?", Long.class, purchaseId);
    mvc.perform(post("/api/finance/orders/invoices/{id}/review", invoiceId).cookie(session)
                    .contentType("application/json")
                    .content("{\"approved\":true,\"confirmedAmount\":120.00,\"confirmedInvoiceNo\":\"INV-FINAL-1\",\"reviewRemark\":\"按票面确认\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"));

    Map<String, Object> row = jdbc.queryForMap("SELECT invoice_no,confirmed_invoice_no,tax_included_amount,confirmed_amount,review_status FROM purchase_invoice WHERE id=?", invoiceId);
    assertThat(row.get("invoice_no")).isEqualTo("INV-RAW-1");
    assertThat(row.get("confirmed_invoice_no")).isEqualTo("INV-FINAL-1");
    assertThat(row.get("tax_included_amount")).isEqualTo(new java.math.BigDecimal("122.00"));
    assertThat(row.get("confirmed_amount")).isEqualTo(new java.math.BigDecimal("120.00"));
    assertThat(row.get("review_status")).isEqualTo("APPROVED");
}
```

- [ ] **Step 2: Write failing summary test**

Add to `FinanceWorkbenchQueryApiTest`:

```java
@Test
void reviewSummaryShowsMoneyInvoiceTotalsAndDifferences() throws Exception {
    Cookie session = login();
    long purchaseId = createManualPurchase(session, 10);
    jdbc.update("INSERT INTO supplier_payment(purchase_order_id,amount,confirmed_amount,payment_method,paid_at,review_status) VALUES(?,?,?,?,CURRENT_TIMESTAMP,'APPROVED')",
            purchaseId, new java.math.BigDecimal("100.00"), new java.math.BigDecimal("95.00"), "银行转账");
    jdbc.update("INSERT INTO purchase_invoice(purchase_order_id,invoice_no,confirmed_invoice_no,invoice_date,tax_included_amount,confirmed_amount,review_status) VALUES(?,?,?,?,?,?,?)",
            purchaseId, "P-RAW", "P-FINAL", java.sql.Date.valueOf("2026-08-25"), new java.math.BigDecimal("100.00"), new java.math.BigDecimal("90.00"), "APPROVED");

    mvc.perform(get("/api/finance/orders/PAYABLE/{id}/review-summary", purchaseId).cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.confirmedMoneyAmount").value(95.0))
            .andExpect(jsonPath("$.data.confirmedInvoiceAmount").value(90.0))
            .andExpect(jsonPath("$.data.differenceAmount").value(5.0));
}
```

- [ ] **Step 3: Run tests to verify they fail**

```powershell
mvn -pl app "-Dtest=FinanceInvoiceApiTest#approvingInvoiceStoresConfirmedInvoiceNumberAndAmount,FinanceWorkbenchQueryApiTest#reviewSummaryShowsMoneyInvoiceTotalsAndDifferences" test
```

Expected: FAIL because endpoints and summary are not implemented.

- [ ] **Step 4: Save invoices as pending**

In `FinanceInvoiceService.save`, inserted invoice rows should default to:

```java
review_status = "PENDING"
confirmed_invoice_no = null
confirmed_amount = null
review_remark = null
reviewed_by = null
reviewed_at = null
```

Return map fields:

```java
Map.of(
    "id", invoiceId,
    "invoiceNo", request.invoiceNo().trim(),
    "taxIncludedAmount", request.taxIncludedAmount(),
    "reviewStatus", "PENDING"
)
```

- [ ] **Step 5: Add invoice review service method**

In `FinanceReviewService`:

```java
@Transactional
public Map<String,Object> reviewInvoice(long id, FinanceReviewRequest request) {
    requireReviewer();
    Map<String,Object> row = findInvoiceForUpdate(id);
    ensurePending(row);
    if (!request.approved()) {
        return reject(String.valueOf(row.get("table_name")), id, request.reviewRemark());
    }
    if (request.confirmedAmount() == null || request.confirmedAmount().signum() <= 0) {
        throw new IllegalArgumentException("财务确认发票金额必须大于 0");
    }
    if (request.confirmedInvoiceNo() == null || request.confirmedInvoiceNo().isBlank()) {
        throw new IllegalArgumentException("财务确认发票号码不能为空");
    }
    String table = String.valueOf(row.get("table_name"));
    jdbc.update("UPDATE " + table + " SET review_status='APPROVED',confirmed_invoice_no=?,confirmed_amount=?,reviewed_by=?,reviewed_at=?,review_remark=? WHERE id=?",
            request.confirmedInvoiceNo().trim(), request.confirmedAmount(), CurrentUser.required().id(), LocalDateTime.now(), clean(request.reviewRemark()), id);
    return Map.of("id", id, "reviewStatus", "APPROVED", "confirmedInvoiceNo", request.confirmedInvoiceNo().trim(), "confirmedAmount", request.confirmedAmount());
}
```

Implement `findInvoiceForUpdate` by querying `sales_invoice` first and `purchase_invoice` second. Return `table_name` in the map.

- [ ] **Step 6: Add controller endpoints**

In `FinanceController`:

```java
@PostMapping("/invoices/{id}/review")
public ApiResponse<Map<String,Object>> reviewInvoice(@PathVariable long id, @RequestBody FinanceReviewRequest request) {
    return ApiResponse.ok(review.reviewInvoice(id, request));
}

@GetMapping("/{type}/{id}/review-summary")
public ApiResponse<Map<String,Object>> reviewSummary(@PathVariable String type, @PathVariable long id) {
    return ApiResponse.ok(review.reviewSummary(type, id));
}
```

- [ ] **Step 7: Implement review summary**

In `FinanceReviewService.reviewSummary(String type, long id)`, return:

```java
Map.of(
    "type", type,
    "id", id,
    "confirmedMoneyAmount", confirmedMoney,
    "pendingMoneyCount", pendingMoneyCount,
    "moneyRecords", moneyRecords,
    "confirmedInvoiceAmount", confirmedInvoice,
    "pendingInvoiceCount", pendingInvoiceCount,
    "invoiceRecords", invoiceRecords,
    "differenceAmount", confirmedMoney.subtract(confirmedInvoice).abs()
)
```

For `RECEIVABLE`, read `customer_receipt` and `sales_invoice`. For `PAYABLE`, read `supplier_payment` and `purchase_invoice`.

- [ ] **Step 8: Update finance list summary**

In `WorkbenchQueryService`, official invoice numbers and invoice amount should use approved confirmed invoice records:

```sql
COALESCE((SELECT SUM(COALESCE(si.confirmed_amount, si.tax_included_amount)) FROM sales_invoice si WHERE si.sales_order_id=o.id AND COALESCE(si.review_status,'APPROVED')='APPROVED'),0)
```

Add `pending_invoice_count` alongside existing pending money count, and set status to `待复核` when either count is greater than zero.

- [ ] **Step 9: Run focused backend tests**

```powershell
mvn -pl app "-Dtest=FinanceInvoiceApiTest,FinanceWorkbenchQueryApiTest,FinanceReviewApiTest" test
```

Expected: PASS.

- [ ] **Step 10: Commit**

```powershell
git add backend/app/src/main/java/com/internalops/workbench/FinanceInvoiceService.java backend/app/src/main/java/com/internalops/workbench/FinanceReviewService.java backend/app/src/main/java/com/internalops/workbench/FinanceController.java backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java backend/app/src/test/java/com/internalops/workbench/FinanceInvoiceApiTest.java backend/app/src/test/java/com/internalops/workbench/FinanceWorkbenchQueryApiTest.java
git commit -m "支持发票复核和财务汇总核对"
```

---

### Task 4: Frontend Finance Review Summary Dialog

**Files:**
- Modify: `frontend/src/components/FinanceReviewDialog.vue`
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/api/workbench.ts`
- Test: `frontend/src/components/FinanceReviewDialog.test.ts`
- Test: `frontend/src/App.test.ts`

**Interfaces:**
- Consumes: `GET /api/finance/orders/{type}/{id}/review-summary`.
- Consumes: `POST /api/finance/orders/receipts/{id}/review`.
- Consumes: `POST /api/finance/orders/payments/{id}/review`.
- Consumes: `POST /api/finance/orders/invoices/{id}/review`.
- Produces: dialog events `saved(closeAfter?: boolean)` and `message(text, kind?)`.

- [ ] **Step 1: Write failing component test**

Add to `FinanceReviewDialog.test.ts`:

```ts
it('submits confirmed money amount without closing after rejection', async () => {
  getAction.mockResolvedValue({
    moneyRecords: [{ id: 11, amount: 260, confirmedAmount: null, paymentMethod: '银行转账', reviewStatus: 'PENDING' }],
    invoiceRecords: [{ id: 21, invoiceNo: 'RAW-1', taxIncludedAmount: 260, confirmedInvoiceNo: null, confirmedAmount: null, reviewStatus: 'PENDING' }],
    confirmedMoneyAmount: 0,
    confirmedInvoiceAmount: 0,
    differenceAmount: 0
  })
  postAction.mockResolvedValue({ reviewStatus: 'REJECTED' })
  const wrapper = mount(FinanceReviewDialog, {
    props: { order: { id: 8, cashDirection: 'PAYABLE', businessNo: 'CG001' } }
  })
  await flushPromises()

  await wrapper.get('[data-test="money-confirmed-11"]').setValue('250')
  await wrapper.get('[data-test="money-reject-11"]').trigger('click')
  await flushPromises()

  expect(postAction).toHaveBeenCalledWith('/api/finance/orders/payments/11/review', expect.objectContaining({
    approved: false,
    confirmedAmount: 250
  }))
  expect(wrapper.emitted('saved')?.[0]).toEqual([false])
})
```

- [ ] **Step 2: Run test to verify it fails**

```powershell
npm test -- src/components/FinanceReviewDialog.test.ts
```

Expected: FAIL because summary layout and inputs are missing.

- [ ] **Step 3: Add API helpers**

In `frontend/src/api/workbench.ts`, ensure these helpers exist:

```ts
export async function getAction<T = unknown>(url: string): Promise<T> {
  const response = await request<ApiResponse<T>>(url)
  return unwrap(response)
}

export async function postAction<T = unknown>(url: string, body?: unknown): Promise<T> {
  const response = await request<ApiResponse<T>>(url, { method: 'POST', body: JSON.stringify(body ?? {}) })
  return unwrap(response)
}
```

- [ ] **Step 4: Fetch review summary in dialog**

In `FinanceReviewDialog.vue`, derive type:

```ts
const reviewType = computed(() => props.order.cashDirection === 'PAYABLE' ? 'PAYABLE' : 'RECEIVABLE')
const reviewUrl = computed(() => `/api/finance/orders/${reviewType.value}/${Number(props.order.id)}/review-summary`)
```

Load summary on mount and after every review action.

- [ ] **Step 5: Render money and invoice sections**

Add stable test selectors:

```vue
<input :data-test="`money-confirmed-${record.id}`" v-model="record.confirmedAmountDraft" type="number" step="0.01">
<button :data-test="`money-approve-${record.id}`" @click="reviewMoney(record, true)">通过</button>
<button :data-test="`money-reject-${record.id}`" @click="reviewMoney(record, false)">驳回</button>
<input :data-test="`invoice-no-confirmed-${invoice.id}`" v-model="invoice.confirmedInvoiceNoDraft">
<input :data-test="`invoice-confirmed-${invoice.id}`" v-model="invoice.confirmedAmountDraft" type="number" step="0.01">
<button :data-test="`invoice-approve-${invoice.id}`" @click="reviewInvoice(invoice, true)">通过</button>
<button :data-test="`invoice-reject-${invoice.id}`" @click="reviewInvoice(invoice, false)">驳回</button>
```

- [ ] **Step 6: Submit correct endpoints**

Money endpoint:

```ts
const endpoint = reviewType.value === 'PAYABLE'
  ? `/api/finance/orders/payments/${record.id}/review`
  : `/api/finance/orders/receipts/${record.id}/review`
```

Invoice endpoint:

```ts
await postAction(`/api/finance/orders/invoices/${invoice.id}/review`, {
  approved,
  confirmedAmount: Number(invoice.confirmedAmountDraft),
  confirmedInvoiceNo: invoice.confirmedInvoiceNoDraft?.trim(),
  reviewRemark: reviewRemark.value.trim() || undefined
})
```

- [ ] **Step 7: Wire App refresh behavior**

In `App.vue`, keep dialog open after `saved(false)` and refresh list after any review action.

- [ ] **Step 8: Run frontend tests**

```powershell
npm test -- src/components/FinanceReviewDialog.test.ts src/App.test.ts
```

Expected: PASS.

- [ ] **Step 9: Commit**

```powershell
git add frontend/src/components/FinanceReviewDialog.vue frontend/src/components/FinanceReviewDialog.test.ts frontend/src/App.vue frontend/src/App.test.ts frontend/src/api/workbench.ts
git commit -m "实现财务金额发票汇总复核弹窗"
```

---

### Task 5: Frontend Invoice And Money Entry History

**Files:**
- Modify: `frontend/src/components/InvoiceDialog.vue`
- Modify: `frontend/src/components/PaymentDialog.vue`
- Modify: `frontend/src/components/ReceiptDialog.vue`
- Test: `frontend/src/components/InvoiceDialog.test.ts`
- Test: `frontend/src/components/PaymentDialog.test.ts`
- Test: `frontend/src/components/ReceiptDialog.test.ts`

**Interfaces:**
- Consumes: invoice list records with `reviewStatus`, `reviewRemark`, `confirmedInvoiceNo`, and `confirmedAmount`.
- Produces: invoice add requests without review fields.
- Produces: money entry requests without invoice fields.

- [ ] **Step 1: Write failing invoice history test**

Add to `InvoiceDialog.test.ts`:

```ts
it('shows original and finance confirmed invoice values in history', async () => {
  getAction.mockResolvedValue([
    {
      id: 1,
      invoiceNo: 'RAW-001',
      confirmedInvoiceNo: 'FINAL-001',
      taxIncludedAmount: 122,
      confirmedAmount: 120,
      reviewStatus: 'APPROVED',
      reviewRemark: '财务确认'
    }
  ])
  const wrapper = mount(InvoiceDialog, {
    props: { order: { id: 8, cashDirection: 'PAYABLE', businessNo: 'CG001' } }
  })
  await flushPromises()

  expect(wrapper.text()).toContain('RAW-001')
  expect(wrapper.text()).toContain('FINAL-001')
  expect(wrapper.text()).toContain('¥ 120.00')
  expect(wrapper.text()).toContain('已通过')
})
```

- [ ] **Step 2: Run test to verify it fails**

```powershell
npm test -- src/components/InvoiceDialog.test.ts
```

Expected: FAIL if confirmed fields are not displayed.

- [ ] **Step 3: Update invoice history columns**

In `InvoiceDialog.vue`, render:

```vue
<th>原发票号码</th>
<th>财务确认号码</th>
<th>原金额</th>
<th>确认金额</th>
<th>状态</th>
<th>复核备注</th>
```

Use status labels:

```ts
function reviewLabel(status: unknown) {
  if (status === 'APPROVED') return '已通过'
  if (status === 'REJECTED') return '已驳回'
  return '待复核'
}
```

- [ ] **Step 4: Keep invoice save request business-only**

Ensure save payload only contains:

```ts
{
  invoiceNo: form.invoiceNo.trim(),
  invoiceDate: form.invoiceDate || undefined,
  taxIncludedAmount: Number(form.taxIncludedAmount),
  invoiceRemark: form.invoiceRemark.trim() || undefined
}
```

- [ ] **Step 5: Keep money dialogs money-only**

In `PaymentDialog.vue` and `ReceiptDialog.vue`, ensure there are no `invoiceNo` or `invoiceDate` fields in the form or payload. Existing tests should assert no invoice payload is sent.

- [ ] **Step 6: Run dialog tests**

```powershell
npm test -- src/components/InvoiceDialog.test.ts src/components/PaymentDialog.test.ts src/components/ReceiptDialog.test.ts
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add frontend/src/components/InvoiceDialog.vue frontend/src/components/InvoiceDialog.test.ts frontend/src/components/PaymentDialog.vue frontend/src/components/PaymentDialog.test.ts frontend/src/components/ReceiptDialog.vue frontend/src/components/ReceiptDialog.test.ts
git commit -m "完善发票复核历史和款项登记弹窗"
```

---

### Task 6: End-To-End Verification And Service Restart

**Files:**
- Modify only if build or tests expose defects in files changed by Tasks 1-5.

**Interfaces:**
- Consumes: all previous task outputs.
- Produces: rebuilt frontend and backend containers serving the updated workflow.

- [ ] **Step 1: Run backend focused tests**

```powershell
mvn -pl app "-Dtest=FinanceReviewApiTest,FinanceInvoiceApiTest,FinanceWorkbenchQueryApiTest,ProcurementWorkflowApiTest" test
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Run frontend focused tests**

```powershell
npm test -- src/components/FinanceReviewDialog.test.ts src/components/InvoiceDialog.test.ts src/components/PaymentDialog.test.ts src/components/ReceiptDialog.test.ts src/App.test.ts
```

Expected: all listed test files PASS.

- [ ] **Step 3: Build frontend**

```powershell
npm run build
```

Expected: Vite build completes successfully. Existing large chunk warning is acceptable.

- [ ] **Step 4: Package backend**

```powershell
mvn -pl app -DskipTests package
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Rebuild and restart services**

```powershell
docker compose up -d --build backend frontend
```

Expected: backend and frontend containers are recreated and started.

- [ ] **Step 6: Check service health**

```powershell
docker compose ps
try { (Invoke-WebRequest -UseBasicParsing http://localhost/).StatusCode } catch { $_.Exception.Message }
```

Expected: `gmt-inventory-backend-1` and `gmt-inventory-frontend-1` are `Up`; HTTP status is `200`.

- [ ] **Step 7: Manual browser smoke test**

Use the running app at `http://localhost/`:

1. Create or use a sales order.
2. Register a receipt amount.
3. Add an invoice later.
4. Open Finance Management review.
5. Confirm the receipt with a different confirmed amount.
6. Confirm the invoice with a different confirmed invoice number.
7. Verify official totals use confirmed values and history still shows original values.
8. Repeat for a purchase order with payment and purchase invoice.

- [ ] **Step 8: Commit verification fixes**

If this task required code fixes:

```powershell
git add <changed-files>
git commit -m "修复财务金额发票复核验证问题"
```

If no fixes were required, do not create an empty commit.

---

## Self-Review

- Spec coverage: covered money registration, later invoice supplement, finance confirmed money, finance confirmed invoice number, invoice review states, rejected record history, official total rules, and Docker restart.
- Placeholder scan: no incomplete placeholders or undefined implementation targets are present.
- Type consistency: `confirmedAmount`, `confirmedInvoiceNo`, `reviewStatus`, `reviewRemark`, `moneyRecords`, and `invoiceRecords` are consistent across backend API and frontend tasks.
