# 紧凑产品选择与页面去重 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让所有产品搜索框展开时显示完整三项标识、选中后仅显示产品编号，并从订单明细隐藏重复的产品名称。

**Architecture:** 扩展通用 `FuzzyPickerOption`，分离候选标签 `label` 与选中标签 `selectedLabel`，由各产品页面提供统一映射。手工采购保留自有搜索控件，但把候选文本和回填文本拆成两个函数；不修改接口、数据库或产品唯一 ID。

**Tech Stack:** Vue 3、TypeScript、Vitest、Vue Test Utils、Vite、Docker Compose。

## Global Constraints

- 产品候选列表固定按产品编号、客户料号、型号展示。
- 产品搜索范围同时包含产品编号、客户料号、型号。
- 产品选中后输入框仅显示产品编号。
- 缺失产品编号时显示 `未设置产品编号`，不回退为产品名称。
- 订单明细隐藏产品名称，保留产品编号、客户料号、型号、型号/规格和单位。
- 不修改数据库、后端接口、产品唯一 ID 或导入流程。
- 保留非产品选择器的现有显示行为。

---

### Task 1: 通用选择器支持独立选中标签

**Files:**
- Modify: `frontend/src/components/FuzzyPicker.vue`
- Test: `frontend/src/components/FuzzyPicker.scroll.test.ts`

**Interfaces:**
- Consumes: `FuzzyPickerOption` 的 `id`、`label`、`searchText`。
- Produces: 可选字段 `selectedLabel?: string`；候选列表继续显示 `label`，选中输入框显示 `selectedLabel ?? label`。

- [ ] **Step 1: 写入失败测试**

在 `FuzzyPicker.scroll.test.ts` 增加：

```ts
it('uses a compact selected label without changing the option label', async () => {
  const wrapper = mount(FuzzyPicker, {
    attachTo: document.body,
    props: {
      modelValue: null,
      options: [{
        id: 7,
        label: '产品编号：BR_A71\n客户料号：G8A71HS001\n型号：A71',
        selectedLabel: 'BR_A71',
        searchText: 'BR_A71 G8A71HS001 A71'
      }]
    }
  })
  await wrapper.get('input').trigger('focus')
  expect(document.body.querySelector('[data-test="fuzzy-option-7"]')?.textContent).toContain('客户料号：G8A71HS001')
  document.body.querySelector<HTMLElement>('[data-test="fuzzy-option-7"]')?.click()
  await nextTick()
  expect((wrapper.get('input').element as HTMLInputElement).value).toBe('BR_A71')
  wrapper.unmount()
})
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `npm test -- FuzzyPicker.scroll.test.ts`

Expected: TypeScript 或断言失败，因为 `FuzzyPickerOption` 尚无 `selectedLabel`，输入框仍使用完整 `label`。

- [ ] **Step 3: 实现最小组件改动**

将接口扩展为：

```ts
export interface FuzzyPickerOption {
  id: number
  label: string
  selectedLabel?: string
  searchText?: string
}
```

选中项显示值改为：

```ts
const selectedText = computed(() => {
  const option = props.options.find(item => item.id === props.modelValue)
  return option?.selectedLabel ?? option?.label ?? ''
})
```

候选过滤和候选按钮文本继续使用 `option.label` 与 `option.searchText`。

- [ ] **Step 4: 验证组件测试**

Run: `npm test -- FuzzyPicker.scroll.test.ts`

Expected: PASS。

---

### Task 2: FuzzyPicker 产品入口统一精简选中态

**Files:**
- Modify: `frontend/src/components/OrderDialog.vue`
- Modify: `frontend/src/components/SupplierDialog.vue`
- Modify: `frontend/src/components/CustomerDialog.vue`
- Modify: `frontend/src/components/EntityDialog.vue`
- Test: `frontend/src/components/OrderDialog.test.ts`
- Test: `frontend/src/components/SupplierDialog.test.ts`
- Test: `frontend/src/components/CustomerDialog.test.ts`
- Test: `frontend/src/components/EntityDialog.test.ts`

**Interfaces:**
- Consumes: Task 1 的 `FuzzyPickerOption.selectedLabel?: string`。
- Produces: 四个页面的产品候选完整标签和仅含产品编号的选中标签。

- [ ] **Step 1: 更新四个页面的失败测试**

每个页面选择产品后断言输入框：

```ts
expect((wrapper.get('[data-test="order-sku-picker-0"] input').element as HTMLInputElement).value).toBe('BR_D51-B')
expect((wrapper.get('[data-test="supplier-product-picker"] input').element as HTMLInputElement).value).toBe('GMT-P90')
expect((wrapper.get('[data-test="contract-product-picker-0"] input').element as HTMLInputElement).value).toBe('BR_C51')
expect((wrapper.get('[data-test="inventory-product-picker"] input').element as HTMLInputElement).value).toBe('BR_D51')
```

候选列表原有三项顺序断言必须保留。

- [ ] **Step 2: 运行测试并确认失败**

Run: `npm test -- OrderDialog.test.ts SupplierDialog.test.ts CustomerDialog.test.ts EntityDialog.test.ts`

Expected: FAIL，选中输入框仍显示三项完整文本。

- [ ] **Step 3: 为四类产品选项增加 `selectedLabel`**

统一使用：

```ts
selectedLabel: String(sku.productCode ?? '').trim() || '未设置产品编号'
```

`label` 保持：

```ts
[
  `产品编号：${sku.productCode || '—'}`,
  `客户料号：${sku.customerPartNumber || '—'}`,
  `型号：${sku.model || '—'}`
].join('\n')
```

`searchText` 保持：

```ts
[sku.productCode, sku.customerPartNumber, sku.model].filter(Boolean).join(' ')
```

- [ ] **Step 4: 验证四个页面测试**

Run: `npm test -- OrderDialog.test.ts SupplierDialog.test.ts CustomerDialog.test.ts EntityDialog.test.ts`

Expected: PASS。

---

### Task 3: 订单页面去重与手工采购精简

**Files:**
- Modify: `frontend/src/components/OrderDialog.vue`
- Modify: `frontend/src/components/ManualPurchaseDialog.vue`
- Modify: `frontend/src/styles.css`
- Test: `frontend/src/components/OrderDialog.test.ts`
- Test: `frontend/src/components/ManualPurchaseDialog.test.ts`

**Interfaces:**
- Consumes: `OrderSku.productCode`、`customerPartNumber`、`model`、`configuration`、`unit`。
- Produces: 无重复产品名称的订单身份区；手工采购候选详细、选中精简。

- [ ] **Step 1: 写入订单去重失败测试**

选择产品后断言：

```ts
const identity = wrapper.get('[data-test="order-product-identifiers-0"]')
expect(identity.text()).toContain('型号A71')
expect(identity.text()).toContain('型号 / 规格')
expect(identity.text()).not.toContain('产品名称')
```

- [ ] **Step 2: 写入手工采购精简失败测试**

候选列表保留三项断言，并在点击后增加：

```ts
await wrapper.get('[data-test="product-option-201"]').trigger('click')
expect((wrapper.get('[data-test="product-search"]').element as HTMLInputElement).value).toBe('BR_P90')
```

- [ ] **Step 3: 运行测试并确认失败**

Run: `npm test -- OrderDialog.test.ts ManualPurchaseDialog.test.ts`

Expected: FAIL，因为订单仍显示产品名称，手工采购回填完整候选标签。

- [ ] **Step 4: 删除订单产品名称展示**

从 `.order-line-identity` 删除：

```vue
<div><span>产品名称</span><strong>{{ skuFor(line)?.productName || '—' }}</strong></div>
```

保留现有产品编号、客户料号、型号、型号/规格和单位字段，并将桌面布局改成自适应：

```css
.order-line-identity {
  grid-template-columns:repeat(auto-fit,minmax(150px,1fr));
}
```

- [ ] **Step 5: 分离手工采购候选与选中标签**

```ts
function productOptionLabel(product: OrderSku) {
  return [
    `产品编号：${product.productCode || '—'}`,
    `客户料号：${product.customerPartNumber || '—'}`,
    `型号：${product.model || '—'}`
  ].join('\n')
}

function productSelectedLabel(product: OrderSku) {
  return String(product.productCode ?? '').trim() || '未设置产品编号'
}
```

候选按钮使用 `productOptionLabel(product)`，`selectProduct` 设置：

```ts
productQuery.value = productSelectedLabel(product)
```

`.choice-options strong` 保持 `white-space: pre-line`。

- [ ] **Step 6: 验证订单和采购测试**

Run: `npm test -- OrderDialog.test.ts OrderDialog.layout.test.ts OrderDialog.minimum-order.test.ts ManualPurchaseDialog.test.ts`

Expected: PASS。

---

### Task 4: 全量回归、构建与服务器部署

**Files:**
- Verify: `frontend/src/components/FuzzyPicker.vue`
- Verify: `frontend/src/components/OrderDialog.vue`
- Verify: `frontend/src/components/SupplierDialog.vue`
- Verify: `frontend/src/components/CustomerDialog.vue`
- Verify: `frontend/src/components/EntityDialog.vue`
- Verify: `frontend/src/components/ManualPurchaseDialog.vue`
- Verify: `frontend/src/styles.css`

**Interfaces:**
- Consumes: Tasks 1–3 的最终前端行为。
- Produces: 已测试、已构建并部署的前端版本。

- [ ] **Step 1: 运行相关测试**

Run:

```bash
npm test -- FuzzyPicker.scroll.test.ts OrderDialog.test.ts OrderDialog.layout.test.ts OrderDialog.minimum-order.test.ts SupplierDialog.test.ts CustomerDialog.test.ts ManualPurchaseDialog.test.ts EntityDialog.test.ts
```

Expected: 所有测试 PASS，0 failures。

- [ ] **Step 2: 运行生产构建**

Run: `npm run build`

Expected: exit code 0，Vite 输出 `built`。

- [ ] **Step 3: 检查本次差异**

Run:

```bash
git diff -- frontend/src/components/FuzzyPicker.vue frontend/src/components/FuzzyPicker.scroll.test.ts frontend/src/components/OrderDialog.vue frontend/src/components/OrderDialog.test.ts frontend/src/components/SupplierDialog.vue frontend/src/components/SupplierDialog.test.ts frontend/src/components/CustomerDialog.vue frontend/src/components/CustomerDialog.test.ts frontend/src/components/EntityDialog.vue frontend/src/components/EntityDialog.test.ts frontend/src/components/ManualPurchaseDialog.vue frontend/src/components/ManualPurchaseDialog.test.ts frontend/src/styles.css
```

确认没有覆盖工作区中其他既有改动；仅暂存能够与既有改动安全分离的文件或补丁块。

- [ ] **Step 4: 部署前端**

将当前 `frontend` 源码上传到 `/opt/stacks/gmt-inventory-system/frontend`，排除 `node_modules` 和 `dist`，然后运行：

```bash
cd /opt/stacks/gmt-inventory-system
docker compose build frontend
docker compose up -d frontend
```

- [ ] **Step 5: 验证服务器**

Run:

```bash
curl -fsS http://localhost:1234/api/system/health
docker compose ps
```

Expected: 健康接口返回应用和数据库正常；frontend、backend、mysql 容器均为 `Up`。
