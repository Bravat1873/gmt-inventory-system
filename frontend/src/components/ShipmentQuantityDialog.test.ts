import { mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import ShipmentQuantityDialog from './ShipmentQuantityDialog.vue'

vi.mock('../api/workbench', () => ({ updateShipmentQuantities: vi.fn() }))

it('shows available inventory and calculates the remaining quantity per line', () => {
  const wrapper = mount(ShipmentQuantityDialog, {
    props: { order: { id: 1, orderNo: 'SO001', items: [{ lineNo: 10000, skuCode: 'SKU001', productName: '测试产品', quantity: 10, shippedQuantity: 3, availableQuantity: 7 }] } }
  })

  expect(wrapper.get('[data-test="available-quantity"]').text()).toContain('7')
  expect(wrapper.get('[data-test="remaining-quantity"]').text()).toContain('7')
  expect(wrapper.get('[data-test="shipment-status-dot"]').classes()).toContain('incomplete')
})

it('limits cumulative shipment quantity to the stock reserved for this order line', () => {
  const wrapper = mount(ShipmentQuantityDialog, {
    props: { order: { id: 1, orderNo: 'SO001', items: [{ lineNo: 10000, skuCode: 'SKU001', productName: '测试产品', quantity: 10, shippedQuantity: 3, availableQuantity: 0, lockedQuantity: 5 }] } }
  })

  expect(wrapper.get('[data-test="reserved-quantity"]').text()).toContain('5')
  expect((wrapper.get('[aria-label="已发货数量"]').element as HTMLInputElement).max).toBe('8')
})
