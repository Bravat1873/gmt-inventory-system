import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import CustomerDialog from './CustomerDialog.vue'

const api = vi.hoisted(() => ({ getCustomer: vi.fn(), createCustomer: vi.fn(), updateCustomer: vi.fn(), loadOrderSkus: vi.fn() }))
vi.mock('../api/workbench', () => api)

it('maintains contract dates and per-product prices', async () => {
  api.loadOrderSkus.mockResolvedValue([{ id: 1, skuCode: 'SKU-1', productName: '产品一' }])
  api.createCustomer.mockResolvedValue({ id: 1 })
  const wrapper = mount(CustomerDialog, { attachTo: document.body })
  await flushPromises()

  await wrapper.get('[data-test="customer-name"]').setValue('合同客户')
  await wrapper.get('[data-test="add-contract"]').trigger('click')
  await wrapper.get('[data-test="contract-no-0"]').setValue('HT-2026')
  expect(wrapper.get('[data-test="contract-start-0"] [data-test="date-display"]').text()).toContain('请选择开始日期')
  expect(wrapper.get('[data-test="contract-end-0"] [data-test="date-display"]').text()).toContain('请选择结束日期')
  await wrapper.get('[data-test="contract-start-0"] input[type="date"]').setValue('2026-08-01')
  await wrapper.get('[data-test="contract-end-0"] input[type="date"]').setValue('2027-07-31')
  expect(wrapper.get('[data-test="contract-start-0"] [data-test="date-display"]').text()).toContain('2026年8月1日')
  expect((wrapper.get('[data-test="add-contract-product-0"]').element as HTMLButtonElement).disabled).toBe(true)
  const picker = wrapper.get('[data-test="contract-product-picker-0"]')
  await picker.get('input').setValue('SKU-1')
  document.body.querySelector<HTMLElement>('[data-test="fuzzy-option-1"]')?.click()
  await flushPromises()
  expect((wrapper.get('[data-test="add-contract-product-0"]').element as HTMLButtonElement).disabled).toBe(false)
  await wrapper.get('[data-test="add-contract-product-0"]').trigger('click')
  await wrapper.get('[data-test="contract-price-0-0"]').setValue('500')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.createCustomer).toHaveBeenCalledWith(expect.objectContaining({
    customerName: '合同客户',
    contracts: [expect.objectContaining({ contractNo: 'HT-2026', startDate: '2026-08-01', endDate: '2027-07-31', prices: [{ skuId: 1, salePrice: 500 }] })]
  }))
  wrapper.unmount()
})
