import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import PaymentDialog from './PaymentDialog.vue'

const { postAction } = vi.hoisted(() => ({ postAction: vi.fn() }))
vi.mock('../api/workbench', () => ({ postAction }))

it('binds a supplier payment to the selected purchase without allowing a manual purchase number', () => {
  const wrapper = mount(PaymentDialog, {
    props: { purchase: { id: 8, purchaseNo: 'PO-SIM-001', totalAmount: 300, settledAmount: 0, outstandingAmount: 300, supplierName: '模拟供应商' } }
  })

  const purchaseNo = wrapper.get('[data-test="payment-purchase-no"]').element as HTMLInputElement
  expect(purchaseNo.value).toBe('PO-SIM-001')
  expect(purchaseNo.disabled).toBe(true)
  expect((wrapper.get('[data-test="payment-amount"]').element as HTMLInputElement).value).toBe('300')
})

it('allows a zero-amount payment when supplementing an invoice number', async () => {
  postAction.mockResolvedValue({})
  const wrapper = mount(PaymentDialog, {
    props: { purchase: { id: 8, purchaseNo: 'CG001', totalAmount: 300, settledAmount: 300, outstandingAmount: 0, supplierName: '模拟供应商' } }
  })

  await wrapper.get('[data-test="payment-invoice-no"]').setValue('CG-补录-01')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(postAction).toHaveBeenCalledWith('/api/procurement/purchases/8/payment', expect.objectContaining({ amount: 0, invoiceNo: 'CG-补录-01' }))
})
