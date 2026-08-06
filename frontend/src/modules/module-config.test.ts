import { expect, it } from 'vitest'
import { moduleDefinitions } from './module-config'

it('shows supplier and product names in the purchase list without identifiers', () => {
  const purchase = moduleDefinitions.find(item => item.key === 'purchase')!
  expect(purchase.fields).toContain('supplierName')
  expect(purchase.fields).toContain('productSummary')
  expect(purchase.fields).not.toContain('supplierId')
  expect(purchase.fields).not.toContain('productIds')
})

it('provides supplier management in the left navigation definitions', () => {
  const supplier = moduleDefinitions.find(item => item.key === 'supplier')!
  expect(supplier.label).toBe('供应商管理')
  expect(supplier.fields).toContain('productCount')
})

it('places finance direction inside the business type cell instead of a standalone column', () => {
  const finance = moduleDefinitions.find(item => item.key === 'finance')!
  expect(finance.fields).not.toContain('cashDirection')
  expect(finance.fields).toContain('businessType')
})
