import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import PurchaseReceiptDialog from './PurchaseReceiptDialog.vue'

const { receivePurchase } = vi.hoisted(() => ({ receivePurchase: vi.fn() }))
vi.mock('../api/workbench', () => ({ receivePurchase }))
beforeEach(() => receivePurchase.mockReset())

const purchase = {
  id: 8,
  purchaseNo: 'PO-008',
  supplierName: '供应商一',
  totalAmount: 150,
  items: [
    { id: 81, skuCode: 'P90', productName: '门锁', quantity: 10, receivedQuantity: 4, remainingQuantity: 6 },
    { id: 82, skuCode: 'D51', productName: '面板', quantity: 5, receivedQuantity: 0, remainingQuantity: 5 }
  ]
}

it('submits per-line receipt quantities including zero lines', async () => {
  receivePurchase.mockResolvedValue({})
  const wrapper = mount(PurchaseReceiptDialog, { props: { purchase } })
  const inputs = wrapper.findAll('[data-test="received-now"]')
  await inputs[0].setValue('3')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(receivePurchase).toHaveBeenCalledWith(8, [
    { purchaseOrderItemId: 81, receivedQuantity: 3 },
    { purchaseOrderItemId: 82, receivedQuantity: 0 }
  ])
  expect(wrapper.emitted('saved')).toBeTruthy()
})

it('rejects an all-zero or over-remaining receipt', async () => {
  const wrapper = mount(PurchaseReceiptDialog, { props: { purchase } })
  await wrapper.get('form').trigger('submit')
  expect(wrapper.text()).toContain('本次至少填写一项实收数量')

  await wrapper.findAll('[data-test="received-now"]')[0].setValue('7')
  await wrapper.get('form').trigger('submit')
  expect(wrapper.text()).toContain('本次实收数量不能超过剩余数量')
  expect(receivePurchase).not.toHaveBeenCalled()
})
