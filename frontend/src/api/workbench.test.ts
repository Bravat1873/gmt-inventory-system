import { afterEach, expect, it, vi } from 'vitest'
import { deleteOrder, reviewOrder, updateShipmentQuantities } from './workbench'

afterEach(() => vi.unstubAllGlobals())

it('sends the delivery address, items, and optional remark when updating shipment quantities', async () => {
  const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ success: true, data: {} }) })
  vi.stubGlobal('fetch', fetch)

  await updateShipmentQuantities(4, '第一批收货地址', [{ lineNo: 10, shippedQuantity: 3 }], '加急')

  expect(fetch).toHaveBeenCalledWith('/api/orders/4/shipment-quantities', expect.objectContaining({
    method: 'PUT',
    body: JSON.stringify({ deliveryAddress: '第一批收货地址', items: [{ lineNo: 10, shippedQuantity: 3 }], remark: '加急' })
  }))
})

it('sends dedicated review and delete requests for orders', async () => {
  const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ success: true, data: {} }) })
  vi.stubGlobal('fetch', fetch)

  await reviewOrder(4)
  await deleteOrder(4)

  expect(fetch).toHaveBeenNthCalledWith(1, '/api/orders/4/review', expect.objectContaining({ method: 'POST' }))
  expect(fetch).toHaveBeenNthCalledWith(2, '/api/orders/4', expect.objectContaining({ method: 'DELETE' }))
})
