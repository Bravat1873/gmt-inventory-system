import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import ManualPurchaseDialog from './ManualPurchaseDialog.vue'

const api = vi.hoisted(() => ({
  createManualPurchase: vi.fn().mockResolvedValue({ purchaseNo: 'PO-001' }),
  loadOrderSkus: vi.fn().mockResolvedValue([
    { id: 101, skuCode: 'P90-001', productName: 'P90 智能锁', model: 'P90', unit: '件' },
    { id: 102, skuCode: 'P50-001', productName: 'P50 智能锁', model: 'P50', unit: '件' }
  ]),
  loadProductSuppliers: vi.fn().mockResolvedValue([
    { supplierId: 201, supplierName: '贝朗供应商', purchasePrice: 220, moq: 5, leadTimeDays: 7 }
  ])
}))

vi.mock('../api/workbench', () => api)

it('selects a product first and then its configured supplier', async () => {
  const wrapper = mount(ManualPurchaseDialog)
  await flushPromises()

  const productSearch = wrapper.get('[data-test="product-search"]')
  const supplierSearch = wrapper.get('[data-test="supplier-search"]')
  expect(productSearch.element.compareDocumentPosition(supplierSearch.element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(supplierSearch.attributes('disabled')).toBeDefined()

  await productSearch.trigger('focus')
  await wrapper.get('[data-test="product-option-101"]').trigger('click')
  await flushPromises()
  expect(api.loadProductSuppliers).toHaveBeenCalledWith(101, '')
  expect(supplierSearch.attributes('disabled')).toBeUndefined()

  await supplierSearch.trigger('focus')
  await wrapper.get('[data-test="supplier-option-201"]').trigger('click')
  expect((wrapper.get('[data-test="purchase-price"]').element as HTMLInputElement).value).toBe('220')
  expect(wrapper.text()).toContain('最小起订量：5')

  await productSearch.trigger('focus')
  await productSearch.setValue('P50')
  await wrapper.get('[data-test="product-option-102"]').trigger('click')
  await flushPromises()
  expect((supplierSearch.element as HTMLInputElement).value).toBe('')
  expect((wrapper.get('[data-test="purchase-price"]').element as HTMLInputElement).value).toBe('')
})