import { mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import PaymentDialog from './PaymentDialog.vue'

vi.mock('../api/workbench', () => ({ postAction: vi.fn() }))

it('binds a supplier payment to the selected purchase without allowing a manual purchase number', () => {
  const wrapper = mount(PaymentDialog, {
    props: { purchase: { id: 8, purchaseNo: 'PO-SIM-001', totalAmount: 300, settledAmount: 0, outstandingAmount: 300, supplierName: '模拟供应商' } }
  })

  const purchaseNo = wrapper.get('[data-test="payment-purchase-no"]').element as HTMLInputElement
  expect(purchaseNo.value).toBe('PO-SIM-001')
  expect(purchaseNo.disabled).toBe(true)
  expect((wrapper.get('[data-test="payment-amount"]').element as HTMLInputElement).value).toBe('300')
})
