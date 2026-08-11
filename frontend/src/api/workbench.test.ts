import { afterEach, expect, it, vi } from 'vitest'
import { updateShipmentQuantities } from './workbench'

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
