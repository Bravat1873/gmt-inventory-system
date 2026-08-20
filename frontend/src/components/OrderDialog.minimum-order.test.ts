import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import OrderDialog from './OrderDialog.vue'

const api = vi.hoisted(() => ({
  createOrder: vi.fn(), updateOrder: vi.fn(), loadContractPrice: vi.fn(),
  loadOrderCustomers: vi.fn(), loadOrderSkus: vi.fn()
}))
vi.mock('../api/workbench', () => api)

it('allows sales orders below a legacy product sales minimum quantity', async () => {
  api.loadOrderCustomers.mockResolvedValue([])
  api.loadOrderSkus.mockResolvedValue([{ id: 1, customerPartNumber: 'SKU-1', productName: 'Product', unit: '件', salesMinimumOrderQuantity: 5, actualQuantity: 0, availableQuantity: 0, inTransitQuantity: 0, pendingDeliveryQuantity: 0, supplyDemandSurplus: 0, purchaseShortageQuantity: 0 }])
  const wrapper = mount(OrderDialog, { props: { row: { customerId: 1, orderDate: '2026-08-13', orderType: '工程订单', salesperson: 'Admin', items: [{ skuId: 1, quantity: 4, salePrice: 10 }] } } })
  await flushPromises()

  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.get('[data-test="order-inventory-0"]').text()).not.toContain('销售最小起订量')
  expect(wrapper.find('[data-test="quantity-error-0"]').exists()).toBe(false)
  expect(api.createOrder).toHaveBeenCalledWith(expect.objectContaining({ items: [expect.objectContaining({ quantity: 4 })] }))
  wrapper.unmount()
})

