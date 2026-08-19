import { afterEach, describe, expect, it, vi } from 'vitest'
import { loadDashboard } from './dashboard'

describe('首页接口客户端', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('读取指定周期的实时看板快照', async () => {
    const snapshot = { todayOrders: 2, pendingReceipt: 1, pendingStock: 2, pendingPurchasePayment: 3, pendingShipment: 4, days: 30, trend: [], exceptions: [] }
    const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ success: true, data: snapshot }) })
    vi.stubGlobal('fetch', fetch)

    await expect(loadDashboard(30)).resolves.toEqual(snapshot)
    expect(fetch).toHaveBeenCalledWith('/api/dashboard/snapshot?days=30')
  })
})