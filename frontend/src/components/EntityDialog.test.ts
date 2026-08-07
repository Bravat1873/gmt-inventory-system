import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import EntityDialog from './EntityDialog.vue'

const api = vi.hoisted(() => ({ createEntity: vi.fn(), updateEntity: vi.fn(), loadOrderSkus: vi.fn() }))

vi.mock('../api/workbench', () => api)

it('includes the loaded version when saving an existing record', async () => {
  api.updateEntity.mockResolvedValue({ id: 7, version: 4 })
  const wrapper = mount(EntityDialog, {
    props: { module: 'customer', row: { id: 7, customerName: 'Customer A', version: 3 } }
  })

  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.updateEntity).toHaveBeenCalledWith('customer', 7, expect.objectContaining({ customerName: 'Customer A', version: 3 }))
})

it('产品弹窗只显示产品列表字段并自动计算价格差异', async () => {
  const wrapper = mount(EntityDialog, { props: { module: 'product', currentUserRole: 'FINANCE' } })
  expect(wrapper.text()).not.toContain('供应商编号')
  expect(wrapper.text()).not.toContain('采购单价')
  expect(wrapper.text()).not.toContain('最小起订量')
  expect(wrapper.text()).not.toContain('交货天数')
  expect(wrapper.text()).toContain('差异：转厂价-原成本')

  const inputs=wrapper.findAll('input')
  await inputs.find(input=>input.attributes('type')==='number' && input.element.parentElement?.textContent?.includes('成本单价'))!.setValue('100')
  await inputs.find(input=>input.attributes('type')==='number' && input.element.parentElement?.textContent?.includes('转厂价格'))!.setValue('135')
  expect(wrapper.get('[data-test="price-difference"]').attributes('value')).toBe('35')
})

it('allows finance users to edit and submit both product prices', async () => {
  api.createEntity.mockResolvedValue({ id: 1 })
  const wrapper = mount(EntityDialog, {
    props: { module: 'product', currentUserRole: 'FINANCE' }
  })

  const currentCost = wrapper.get('[data-test="product-current-cost"]')
  const factoryPrice = wrapper.get('[data-test="product-factory-price"]')
  expect(currentCost.attributes('disabled')).toBeUndefined()
  expect(factoryPrice.attributes('disabled')).toBeUndefined()

  await currentCost.setValue('100')
  await factoryPrice.setValue('135')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.createEntity).toHaveBeenLastCalledWith('product', expect.objectContaining({ currentCost: 100, factoryPrice: 135 }))
})

it('shows product prices read-only to ordinary users and omits them from the payload', async () => {
  api.updateEntity.mockResolvedValue({ id: 7, version: 4 })
  const wrapper = mount(EntityDialog, {
    props: {
      module: 'product',
      currentUserRole: 'USER',
      row: { id: 7, model: 'P90', currentCost: 100, factoryPrice: 135, version: 3 }
    }
  })

  const currentCost = wrapper.get('[data-test="product-current-cost"]')
  const factoryPrice = wrapper.get('[data-test="product-factory-price"]')
  expect((currentCost.element as HTMLInputElement).value).toBe('100')
  expect((factoryPrice.element as HTMLInputElement).value).toBe('135')
  expect(currentCost.attributes('disabled')).toBeDefined()
  expect(factoryPrice.attributes('disabled')).toBeDefined()
  expect(wrapper.get('[data-test="product-price-permission-hint"]').text()).toBe('仅财务或管理员可修改')

  await wrapper.get('form').trigger('submit')
  await flushPromises()

  const payload = api.updateEntity.mock.calls.at(-1)?.[2]
  expect(payload).not.toHaveProperty('currentCost')
  expect(payload).not.toHaveProperty('factoryPrice')
})

it('allows administrators to select and submit a user role with Chinese labels', async () => {
  api.updateEntity.mockResolvedValue({ id: 7, version: 4 })
  const wrapper = mount(EntityDialog, {
    props: {
      module: 'user',
      currentUserRole: 'ADMIN',
      row: { id: 7, username: 'finance', displayName: '财务员', role: 'USER', version: 3 }
    }
  })

  const role = wrapper.get('[data-test="user-role"]')
  expect(role.attributes('disabled')).toBeUndefined()
  expect(role.text()).toContain('管理员')
  expect(role.text()).toContain('财务')
  expect(role.text()).toContain('普通用户')
  await role.setValue('FINANCE')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.updateEntity).toHaveBeenLastCalledWith('user', 7, expect.objectContaining({ role: 'FINANCE' }))
})

it('shows but does not submit the role control for non-administrators', async () => {
  api.updateEntity.mockResolvedValue({ id: 7, version: 4 })
  const wrapper = mount(EntityDialog, {
    props: {
      module: 'user',
      currentUserRole: 'FINANCE',
      row: { id: 7, username: 'finance', displayName: '财务员', role: 'FINANCE', version: 3 }
    }
  })

  const role = wrapper.get('[data-test="user-role"]')
  expect(role.attributes('disabled')).toBeDefined()
  expect((role.element as HTMLSelectElement).value).toBe('FINANCE')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.updateEntity.mock.calls.at(-1)?.[2]).not.toHaveProperty('role')
})

it('lets the backend apply the USER default when an administrator does not select a role', async () => {
  api.createEntity.mockResolvedValue({ id: 8 })
  const wrapper = mount(EntityDialog, {
    props: { module: 'user', currentUserRole: 'ADMIN' }
  })

  expect((wrapper.get('[data-test="user-role"]').element as HTMLSelectElement).value).toBe('')
  await wrapper.get('[data-test="user-username"]').setValue('new-user')
  await wrapper.get('[data-test="user-display-name"]').setValue('新用户')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.createEntity.mock.calls.at(-1)?.[1]).not.toHaveProperty('role')
})

it('库存弹窗使用页面库存字段而不暴露内部调整原因', () => {
  const wrapper = mount(EntityDialog, { props: { module: 'inventory' } })

  expect(wrapper.text()).toContain('物料编号 SKU')
  expect(wrapper.text()).toContain('实际库存数量')
  expect(wrapper.text()).toContain('可用库存数量')
  expect(wrapper.text()).toContain('已锁定数量')
  expect(wrapper.text()).not.toContain('入/出库摘要')
  expect(wrapper.get('[data-test="add-inventory-movement"]').text()).toContain('新增明细')
  expect(wrapper.text()).toContain('供应商')
  expect(wrapper.text()).toContain('备注')
  expect(wrapper.text()).not.toContain('调整原因')
})

it('新增库存允许编辑产品字段与库存汇总字段，并将选择的产品编号提交保存', async () => {
  api.loadOrderSkus.mockResolvedValue([
    {
      id: 18,
      skuCode: 'SKU-P90',
      productName: 'P90 智能锁',
      model: 'P90',
      configuration: '可视对讲',
      productVersion: '工程款',
      color: '宇宙黑',
      lockBody: '6068',
      unit: '套'
    }
  ])
  api.createEntity.mockResolvedValue({ id: 1 })
  const wrapper = mount(EntityDialog, { attachTo: document.body, props: { module: 'inventory' } })
  await flushPromises()

  const picker = wrapper.get('[data-test="inventory-product-picker"]')
  await picker.get('input').setValue('P90')
  const option = document.body.querySelector<HTMLElement>('[data-test="fuzzy-option-18"]')
  expect(option?.textContent).toContain('P90')
  option?.click()
  await flushPromises()

  const editable = ['inventory-model', 'inventory-configuration', 'inventory-color', 'inventory-lock-body', 'inventory-unit', 'inventory-actual-quantity', 'inventory-available-quantity', 'inventory-locked-quantity']
  editable.forEach(testId => expect(wrapper.get(`[data-test="${testId}"]`).attributes('disabled')).toBeUndefined())
  expect((wrapper.get('[data-test="inventory-model"]').element as HTMLInputElement).value).toBe('P90')
  expect((wrapper.get('[data-test="inventory-unit"]').element as HTMLInputElement).value).toBe('套')

  await wrapper.get('[data-test="inventory-available-quantity"]').setValue('12')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.createEntity).toHaveBeenCalledWith('inventory', expect.objectContaining({
    skuId: 18,
    skuCode: 'SKU-P90',
    model: 'P90',
    availableQuantity: 12
  }))
  wrapper.unmount()
})
