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
  const wrapper = mount(EntityDialog, { props: { module: 'product' } })
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
