# 产品选择器标识顺序 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让订单和供应商产品选择器支持按产品编号、客户料号、型号搜索，并在搜索结果及已选产品信息中按“产品编号、客户料号、型号”顺序展示。

**Architecture:** 继续复用 `FuzzyPicker` 的文本标签与 `searchText` 过滤能力，不增加接口或数据库变更。订单与供应商组件各自生成统一的三行标识标签，现有 Teleport 下拉、键盘输入、滚动定位和选中逻辑保持不变。

**Tech Stack:** Vue 3、TypeScript、Vitest、Vue Test Utils、Vite。

## Global Constraints

- 产品核心标识固定顺序：产品编号、客户料号、型号。
- 搜索范围必须同时包含 `productCode`、`customerPartNumber`、`model`。
- 缺失标识显示 `—`。
- 不修改数据库、后端接口、产品唯一 ID 或导入流程。
- 使用现有 `FuzzyPicker`，不引入新依赖。

---

### Task 1: 订单产品搜索与已选信息

**Files:**
- Modify: `frontend/src/components/OrderDialog.test.ts`
- Modify: `frontend/src/components/OrderDialog.vue`

**Interfaces:**
- Consumes: `OrderSku` 的 `productCode`、`customerPartNumber`、`model` 字段和 `FuzzyPickerOption`。
- Produces: `skuLabel(sku: OrderSku): string` 三行标签；订单明细三个独立标识展示块。

- [ ] **Step 1: 写入失败测试**

在 `OrderDialog.test.ts` 增加测试，构造两个客户料号相同但产品编号不同的产品，断言产品编号搜索仅保留目标选项，并断言文本顺序：

```ts
it('searches and displays product identifiers with product code first', async () => {
  loadOrderSkus.mockResolvedValue([
    { ...sku(21), productCode: 'BR_D51-A', customerPartNumber: 'D1213K-D51', model: 'D51-GEN2' },
    { ...sku(22), productCode: 'BR_D51-B', customerPartNumber: 'D1213K-D51', model: 'D51-GEN2' }
  ])
  loadOrderCustomers.mockResolvedValue([])
  const wrapper = mount(OrderDialog, { attachTo: document.body, props: { defaultSalesperson: 'Admin' } })
  await flushPromises()
  await wrapper.get('[data-test="order-sku-picker-0"] input').setValue('BR_D51-B')
  const option = document.body.querySelector('[data-test="fuzzy-option-22"]')!
  expect(document.body.querySelector('[data-test="fuzzy-option-21"]')).toBeNull()
  expect(option.textContent).toMatch(/产品编号：BR_D51-B[\s\S]*客户料号：D1213K-D51[\s\S]*型号：D51-GEN2/)
  option.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flushPromises()
  expect(wrapper.get('[data-test="order-product-identifiers-0"]').text())
    .toMatch(/产品编号BR_D51-B[\s\S]*客户料号D1213K-D51[\s\S]*型号D51-GEN2/)
  wrapper.unmount()
})
```

- [ ] **Step 2: 运行测试并确认失败原因**

Run: `npm test -- OrderDialog.test.ts`

Expected: FAIL，因为当前下拉只显示客户料号，且订单明细不存在 `order-product-identifiers-0`。

- [ ] **Step 3: 实现最小订单改动**

在 `OrderDialog.vue` 将标签与搜索字段改为：

```ts
const skuOptions = computed<FuzzyPickerOption[]>(() => skus.value.map(sku => ({
  id: sku.id,
  label: skuLabel(sku),
  searchText: [sku.productCode, sku.customerPartNumber, sku.model].filter(Boolean).join(' ')
})))

function skuLabel(sku: OrderSku) {
  return [
    `产品编号：${String(sku.productCode ?? '').trim() || '—'}`,
    `客户料号：${String(sku.customerPartNumber ?? '').trim() || '—'}`,
    `型号：${String(sku.model ?? '').trim() || '—'}`
  ].join('\n')
}
```

将产品选择标签改为“产品”，占位文字保持包含三个搜索字段；在订单明细身份区域最前面加入：

```vue
<div :data-test="`order-product-identifiers-${index}`" class="order-line-product-identifiers">
  <div><span>产品编号</span><strong>{{ skuFor(line)?.productCode || '—' }}</strong></div>
  <div><span>客户料号</span><strong>{{ skuFor(line)?.customerPartNumber || '—' }}</strong></div>
  <div><span>型号</span><strong>{{ skuFor(line)?.model || '—' }}</strong></div>
</div>
```

- [ ] **Step 4: 运行订单测试**

Run: `npm test -- OrderDialog.test.ts OrderDialog.layout.test.ts OrderDialog.minimum-order.test.ts`

Expected: PASS，新增和既有订单弹窗测试全部通过。

---

### Task 2: 供应产品搜索与卡片标识顺序

**Files:**
- Modify: `frontend/src/components/SupplierDialog.test.ts`
- Modify: `frontend/src/components/SupplierDialog.vue`

**Interfaces:**
- Consumes: `OrderSku` 和供应商产品记录中的三个标识字段。
- Produces: `labelOf(sku: OrderSku): string` 三行标签；供应产品卡片按产品编号、客户料号、型号展示。

- [ ] **Step 1: 写入失败测试**

更新供应商建议测试和完整标识测试，使用顺序断言：

```ts
expect(matchingOption?.textContent)
  .toMatch(/产品编号：GMT-P90[\s\S]*客户料号：P90-001[\s\S]*型号：P90/)

const text = name.text()
expect(text.indexOf('GMT-VERY-LONG-P90')).toBeLessThan(text.indexOf('非常长的客户料号-P90-001'))
expect(text.indexOf('非常长的客户料号-P90-001')).toBeLessThan(text.indexOf('VERY-LONG-MODEL'))
```

- [ ] **Step 2: 运行测试并确认失败原因**

Run: `npm test -- SupplierDialog.test.ts`

Expected: FAIL，因为当前 `labelOf` 和卡片均把客户料号放在产品编号之前。

- [ ] **Step 3: 实现最小供应商改动**

在 `SupplierDialog.vue` 调整搜索顺序和标签：

```ts
const availableSkuOptions = computed<FuzzyPickerOption[]>(() => availableSkus.value.map(sku => ({
  id: sku.id,
  label: labelOf(sku),
  searchText: [sku.productCode, sku.customerPartNumber, sku.model].filter(Boolean).join(' ')
})))

function labelOf(sku: OrderSku) {
  return [
    `产品编号：${sku.productCode || '—'}`,
    `客户料号：${sku.customerPartNumber || '—'}`,
    `型号：${sku.model || '—'}`
  ].join('\n')
}
```

将 `.supplier-product-identity` 内三个 `<div>` 的模板顺序调整为产品编号、客户料号、型号。

- [ ] **Step 4: 运行供应商测试**

Run: `npm test -- SupplierDialog.test.ts`

Expected: PASS。

---

### Task 3: 样式、回归验证与部署

**Files:**
- Modify: `frontend/src/styles.css`（仅在现有换行样式不足时）

**Interfaces:**
- Consumes: 三行换行标签和现有 `.fuzzy-picker-options`。
- Produces: 可读的多行搜索结果，不遮挡弹窗内容。

- [ ] **Step 1: 如有必要增加最小样式**

确认现有按钮是否保留换行；若不足，仅加入：

```css
.fuzzy-picker-options button {
  white-space: pre-line;
  line-height: 1.45;
}
```

- [ ] **Step 2: 运行聚焦测试**

Run: `npm test -- OrderDialog.test.ts OrderDialog.layout.test.ts OrderDialog.minimum-order.test.ts SupplierDialog.test.ts`

Expected: 所有测试 PASS，0 failures。

- [ ] **Step 3: 运行生产构建**

Run: `npm run build`

Expected: exit code 0，Vite 输出 `built`。

- [ ] **Step 4: 检查差异并提交**

Run: `git diff -- frontend/src/components/OrderDialog.vue frontend/src/components/OrderDialog.test.ts frontend/src/components/SupplierDialog.vue frontend/src/components/SupplierDialog.test.ts frontend/src/styles.css`

仅暂存上述实际修改文件，提交信息：

```bash
git commit -m "前端：统一产品搜索和标识展示顺序"
```

- [ ] **Step 5: 部署前端并验证服务器**

将当前前端源码上传到 `/opt/stacks/gmt-inventory-system/frontend`，执行：

```bash
docker compose build frontend
docker compose up -d frontend
curl -fsS http://localhost:1234/api/system/health
docker compose ps
```

Expected: 前端容器为 `Up`；健康接口显示应用和数据库均正常。
