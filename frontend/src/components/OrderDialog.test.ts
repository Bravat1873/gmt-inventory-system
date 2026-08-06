import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import OrderDialog from './OrderDialog.vue'

const { createOrder, loadOrderCustomers, loadOrderSkus } = vi.hoisted(() => ({
  createOrder: vi.fn(), loadOrderCustomers: vi.fn(), loadOrderSkus: vi.fn()
}))

vi.mock('../api/workbench', () => ({
  createOrder, updateOrder: vi.fn(), loadOrderCustomers, loadOrderSkus
}))

it('保存前明确提示缺少客户而不提交订单', async () => {
  loadOrderSkus.mockResolvedValue([{ id: 1, skuCode: 'SKU-1', productName: '产品', model: 'M1', configuration: '规格', unit: '套' }])
  loadOrderCustomers.mockResolvedValue([])
  const wrapper = mount(OrderDialog, { props: { defaultSalesperson: '管理员' } })
  await flushPromises()
  await wrapper.get('form').trigger('submit')

  expect(createOrder).not.toHaveBeenCalled()
  expect(wrapper.get('[data-test="customer-error"]').text()).toBe('请选择客户')
})

it('renders a readable selectable label when a material code is blank', async () => {
  loadOrderSkus.mockResolvedValue([{ id: 8, skuCode: '', productName: 'Smart Lock', model: 'E71', configuration: 'Black', unit: 'PCS' }])
  loadOrderCustomers.mockResolvedValue([])
  const wrapper = mount(OrderDialog, { props: { defaultSalesperson: 'Admin' } })
  await flushPromises()

  const option = wrapper.findAll('option').find(item => item.attributes('value') === '8')
  expect(option?.text()).toContain('E71')
  expect(option?.text()).toContain('Smart Lock')
})

it('puts all order-detail rows in one shared horizontal scroller', async () => {
  loadOrderSkus.mockResolvedValue([{ id: 1, skuCode: 'SKU-1', productName: 'Product', model: 'M1', configuration: 'Specification', unit: 'PCS' }])
  loadOrderCustomers.mockResolvedValue([])
  const wrapper = mount(OrderDialog, { props: { defaultSalesperson: 'Admin' } })
  await flushPromises()

  expect(wrapper.findAll('[data-test="order-lines-scroll"]')).toHaveLength(1)
  expect(wrapper.findAll('[data-test="order-line-scroll"]')).toHaveLength(0)
})
