import { mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import ReceiptDialog from './ReceiptDialog.vue'

vi.mock('../api/workbench', () => ({ postAction: vi.fn() }))

it('shows receivable, received and editable receipt amount from order lines', () => {
  const wrapper = mount(ReceiptDialog, {
    props: {
      order: {
        id: 1,
        orderNo: 'SO001',
        receivedAmount: 20,
        items: [{ quantity: 3, shippedQuantity: 1, salePrice: 50 }]
      }
    }
  })

  expect(wrapper.get('[data-test="receivable-amount"]').text()).toContain('100.00')
  expect(wrapper.get('[data-test="received-amount"]').text()).toContain('20.00')
  expect((wrapper.get('[data-test="receipt-amount"]').element as HTMLInputElement).disabled).toBe(false)
})
