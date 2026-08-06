import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import ManualPurchaseDialog from './ManualPurchaseDialog.vue'

const api = vi.hoisted(() => ({
  createManualPurchase: vi.fn().mockResolvedValue({ purchaseNo: 'PO-001' }),
  loadSupplierOptions: vi.fn().mockResolvedValue([{ id: 201, supplierName: '贝朗供应商', contactName: '张三' }]),
  loadSupplierProducts: vi.fn().mockResolvedValue([
    { id: 101, skuCode: 'P90-001', productName: 'P90 智能锁', model: 'P90', unit: '件', purchasePrice: 220, moq: 5, leadTimeDays: 7 }
  ])
}))

vi.mock('../api/workbench', () => api)

it('selects a supplier and its configured product through fuzzy matching', async () => {
  const wrapper = mount(ManualPurchaseDialog)
  await flushPromises()

  await wrapper.get('[data-test="supplier-search"]').trigger('focus')
  await wrapper.get('[data-test="supplier-search"]').setValue('贝朗')
  await wrapper.get('[data-test="supplier-option-201"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-test="product-search"]').trigger('focus')
  await wrapper.get('[data-test="product-option-101"]').trigger('click')

  expect((wrapper.get('[data-test="purchase-price"]').element as HTMLInputElement).value).toBe('220')
  expect(wrapper.text()).toContain('最小起订量：5')
})
