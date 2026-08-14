import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import CustomerFundsDialog from './CustomerFundsDialog.vue'
import * as api from '../api/customer-funds'

vi.mock('../api/customer-funds', () => ({
  loadCustomerFundOverview: vi.fn().mockResolvedValue({ balance: 120, orderOutstandingAmount: 200, coverageRatio: 60, pendingAmount: 30, insufficient: true }),
  loadCustomerFundRequests: vi.fn().mockResolvedValue([{ id: 9, requestType: 'CUSTOMER_DEPOSIT', status: 'PENDING', amount: 30 }]),
  loadCustomerFundLedger: vi.fn().mockResolvedValue([]),
  loadCustomerFundSummary: vi.fn().mockResolvedValue([]),
  loadCustomerFundOrders: vi.fn().mockResolvedValue([{ id: 31, orderNo: 'SO-031', status: 'DRAFT', orderDate: '2026-08-14' }]),
  submitCustomerDeposit: vi.fn(),
  reviewCustomerFundRequest: vi.fn(),
  canReviewCustomerFunds: (role:string) => role === 'ADMIN' || role === 'FINANCE'
}))

it('shows balance warning without stretching the customer table and allows finance review', async () => {
  const wrapper = mount(CustomerFundsDialog, { props: { customer: { id: 7, customerName: '测试客户' }, currentUserRole: 'FINANCE' } })
  await flushPromises()
  expect(wrapper.text()).toContain('¥ 120.00')
  expect(wrapper.text()).toContain('余额不足')
  expect(wrapper.find('[data-test="fund-review-approve"]').exists()).toBe(true)
  await wrapper.get('[data-test="fund-review-approve"]').trigger('click')
  expect(api.reviewCustomerFundRequest).toHaveBeenCalledWith(9, true, '')
})
it('submits an optional selected customer order with the deposit', async () => {
  vi.mocked(api.submitCustomerDeposit).mockResolvedValueOnce(10)
  const wrapper = mount(CustomerFundsDialog, { props: { customer: { id: 7, customerName: '测试客户' }, currentUserRole: 'FINANCE' }, attachTo: document.body })
  await flushPromises()
  const picker = wrapper.get('[data-test="fund-order-picker"]')
  await picker.get('input').setValue('SO-031')
  document.body.querySelector<HTMLButtonElement>('[data-test="fuzzy-option-31"]')?.click()
  await wrapper.get('.fund-deposit input[type="number"]').setValue('100')
  await wrapper.get('.fund-deposit').trigger('submit')
  await flushPromises()
  expect(api.submitCustomerDeposit).toHaveBeenCalledWith(7, expect.objectContaining({ amount: 100, orderId: 31 }))
  wrapper.unmount()
})

it('allows a prepayment without selecting an order', async () => {
  vi.mocked(api.submitCustomerDeposit).mockClear()
  vi.mocked(api.submitCustomerDeposit).mockResolvedValueOnce(11)
  const wrapper = mount(CustomerFundsDialog, { props: { customer: { id: 7, customerName: '测试客户' }, currentUserRole: 'FINANCE' } })
  await flushPromises()
  await wrapper.get('.fund-deposit input[type="number"]').setValue('50')
  await wrapper.get('.fund-deposit').trigger('submit')
  await flushPromises()
  expect(api.submitCustomerDeposit).toHaveBeenCalledWith(7, expect.objectContaining({ amount: 50, orderId: undefined }))
})
