# 上下式资金登记与历史布局 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将发票、付款和收款登记弹窗改为历史在上、表单在下，并收紧所有列表的固定操作列。

**Architecture:** 发票弹窗只调整内容顺序。付款和收款弹窗使用已有 `loadFinanceRecords` 读取当前单据资金记录并在提交后刷新。列表通过 `ModuleListPage` 统一收紧操作列。

**Tech Stack:** Vue 3、TypeScript、Vitest、Vue Test Utils、CSS。

## Global Constraints

- 不改变付款、收款、发票保存接口和财务复核规则。
- 历史区显示待复核、已通过、已驳回及金额、方式、时间和备注。
- 发票预填金额继续取最近一笔收付款的原始金额，用户可覆盖。
- 操作列保持右侧固定和单行按钮，不为空按钮预留宽度。

### Task 1: 发票弹窗的上下式布局

**Files:** `frontend/src/components/InvoiceDialog.vue`, `frontend/src/components/InvoiceDialog.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
it('places invoice history before the new invoice form', async () => {
  loadInvoices.mockResolvedValue([{ id: 1, invoiceNo: 'FP-1', reviewStatus: 'REJECTED' }])
  const wrapper = mount(InvoiceDialog, { props: { type: 'PURCHASE', businessId: 8, businessNo: 'CG001' } })
  await flushPromises()
  expect(wrapper.findAll('.invoice-content > *').map(node => node.classes())).toEqual([
    expect.arrayContaining(['invoice-history']), expect.arrayContaining(['invoice-form'])
  ])
  expect(wrapper.text()).toContain('已驳回')
})
```

- [ ] **Step 2: Run `npm test -- src/components/InvoiceDialog.test.ts`; expect FAIL because the form precedes history.**
- [ ] **Step 3: Put `<section class="invoice-history">` before `<form class="invoice-form">`, set `.invoice-content{grid-template-columns:1fr}`, restrict the history table to `max-height:300px;overflow:auto`, and use a two-column form with the heading, remark and footer spanning both columns.**
- [ ] **Step 4: Run `npm test -- src/components/InvoiceDialog.test.ts`; expect PASS.**
- [ ] **Step 5: Commit `InvoiceDialog.vue` and `InvoiceDialog.test.ts` with `改为上下式发票登记界面`.**

### Task 2: 收付款弹窗顶部历史记录

**Files:** `frontend/src/components/PaymentDialog.vue`, `frontend/src/components/PaymentDialog.test.ts`, `frontend/src/components/ReceiptDialog.vue`, `frontend/src/components/ReceiptDialog.test.ts`

- [ ] **Step 1: Write the failing tests**

```ts
it('shows payment history and status above the payment form', async () => {
  loadFinanceRecords.mockResolvedValue([{ id: 9, amount: 200, confirmedAmount: 180, paymentMethod: '银行转账', occurredAt: '2026-08-25T10:00:00', reviewStatus: 'REJECTED', reviewRemark: '金额待确认' }])
  const wrapper = mount(PaymentDialog, { props: { purchase: { id: 8, purchaseNo: 'CG001', totalAmount: 300, outstandingAmount: 300 } } })
  await flushPromises()
  expect(wrapper.findAll('.payment-content > *').at(0)?.classes()).toContain('fund-history')
  expect(wrapper.text()).toContain('已驳回')
})
```

```ts
it('shows receipt history and status above the receipt form', async () => {
  loadFinanceRecords.mockResolvedValue([{ id: 9, amount: 100, paymentMethod: '现金', occurredAt: '2026-08-25T10:00:00', reviewStatus: 'PENDING' }])
  const wrapper = mount(ReceiptDialog, { props: { order: { id: 10, orderNo: 'DD001', receivableAmount: 200 } } })
  await flushPromises()
  expect(wrapper.findAll('.receipt-content > *').at(0)?.classes()).toContain('fund-history')
  expect(wrapper.text()).toContain('待复核')
})
```

- [ ] **Step 2: Run `npm test -- src/components/PaymentDialog.test.ts src/components/ReceiptDialog.test.ts`; expect FAIL because neither dialog renders `.fund-history`.**
- [ ] **Step 3: Import `onMounted`, `loadFinanceRecords`, and `FinanceRecord`; add `records`, `reloadHistory`, and status mapping. Use `loadFinanceRecords('PURCHASE', purchase.id)` for payment and `loadFinanceRecords('SALES', order.id)` for receipt. Render history columns `登记金额`、`确认金额`、`方式`、`登记时间`、`状态`、`备注` before the form. Call `await reloadHistory()` after `postAction` and before `emit('saved')`.**
- [ ] **Step 4: Add one-column `.payment-content` and `.receipt-content` containers plus a scrollable `.fund-history-table` capped at 260px; retain summary cards, validations, and submit labels.**
- [ ] **Step 5: Run `npm test -- src/components/PaymentDialog.test.ts src/components/ReceiptDialog.test.ts`; expect PASS.**
- [ ] **Step 6: Commit the four dialog files with `在收付款登记中展示复核历史`.**

### Task 3: 收紧共享列表操作列

**Files:** `frontend/src/components/ModuleListPage.vue`, `frontend/src/components/ModuleListPage.test.ts`, `frontend/src/styles.css`

- [ ] **Step 1: Write the failing test**

```ts
it.each([['order', 360], ['purchase', 330], ['finance', 290]] as const)('uses a compact %s action column', async (key, width) => {
  loadModule.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === key)! } })
  await flushPromises()
  expect(wrapper.findAll('col').at(-1)?.attributes('style')).toContain(`width: ${width}px`)
})
```

- [ ] **Step 2: Run `npm test -- src/components/ModuleListPage.test.ts`; expect FAIL because the widths are 520px, 540px, and 420px.**
- [ ] **Step 3: Return 360, 330, and 290 from `actionColumnWidth` for order, purchase, and finance. Keep `.action-column-header,.row-actions{position:sticky;right:0}`, set the header `z-index:3;text-align:right`, and keep `min-width:max-content` for button groups.**
- [ ] **Step 4: Run `npm test -- src/components/ModuleListPage.test.ts`; expect PASS.**
- [ ] **Step 5: Commit the list component, test, and CSS with `收紧列表操作列宽度`.**

### Task 4: Final verification and deployment

- [ ] **Step 1: Run `npm test -- src/components/InvoiceDialog.test.ts src/components/PaymentDialog.test.ts src/components/ReceiptDialog.test.ts src/components/ModuleListPage.test.ts src/App.test.ts`; expect PASS.**
- [ ] **Step 2: Run `npm run build`; expect Vite `built in` (existing chunk-size warnings are non-blocking).**
- [ ] **Step 3: Run `docker compose up -d --build frontend`; then verify `docker compose ps` reports frontend `Up` and `Invoke-WebRequest http://localhost/` returns `200`.**
