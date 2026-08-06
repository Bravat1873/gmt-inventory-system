import { mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import EntityDialog from './EntityDialog.vue'

vi.mock('../api/workbench', () => ({ createEntity: vi.fn(), updateEntity: vi.fn() }))

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
