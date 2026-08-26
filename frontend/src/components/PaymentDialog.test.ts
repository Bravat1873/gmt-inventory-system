import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import PaymentDialog from './PaymentDialog.vue'

const { postAction, loadFinanceRecords } = vi.hoisted(() => ({ postAction: vi.fn(), loadFinanceRecords: vi.fn().mockResolvedValue([]) }))
vi.mock('../api/workbench', () => ({ postAction, loadFinanceRecords }))

it('binds a supplier payment to the selected purchase without allowing a manual purchase number', () => {
  const wrapper = mount(PaymentDialog, {
    props: { purchase: { id: 8, purchaseNo: 'PO-SIM-001', totalAmount: 300, paidAmount: 40, outstandingAmount: 260, supplierName: '模拟供应商' } }
  })

  const purchaseNo = wrapper.get('[data-test="payment-purchase-no"]').element as HTMLInputElement
  expect(purchaseNo.value).toBe('PO-SIM-001')
  expect(purchaseNo.disabled).toBe(true)
  expect(wrapper.text()).toContain('¥ 40.00')
  expect((wrapper.get('[data-test="payment-amount"]').element as HTMLInputElement).value).toBe('260')
})

it('rejects zero-amount payment because invoices are maintained separately', async () => {
  postAction.mockResolvedValue({})
  const wrapper = mount(PaymentDialog, {
    props: { purchase: { id: 8, purchaseNo: 'CG001', totalAmount: 300, paidAmount: 300, outstandingAmount: 0, supplierName: '模拟供应商' } }
  })

  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(wrapper.text()).toContain('付款金额必须大于 0；发票请单独维护')
  expect(postAction).not.toHaveBeenCalled()
})

it('shows payment history and review status above the payment form', async () => {
  loadFinanceRecords.mockResolvedValue([{ id: 9, amount: 200, confirmedAmount: 180, paymentMethod: '银行转账', occurredAt: '2026-08-25T10:00:00', reviewStatus: 'REJECTED', reviewRemark: '金额待确认' }])
  const wrapper = mount(PaymentDialog, {
    props: { purchase: { id: 8, purchaseNo: 'CG001', totalAmount: 300, outstandingAmount: 300 } }
  })
  await flushPromises()

  expect(wrapper.findAll('.payment-content > *').at(0)?.classes()).toContain('fund-history')
  expect(wrapper.text()).toContain('已驳回')
  expect(wrapper.text()).toContain('金额待确认')
})
