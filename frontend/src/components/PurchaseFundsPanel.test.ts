import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import PurchaseFundsPanel from './PurchaseFundsPanel.vue'
import { loadModule, loadPurchase, loadFinanceRecords } from '../api/workbench'
vi.mock('../api/workbench', () => ({ loadModule: vi.fn(), loadPurchase: vi.fn(), loadFinanceRecords: vi.fn() }))
const row = { id: 20, businessNo: 'PO-20', counterparty: '供应商乙', amount: 100, settledAmount: 40, outstandingAmount: 60, pendingReviewCount: 1, status: '待复核' }
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(loadModule).mockResolvedValue({ items: [row], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  vi.mocked(loadPurchase).mockResolvedValue({ id: 20, purchaseNo: 'PO-20', supplierName: '供应商乙', items: [], totalAmount: 100, paidAmount: 40 })
  vi.mocked(loadFinanceRecords).mockResolvedValue([])
})
const open = (role: 'FINANCE' | 'USER' = 'FINANCE') => mount(PurchaseFundsPanel, { props: { currentUserRole: role }, global: { stubs: { PaymentDialog: { props: ['purchase'], template: '<div data-test="payment-dialog">{{ purchase.totalAmount - purchase.paidAmount }}<button @click="$emit(\'saved\')">保存</button></div>' }, FinanceReviewDialog: { props: ['type', 'businessId', 'canReviewInvoices'], template: '<div data-test="review-dialog">{{ type }} {{ businessId }}<button @click="$emit(\'saved\',false)">复核</button></div>' } } } })
it('loads shared purchase balances, searches and opens fresh payment data', async () => {
  const wrapper = open(); await flushPromises()
  expect(loadModule).toHaveBeenCalledWith('purchaseFunds', expect.any(URLSearchParams))
  expect(wrapper.get('[data-test="purchase-paid"]').text()).toContain('40.00')
  expect(wrapper.get('[data-test="purchase-unpaid"]').text()).toContain('60.00')
  await wrapper.get('[data-test="purchase-funds-keyword"]').setValue('PO-20')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(vi.mocked(loadModule).mock.lastCall?.[1].get('keyword')).toBe('PO-20')
  await wrapper.get('[data-test="fund-purchase-payment"]').trigger('click'); await flushPromises()
  expect(loadPurchase).toHaveBeenCalledWith(20)
  expect(wrapper.get('[data-test="payment-dialog"]').text()).toContain('60')
  await wrapper.get('[data-test="payment-dialog"] button').trigger('click'); await flushPromises()
  expect(wrapper.find('[data-test="payment-dialog"]').exists()).toBe(false)
  expect(loadModule).toHaveBeenCalledTimes(3)
})
it('reviews the same purchase and refreshes amounts after approval', async () => {
  const wrapper = open(); await flushPromises()
  await wrapper.get('[data-test="fund-purchase-review"]').trigger('click')
  expect(wrapper.get('[data-test="review-dialog"]').text()).toContain('PURCHASE 20')
  vi.mocked(loadModule).mockResolvedValueOnce({ items: [{ ...row, settledAmount: 70, outstandingAmount: 30 }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  await wrapper.get('[data-test="review-dialog"] button').trigger('click'); await flushPromises()
  expect(wrapper.get('[data-test="purchase-paid"]').text()).toContain('70.00')
  expect(wrapper.get('[data-test="purchase-unpaid"]').text()).toContain('30.00')
})
it('allows ordinary users to view records without finance controls', async () => {
  const wrapper = open('USER'); await flushPromises()
  expect(wrapper.find('[data-test="fund-purchase-payment"]').exists()).toBe(false)
  expect(wrapper.find('[data-test="fund-purchase-review"]').exists()).toBe(false)
  await wrapper.get('[data-test="fund-purchase-history"]').trigger('click'); await flushPromises()
  expect(loadFinanceRecords).toHaveBeenCalledWith('PURCHASE', 20)
  expect(wrapper.get('[role="dialog"]').text()).toContain('暂无付款记录')
})
