import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import OrderDialog from './OrderDialog.vue'

const api = vi.hoisted(() => ({
  createOrder: vi.fn(), updateOrder: vi.fn(), loadContractPrice: vi.fn(),
  loadOrderCustomers: vi.fn(), loadOrderSkus: vi.fn()
}))
vi.mock('../api/workbench', () => api)

it('shows the product sales minimum and blocks a smaller order quantity', async () => {
  api.loadOrderCustomers.mockResolvedValue([])
  api.loadOrderSkus.mockResolvedValue([{ id: 1, skuCode: 'SKU-1', productName: 'Product', unit: '件', salesMinimumOrderQuantity: 5, actualQuantity: 0, availableQuantity: 0, inTransitQuantity: 0, pendingDeliveryQuantity: 0, supplyDemandBalance: 0, purchaseShortageQuantity: 0 }])
  const wrapper = mount(OrderDialog, { props: { row: { customerId: 1, orderDate: '2026-08-13', orderType: '销售', salesperson: 'Admin', items: [{ skuId: 1, quantity: 4, salePrice: 10 }] } } })
  await flushPromises()

  expect(wrapper.get('[data-test="order-inventory-0"]').text()).toContain('销售最小起订量 5')
  await wrapper.get('form').trigger('submit')
  expect(wrapper.get('[data-test="quantity-error-0"]').text()).toContain('不能小于 5')
  expect(api.createOrder).not.toHaveBeenCalled()
  wrapper.unmount()
})
