import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import SupplierDialog from './SupplierDialog.vue'

const api = vi.hoisted(() => ({
  loadOrderSkus: vi.fn().mockResolvedValue([{ id: 101, skuCode: 'P90-001', productName: 'P90 智能锁', model: 'P90', unit: '件' }]),
  createSupplier: vi.fn().mockResolvedValue({ id: 201 }),
  updateSupplier: vi.fn(),
  getSupplier: vi.fn()
}))

vi.mock('../api/workbench', () => api)

it('maintains supplier products without exposing supplier or product IDs in the form', async () => {
  const wrapper = mount(SupplierDialog)
  await flushPromises()
  await wrapper.get('[data-test="supplier-name"]').setValue('贝朗供应商')
  await wrapper.get('[data-test="supplier-product-picker"]').setValue('101')
  await wrapper.get('[data-test="add-supplier-product"]').trigger('click')

  expect(wrapper.text()).toContain('P90-001')
  expect(wrapper.text()).not.toContain('供应商 ID')
  expect(wrapper.text()).not.toContain('产品 ID')
})
