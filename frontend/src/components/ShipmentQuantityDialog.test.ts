import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import ShipmentQuantityDialog from './ShipmentQuantityDialog.vue'

const { updateShipmentQuantities } = vi.hoisted(() => ({ updateShipmentQuantities: vi.fn() }))
vi.mock('../api/workbench', () => ({ updateShipmentQuantities }))

const shipment = { shippedAt: '2026-08-02T10:00:00', totalQuantity: 8, deliveryAddress: '第一批地址' }
const item = { lineNo: 10000, skuCode: 'SKU001', productName: '测试产品', quantity: 10, shippedQuantity: 3, availableQuantity: 7, lockedQuantity: 5 }

function mountDialog(order: Record<string, unknown> = {}) {
  return mount(ShipmentQuantityDialog, {
    props: { order: { id: 1, orderNo: 'SO001', defaultShipmentAddress: '默认收货地址', items: [item], ...order } }
  })
}

afterEach(() => vi.clearAllMocks())

it('initializes every line with zero current shipment and disables confirmation', () => {
  const wrapper = mountDialog()

  expect((wrapper.get('[aria-label="本次发货数量"]').element as HTMLInputElement).value).toBe('0')
  expect(wrapper.get('[data-test="shipment-total-current"]').text()).toContain('0')
  expect((wrapper.get('footer .primary-action').element as HTMLButtonElement).disabled).toBe(true)
})

it('limits this shipment to the lower of order remaining and reserved quantity', () => {
  const wrapper = mountDialog()

  expect((wrapper.get('[aria-label="本次发货数量"]').element as HTMLInputElement).max).toBe('5')
})

it('limits this shipment to order remaining when it is lower than reserved quantity', () => {
  const wrapper = mountDialog({ items: [{ ...item, shippedQuantity: 8, lockedQuantity: 5 }] })

  expect((wrapper.get('[aria-label="本次发货数量"]').element as HTMLInputElement).max).toBe('2')
})

it('submits the original cumulative quantity plus this shipment', async () => {
  updateShipmentQuantities.mockResolvedValue({})
  const wrapper = mountDialog({ id: 12 })

  await wrapper.get('[aria-label="本次发货数量"]').setValue('5')
  await wrapper.get('footer .primary-action').trigger('click')
  await flushPromises()

  expect(updateShipmentQuantities).toHaveBeenCalledWith(12, '默认收货地址', [{ lineNo: 10000, shippedQuantity: 8 }])
})

it('updates the remaining quantity from the current shipment value', async () => {
  const wrapper = mountDialog()

  await wrapper.get('[aria-label="本次发货数量"]').setValue('4')

  expect(wrapper.get('[data-test="remaining-quantity"]').text()).toContain('3')
})

it('restores the configured shipment address from the order address button', async () => {
  const wrapper = mountDialog({ defaultShipmentAddress: '仓库默认地址', deliveryAddress: '订单收货地址' })

  await wrapper.get('[data-test="shipment-address"]').setValue('临时收货地址')
  await wrapper.get('[data-test="use-order-address"]').trigger('click')

  expect((wrapper.get('[data-test="shipment-address"]').element as HTMLInputElement).value).toBe('仓库默认地址')
})

it('disables address restoration when there is no configured shipment address', () => {
  const wrapper = mountDialog({ defaultShipmentAddress: undefined, deliveryAddress: undefined })

  expect((wrapper.get('[data-test="use-order-address"]').element as HTMLButtonElement).disabled).toBe(true)
})

it('falls back to the order delivery address for address restoration', async () => {
  const wrapper = mountDialog({ defaultShipmentAddress: undefined, deliveryAddress: '订单收货地址' })

  expect((wrapper.get('[data-test="use-order-address"]').element as HTMLButtonElement).disabled).toBe(false)
  await wrapper.get('[data-test="shipment-address"]').setValue('临时收货地址')
  await wrapper.get('[data-test="use-order-address"]').trigger('click')

  expect((wrapper.get('[data-test="shipment-address"]').element as HTMLTextAreaElement).value).toBe('订单收货地址')
})

it('blocks a positive shipment when the shipment address is empty', async () => {
  const wrapper = mountDialog({ defaultShipmentAddress: undefined, deliveryAddress: undefined })

  await wrapper.get('[aria-label="本次发货数量"]').setValue('1')
  await wrapper.get('footer .primary-action').trigger('click')

  expect(wrapper.emitted('message')?.[0]).toEqual(['请填写本批收货地址', 'error'])
  expect(updateShipmentQuantities).not.toHaveBeenCalled()
})

it('keeps historical shipment details collapsed until explicitly expanded', async () => {
  const wrapper = mountDialog({ shipments: [shipment] })

  expect(wrapper.get('[data-test="shipment-history-toggle"]').text()).toContain('历史发货（1）')
  expect(wrapper.find('[data-test="shipment-history"]').exists()).toBe(false)

  await wrapper.get('[data-test="shipment-history-toggle"]').trigger('click')

  expect(wrapper.get('[data-test="shipment-history"] time').attributes('datetime')).toBe('2026-08-02T10:00:00')
  expect(wrapper.get('[data-test="shipment-history"]').get('[aria-label="本批发货数量 8"]').text()).toBe('8')
  expect(wrapper.get('[data-test="shipment-history"]').get('[aria-label="本批收货地址 第一批地址"]').text()).toBe('第一批地址')
})

it('renders shipment items as product line cards with compact summary metrics', () => {
  const wrapper = mountDialog()

  expect(wrapper.get('[data-test="shipment-line"]').text()).toContain('订单数量')
  expect(wrapper.get('[data-test="shipment-line"]').text()).toContain('累计已发')
  expect(wrapper.get('[data-test="shipment-line"]').text()).toContain('本次发货')
  expect(wrapper.get('[data-test="shipment-line"]').text()).toContain('发货后剩余')
  expect(wrapper.get('[data-test="available-quantity"]').text()).toContain('7')
})
