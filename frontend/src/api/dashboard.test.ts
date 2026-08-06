import { afterEach, describe, expect, it, vi } from 'vitest'
import { loadDashboard } from './dashboard'

describe('首页接口客户端', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('合并数据库状态和首页统计', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ success: true, data: { database: '正常' } }) })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ success: true, data: { pendingReceipt: 1, pendingStock: 2, pendingPurchasePayment: 3, pendingShipment: 4 } }) }))

    await expect(loadDashboard()).resolves.toEqual({
      database: '正常',
      pendingReceipt: 1,
      pendingStock: 2,
      pendingPurchasePayment: 3,
      pendingShipment: 4
    })
  })
})
