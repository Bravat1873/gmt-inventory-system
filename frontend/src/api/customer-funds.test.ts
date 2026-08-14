import { afterEach, expect, it, vi } from 'vitest'
import { loadCustomerFundOverview, submitCustomerDeposit } from './customer-funds'

afterEach(() => vi.restoreAllMocks())

it('loads fund overview and submits a pending deposit', async () => {
  const fetchMock = vi.spyOn(globalThis, 'fetch')
  fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ success: true, data: { customerId: 7, balance: 100 } }), { status: 200 }))
  fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ success: true, data: 19 }), { status: 200 }))
  await expect(loadCustomerFundOverview(7)).resolves.toMatchObject({ balance: 100 })
  await expect(submitCustomerDeposit(7, { amount: 50, paymentDate: '2026-08-14', paymentMethod: '银行转账' })).resolves.toBe(19)
  expect(fetchMock.mock.calls[1][0]).toBe('/api/customers/7/funds/deposits')
})