import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ReceiptDialog from './ReceiptDialog.vue'

const { postAction } = vi.hoisted(() => ({ postAction: vi.fn() }))
vi.mock('../api/workbench', () => ({ postAction }))

beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(new Date('2026-08-11T08:00:00+08:00')); postAction.mockReset().mockResolvedValue({}) })
afterEach(() => vi.useRealTimers())

it('uses the full order amount instead of the unshipped amount', () => {
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

  expect(wrapper.get('[data-test="receivable-amount"]').text()).toContain('150.00')
  expect(wrapper.get('[data-test="received-amount"]').text()).toContain('20.00')
  expect((wrapper.get('[data-test="receipt-amount"]').element as HTMLInputElement).disabled).toBe(false)
})

it('defaults the receipt date to today and allows submitting a future date', async () => {
  const wrapper = mount(ReceiptDialog, { props: { order: { id: 1, orderNo: 'SO001', receivedAmount: 0, items: [{ quantity: 2, shippedQuantity: 0, salePrice: 50 }] } } })
  const dateInput = wrapper.get('[data-test="receipt-date"] input[type="date"]')
  expect((dateInput.element as HTMLInputElement).value).toBe('2026-08-11')
  await dateInput.setValue('2026-09-20')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.find('[data-test="receipt-invoice-no"]').exists()).toBe(false)
  expect(postAction).toHaveBeenLastCalledWith('/api/finance/orders/1/receipt', expect.objectContaining({ receivedAt: '2026-09-20' }))
  expect(postAction.mock.calls.at(-1)?.[1]).not.toHaveProperty('invoiceNo')
})

it('keeps invoice-only supplement out of the receipt dialog after the order has been settled', async () => {
  const wrapper = mount(ReceiptDialog, { props: { order: { id: 1, orderNo: 'SO001', receivedAmount: 100, items: [{ quantity: 2, shippedQuantity: 0, salePrice: 50 }] } } })

  expect(wrapper.find('[data-test="receipt-invoice-no"]').exists()).toBe(false)
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(postAction).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('收款金额不能为 0')
})
