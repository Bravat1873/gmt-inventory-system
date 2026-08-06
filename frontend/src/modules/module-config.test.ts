import { expect, it } from 'vitest'
import { moduleDefinitions } from './module-config'

it('shows supplier and product identifiers in the purchase list', () => {
  const purchase = moduleDefinitions.find(item => item.key === 'purchase')!
  expect(purchase.fields).toContain('supplierId')
  expect(purchase.fields).toContain('productIds')
})

it('places finance direction inside the business type cell instead of a standalone column', () => {
  const finance = moduleDefinitions.find(item => item.key === 'finance')!
  expect(finance.fields).not.toContain('cashDirection')
  expect(finance.fields).toContain('businessType')
})
