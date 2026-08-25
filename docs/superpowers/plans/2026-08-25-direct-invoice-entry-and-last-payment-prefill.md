# 直接发票入口与最近登记金额预填 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在订单、采购和财务列表提供统一的“发票”入口，并用最近一笔收款或付款记录预填可编辑的发票金额。

**Architecture:** 列表组件负责展示精简后的操作文案和触发当前行的 `invoice` 事件。`App.vue` 根据来源模块标准化业务类型、业务单号与业务 ID；`InvoiceDialog.vue` 复用既有资金记录接口，以第一条记录的原始金额初始化新增发票表单。复核弹窗继续使用现有发票复核汇总接口，以回归测试锁定两个发票号码字段。

**Tech Stack:** Vue 3、TypeScript、Vitest、Vue Test Utils、Spring Boot 现有财务接口。

## Global Constraints

- 所有列表行内“维护发票”显示为“发票”。
- 不增加单号模糊搜索、独立发票模块、后端接口或数据库迁移。
- 销售订单预填最近一笔登记收款的原始 `amount`；采购单预填最近一笔登记付款的原始 `amount`。
- 默认金额可以在保存前修改；没有资金记录时保持为空。
- 只缩短列表操作按钮，弹窗标题、状态和错误消息保持完整语义。

---

### Task 1: 列表操作文案与直接发票入口

**Files:**
- Modify: `frontend/src/components/ModuleListPage.vue:5-145`
- Modify: `frontend/src/components/ModuleListPage.test.ts:35-220`
- Modify: `frontend/src/modules/module-config.ts:23-26`

**Interfaces:**
- Consumes: 现有 `invoice: [row: Record<string, unknown>]` 事件。
- Produces: 订单行、实际采购行和财务行都可发出 `invoice` 事件；采购建议行不能发出该事件。

- [ ] **Step 1: 写出失败的列表操作测试**

在 `ModuleListPage.test.ts` 添加覆盖订单、实际采购、财务和采购建议的测试：

```ts
it.each([
  ['order', { id: 10, status: 'READY_TO_SHIP' }],
  ['purchase', { id: 20, recordType: 'PURCHASE' }],
  ['finance', { id: 30, cashDirection: 'RECEIVABLE' }]
] as const)('shows 发票 for %s records', async (key, row) => {
  loadModule.mockResolvedValue({ items: [row], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === key)! } })
  await flushPromises()
  await wrapper.get('[data-test="invoice"]').trigger('click')
  expect(wrapper.get('[data-test="invoice"]').text()).toBe('发票')
  expect(wrapper.emitted('invoice')?.[0]?.[0]).toMatchObject(row)
})

it('does not show 发票 for a purchase suggestion', async () => {
  loadModule.mockResolvedValue({ items: [{ id: 21, recordType: 'SUGGESTION' }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'purchase')! } })
  await flushPromises()
  expect(wrapper.find('[data-test="invoice"]').exists()).toBe(false)
})
```

将现有采购操作测试预期改为 `单据`、`登记`、`收货`；再增加订单、客户、售后和财务行断言，覆盖 `资金`、`分配`、`复核`（没有括号数量）。

- [ ] **Step 2: 运行测试确认失败**

Run: `npm test -- src/components/ModuleListPage.test.ts`

Expected: FAIL，因为订单和采购尚无 `[data-test="invoice"]`，或按钮文字仍为旧文案。

- [ ] **Step 3: 实现最小列表改动**

在 `ModuleListPage.vue` 的行操作区域使用以下条件和文案：

```vue
<button v-if="module.key === 'order'" data-test="invoice" @click="emit('invoice', row)">发票</button>
<button v-if="module.key === 'purchase' && row.recordType === 'PURCHASE'" data-test="invoice" @click="emit('invoice', row)">发票</button>
<button v-if="module.key === 'finance'" data-test="invoice" @click="emit('invoice', row)">发票</button>
```

把现有列表按钮文字改为 `资金`、`单据`、`登记`、`分配`、`收货`、`复核`。在 `module-config.ts` 将三个模块的 `exportDocumentActionLabel` 改为 `单据`。调整订单与采购的 `actionColumnWidth`，使新增按钮不压缩已有操作。

- [ ] **Step 4: 运行测试确认通过**

Run: `npm test -- src/components/ModuleListPage.test.ts`

Expected: PASS，新增入口、采购建议限制和精简文案均通过。

- [ ] **Step 5: 提交本任务**

```powershell
git add frontend/src/components/ModuleListPage.vue frontend/src/components/ModuleListPage.test.ts frontend/src/modules/module-config.ts
git commit -m "新增订单采购发票入口并精简操作文案"
```

### Task 2: 标准化发票关联上下文并显示业务单号

**Files:**
- Modify: `frontend/src/App.vue:30-65,238-246,560-630`
- Modify: `frontend/src/App.test.ts:230-340`
- Modify: `frontend/src/components/InvoiceDialog.vue:1-47`
- Modify: `frontend/src/components/InvoiceDialog.test.ts:1-75`

**Interfaces:**
- Consumes: `ModuleListPage` 的 `invoice` 事件和行字段 `id`、`orderNo`、`purchaseNo`、`businessNo`、`cashDirection`。
- Produces: `InvoiceDialog` 新增 props `{ type: 'SALES' | 'PURCHASE'; businessId: number; businessNo: string }`，并显示关联业务类型和业务单号。

- [ ] **Step 1: 写出失败的应用与弹窗测试**

在 `App.test.ts` 添加：

```ts
it('opens an invoice dialog for an order with its sales association', async () => {
  const wrapper = mount(App)
  await flushPromises()
  wrapper.getComponent(ModuleListPage).vm.$emit('invoice', { id: 10, orderNo: 'DD20260800001' })
  await flushPromises()
  expect(wrapper.find('.invoice-dialog').text()).toContain('DD20260800001')
})

it('opens an invoice dialog for a purchase with its purchase association', async () => {
  history.replaceState(null, '', '/?module=purchase&page=1')
  const wrapper = mount(App)
  await flushPromises()
  wrapper.getComponent(ModuleListPage).vm.$emit('invoice', { id: 20, purchaseNo: 'CG20260800001', recordType: 'PURCHASE' })
  await flushPromises()
  expect(wrapper.find('.invoice-dialog').text()).toContain('CG20260800001')
})
```

在 `InvoiceDialog.test.ts` 的 mount props 加入 `businessNo`，并断言标题区域包含 `关联单号：DD20260800001`。

- [ ] **Step 2: 运行测试确认失败**

Run: `npm test -- src/App.test.ts src/components/InvoiceDialog.test.ts`

Expected: FAIL，`InvoiceDialog` 尚未接受或显示 `businessNo`，订单和采购来源尚未标准化类型。

- [ ] **Step 3: 实现关联上下文**

在 `App.vue` 新增 `invoiceContext`，由 `openInvoice(row)` 根据当前模块构造：订单固定 `SALES` 并取 `orderNo`；实际采购固定 `PURCHASE` 并取 `purchaseNo`；财务记录用现有 `financeType(row)` 并取 `businessNo`。模板向 `InvoiceDialog` 传递：

```vue
<InvoiceDialog
  v-if="invoiceContext"
  :type="invoiceContext.type"
  :business-id="invoiceContext.id"
  :business-no="invoiceContext.businessNo"
  @close="invoiceContext = undefined"
  @saved="list?.reload()"
  @message="showMessage"
/>
```

在 `InvoiceDialog.vue` 头部说明下新增：

```vue
<p class="invoice-association">关联{{ props.type === 'SALES' ? '销售订单' : '采购单' }}：{{ props.businessNo }}</p>
```

为空的单号使用 `—`，但不阻止根据业务 ID 保存发票。

- [ ] **Step 4: 运行测试确认通过**

Run: `npm test -- src/App.test.ts src/components/InvoiceDialog.test.ts`

Expected: PASS，订单、采购和财务入口使用正确的关联类型、ID 和单号。

- [ ] **Step 5: 提交本任务**

```powershell
git add frontend/src/App.vue frontend/src/App.test.ts frontend/src/components/InvoiceDialog.vue frontend/src/components/InvoiceDialog.test.ts
git commit -m "展示发票关联业务单号"
```

### Task 3: 预填最近一笔收付款金额并保护复核发票号码展示

**Files:**
- Modify: `frontend/src/components/InvoiceDialog.vue:1-24`
- Modify: `frontend/src/components/InvoiceDialog.test.ts:1-100`
- Modify: `frontend/src/components/FinanceReviewDialog.test.ts:30-90`

**Interfaces:**
- Consumes: `loadFinanceRecords(type, businessId): Promise<FinanceRecord[]>`，其返回按发生时间倒序排列。
- Produces: 新发票表单在首次加载、金额为空且存在资金记录时，填入 `records[0].amount`；人工修改的值原样提交至 `saveInvoice`。

- [ ] **Step 1: 写出失败的金额预填与复核展示测试**

更新 `InvoiceDialog` 的 API mock 以导出 `loadFinanceRecords`。添加：

```ts
it('prefills the original amount of the latest registered record and keeps it editable', async () => {
  loadInvoices.mockResolvedValue([])
  loadFinanceRecords.mockResolvedValue([
    { id: 22, amount: 200, occurredAt: '2026-08-25T10:00:00' },
    { id: 21, amount: 100, occurredAt: '2026-08-25T09:00:00' }
  ])
  const wrapper = mount(InvoiceDialog, { props: { type: 'PURCHASE', businessId: 8, businessNo: 'CG20260800001' } })
  await flushPromises()
  expect((wrapper.get('[data-test="invoice-amount"]').element as HTMLInputElement).value).toBe('200')
  await wrapper.get('[data-test="invoice-amount"]').setValue('180')
  await wrapper.get('[data-test="invoice-no"]').setValue('CG-F-01')
  await wrapper.get('form').trigger('submit.prevent')
  expect(saveInvoice).toHaveBeenCalledWith('PURCHASE', 8, expect.objectContaining({ taxInclusiveAmount: 180 }))
})

it('keeps the amount empty when no money record exists', async () => {
  loadInvoices.mockResolvedValue([])
  loadFinanceRecords.mockResolvedValue([])
  const wrapper = mount(InvoiceDialog, { props: { type: 'SALES', businessId: 9, businessNo: 'DD20260800001' } })
  await flushPromises()
  expect((wrapper.get('[data-test="invoice-amount"]').element as HTMLInputElement).value).toBe('')
})
```

在 `FinanceReviewDialog.test.ts` 添加已复核发票夹具，并断言表格同时显示 `RAW-001` 和 `FINAL-001`。

- [ ] **Step 2: 运行测试确认失败**

Run: `npm test -- src/components/InvoiceDialog.test.ts src/components/FinanceReviewDialog.test.ts`

Expected: FAIL，弹窗尚未读取资金记录并预填金额；复核展示若已通过，保留为现有行为回归验证。

- [ ] **Step 3: 实现最近登记金额预填**

在 `InvoiceDialog.vue` 从 `../api/workbench` 导入 `loadFinanceRecords` 和 `FinanceRecord`。将 `load()` 改为并行读取：

```ts
const [loadedInvoices, records] = await Promise.all([
  loadInvoices(props.type, props.businessId),
  loadFinanceRecords(props.type, props.businessId)
])
invoices.value = loadedInvoices
if (form.taxInclusiveAmount === '' && records[0]?.amount != null) {
  form.taxInclusiveAmount = String(records[0].amount)
}
```

保持 `save()` 使用 `Number(form.taxInclusiveAmount)`，不新增金额校验规则，确保人工编辑的输入值优先。不要改动 `FinanceReviewDialog.vue` 的列结构；只用回归测试锁定其已有的原号码和确认号码显示。

- [ ] **Step 4: 运行测试确认通过**

Run: `npm test -- src/components/InvoiceDialog.test.ts src/components/FinanceReviewDialog.test.ts`

Expected: PASS，最近记录金额预填、空记录和手工覆盖均通过，复核号码列保持可见。

- [ ] **Step 5: 提交本任务**

```powershell
git add frontend/src/components/InvoiceDialog.vue frontend/src/components/InvoiceDialog.test.ts frontend/src/components/FinanceReviewDialog.test.ts
git commit -m "预填最近收付款金额并覆盖发票复核测试"
```

### Task 4: 完整前端验证

**Files:**
- Verify: `frontend/src/components/ModuleListPage.test.ts`
- Verify: `frontend/src/components/InvoiceDialog.test.ts`
- Verify: `frontend/src/components/FinanceReviewDialog.test.ts`
- Verify: `frontend/src/App.test.ts`

- [ ] **Step 1: 运行聚焦测试套件**

Run: `npm test -- src/components/ModuleListPage.test.ts src/components/InvoiceDialog.test.ts src/components/FinanceReviewDialog.test.ts src/App.test.ts`

Expected: PASS，所有上述测试文件通过，零失败。

- [ ] **Step 2: 构建生产前端**

Run: `npm run build`

Expected: exit code 0；TypeScript 类型检查和 Vite 构建成功。

- [ ] **Step 3: 检查提交和工作区状态**

Run: `git log --oneline -3; git status --short`

Expected: 本功能有三个中文提交；不修改本功能之外的既有脏文件。
