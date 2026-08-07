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

it('allows typing to find a material when its code is blank', async () => {
  loadOrderSkus.mockResolvedValue([{ id: 8, skuCode: '', productName: 'Smart Lock', model: 'E71', configuration: 'Black', unit: 'PCS' }])
  loadOrderCustomers.mockResolvedValue([])
  const wrapper = mount(OrderDialog, { props: { defaultSalesperson: 'Admin' } })
  await flushPromises()

  const picker = wrapper.get('[data-test="order-sku-picker-0"]')
  await picker.get('input').setValue('E71')
  const matchingOption = document.body.querySelector('[data-test="fuzzy-option-8"]')
  expect(matchingOption?.textContent).toContain('E71')
  expect(matchingOption?.textContent).toContain('Smart Lock')
  wrapper.unmount()
})

it('fills customer details after choosing a typed customer suggestion', async () => {
  loadOrderSkus.mockResolvedValue([])
  loadOrderCustomers.mockResolvedValue([{ id: 9, customerName: '贝朗智能科技有限公司', contactName: '陈小姐', phone: '13800138000', address: '珠海市' }])
  const wrapper = mount(OrderDialog, { props: { defaultSalesperson: 'Admin' } })
  await flushPromises()

  const picker = wrapper.get('[data-test="order-customer-picker"]')
  await picker.get('input').setValue('贝朗')
  document.body.querySelector('[data-test="fuzzy-option-9"]')?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flushPromises()

  expect((wrapper.get('[data-test="order-customer-contact"]').element as HTMLInputElement).value).toBe('陈小姐')
  expect((wrapper.get('[data-test="order-customer-phone"]').element as HTMLInputElement).value).toBe('13800138000')
  wrapper.unmount()
})

it('puts all order-detail rows in one shared horizontal scroller', async () => {
  loadOrderSkus.mockResolvedValue([{ id: 1, skuCode: 'SKU-1', productName: 'Product', model: 'M1', configuration: 'Specification', unit: 'PCS' }])
  loadOrderCustomers.mockResolvedValue([])
  const wrapper = mount(OrderDialog, { props: { defaultSalesperson: 'Admin' } })
  await flushPromises()

  expect(wrapper.findAll('[data-test="order-lines-scroll"]')).toHaveLength(1)
  expect(wrapper.findAll('[data-test="order-line-scroll"]')).toHaveLength(0)
})
