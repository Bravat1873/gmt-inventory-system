import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import FinanceReviewDialog from './FinanceReviewDialog.vue'

const { loadFinanceReviewSummary, reviewFinanceRecord, reviewFinanceInvoice } = vi.hoisted(() => ({
  loadFinanceReviewSummary: vi.fn(),
  reviewFinanceRecord: vi.fn(),
  reviewFinanceInvoice: vi.fn()
}))

vi.mock('../api/workbench', () => ({ loadFinanceReviewSummary, reviewFinanceRecord, reviewFinanceInvoice }))

it('shows review actions for pending records and completion text for reviewed records', async () => {
  loadFinanceReviewSummary.mockResolvedValue({
    confirmedMoneyAmount: 20,
    confirmedInvoiceAmount: 0,
    differenceAmount: 20,
    moneyRecords: [
      { id: 1, amount: 100, reviewStatus: 'PENDING', paymentMethod: '银行转账', occurredAt: '2026-08-25T10:00:00' },
      { id: 2, amount: 20, reviewStatus: 'APPROVED', paymentMethod: '银行转账', occurredAt: '2026-08-25T10:01:00' }
    ],
    invoiceRecords: []
  })
  const wrapper = mount(FinanceReviewDialog, { props: { type: 'PURCHASE', businessId: 1 } })
  await flushPromises()

  expect(wrapper.get('[data-test="money-approve-1"]').text()).toBe('通过')
  expect(wrapper.get('[data-test="money-reject-1"]').text()).toBe('驳回')
  expect(wrapper.get('[data-test="money-complete-2"]').text()).toBe('已通过，不可重复操作')
})

it('submits confirmed money amount without closing after rejection', async () => {
  loadFinanceReviewSummary.mockResolvedValue({
    confirmedMoneyAmount: 0,
    confirmedInvoiceAmount: 0,
    differenceAmount: 0,
    moneyRecords: [{ id: 11, amount: 260, confirmedAmount: null, paymentMethod: '银行转账', reviewStatus: 'PENDING' }],
    invoiceRecords: [{ id: 21, invoiceNo: 'RAW-1', taxInclusiveAmount: 260, confirmedInvoiceNo: null, confirmedAmount: null, reviewStatus: 'PENDING' }]
  })
  reviewFinanceRecord.mockResolvedValue({ reviewStatus: 'REJECTED' })
  const wrapper = mount(FinanceReviewDialog, { props: { type: 'PURCHASE', businessId: 8 } })
  await flushPromises()

  await wrapper.get('[data-test="money-confirmed-11"]').setValue('250')
  await wrapper.get('[data-test="money-reject-11"]').trigger('click')
  await flushPromises()

  expect(reviewFinanceRecord).toHaveBeenCalledWith('payments', 11, false, '', 250)
  expect(wrapper.emitted('saved')?.[0]).toEqual([false])
})

it('submits confirmed invoice number and amount', async () => {
  loadFinanceReviewSummary.mockResolvedValue({
    confirmedMoneyAmount: 0,
    confirmedInvoiceAmount: 0,
    differenceAmount: 0,
    moneyRecords: [],
    invoiceRecords: [{ id: 21, invoiceNo: 'RAW-1', taxInclusiveAmount: 260, reviewStatus: 'PENDING' }]
  })
  reviewFinanceInvoice.mockResolvedValue({ reviewStatus: 'APPROVED' })
  const wrapper = mount(FinanceReviewDialog, { props: { type: 'PURCHASE', businessId: 8 } })
  await flushPromises()

  await wrapper.get('[data-test="invoice-no-confirmed-21"]').setValue('FINAL-1')
  await wrapper.get('[data-test="invoice-confirmed-21"]').setValue('255')
  await wrapper.get('[data-test="invoice-approve-21"]').trigger('click')
  await flushPromises()

  expect(reviewFinanceInvoice).toHaveBeenCalledWith(21, true, {
    confirmedAmount: 255,
    confirmedInvoiceNo: 'FINAL-1',
    reviewRemark: undefined
  })
})

it('shows both original and confirmed invoice numbers for a reviewed invoice', async () => {
  loadFinanceReviewSummary.mockResolvedValue({
    confirmedMoneyAmount: 0,
    confirmedInvoiceAmount: 260,
    differenceAmount: -260,
    moneyRecords: [],
    invoiceRecords: [{
      id: 22,
      invoiceNo: 'RAW-001',
      confirmedInvoiceNo: 'FINAL-001',
      taxInclusiveAmount: 260,
      confirmedAmount: 260,
      reviewStatus: 'APPROVED'
    }]
  })
  const wrapper = mount(FinanceReviewDialog, { props: { type: 'PURCHASE', businessId: 8 } })
  await flushPromises()

  expect(wrapper.text()).toContain('RAW-001')
  expect(wrapper.text()).toContain('FINAL-001')
})
