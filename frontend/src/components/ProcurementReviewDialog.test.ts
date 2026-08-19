import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import ProcurementReviewDialog from './ProcurementReviewDialog.vue'

const api = vi.hoisted(() => ({
  loadProcurementSuggestion: vi.fn(),
  updateProcurementSuggestion: vi.fn(),
  rejectProcurementSuggestion: vi.fn(),
  confirmProcurementSuggestion: vi.fn()
}))
vi.mock('../api/workbench', () => api)

const detail = {
  id: 7,
  suggestionNo: 'QR20260800001',
  status: 'DRAFT',
  version: 2,
  supplierId: 3,
  supplierName: '供应商甲',
  items: [{
    id: 71, skuId: 9, customerPartNumber: 'P50', productName: '智能锁', shortageQuantity: 6,
    minimumOrderQuantity: 10, suggestedQuantity: 10, purchasePrice: 20,
    estimatedAmount: 200, expectedArrivalDate: '2026-08-20', supplierPurchaseInfoId: 31
  }]
}

beforeEach(() => {
  Object.values(api).forEach(mock => mock.mockReset())
  api.loadProcurementSuggestion.mockResolvedValue(detail)
})

it('loads a QR suggestion and keeps the overlay non-dismissible', async () => {
  const wrapper = mount(ProcurementReviewDialog, { props: { suggestionId: 7 }, attachTo: document.body })
  await flushPromises()
  expect(api.loadProcurementSuggestion).toHaveBeenCalledWith(7)
  expect(wrapper.text()).toContain('QR20260800001')
  await wrapper.get('[data-test="procurement-review-mask"]').trigger('click')
  expect(wrapper.emitted('close')).toBeFalsy()
  wrapper.unmount()
})

it('blocks quantities below MOQ and confirms only after saving edits', async () => {
  api.updateProcurementSuggestion.mockResolvedValue({ id: 7, status: 'DRAFT', version: 3 })
  api.confirmProcurementSuggestion.mockResolvedValue({ purchaseNo: 'CG20260800001' })
  const wrapper = mount(ProcurementReviewDialog, { props: { suggestionId: 7 } })
  await flushPromises()
  await wrapper.get('[data-test="review-quantity-71"]').setValue('9')
  await wrapper.get('[data-test="confirm-procurement"]').trigger('click')
  expect(wrapper.text()).toContain('不能低于最小起购量 10')
  expect(api.confirmProcurementSuggestion).not.toHaveBeenCalled()

  await wrapper.get('[data-test="review-quantity-71"]').setValue('12')
  await wrapper.get('[data-test="confirm-procurement"]').trigger('click')
  await flushPromises()
  expect(api.updateProcurementSuggestion).toHaveBeenCalledWith(7, 2, [{ id: 71, quantity: 12, expectedArrivalDate: '2026-08-20' }])
  expect(api.confirmProcurementSuggestion).toHaveBeenCalledWith(7)
  expect(wrapper.emitted('saved')).toBeTruthy()
})

it('requires a rejection reason', async () => {
  const wrapper = mount(ProcurementReviewDialog, { props: { suggestionId: 7 } })
  await flushPromises()
  await wrapper.get('[data-test="reject-procurement"]').trigger('click')
  expect(wrapper.text()).toContain('请填写无需采购原因')
  expect(api.rejectProcurementSuggestion).not.toHaveBeenCalled()
})
