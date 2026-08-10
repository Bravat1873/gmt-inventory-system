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

it('prepends the non-sortable product image gallery column', () => {
  const product = moduleDefinitions.find(item => item.key === 'product')!

  expect(product.columns[0]).toBe('图片')
  expect(product.fields[0]).toBe('productImage')
  expect(product.sortable[0]).toBe('')
})

it('includes a Chinese role column in the user list', () => {
  const user = moduleDefinitions.find(item => item.key === 'user')!
  const roleIndex = user.fields.indexOf('role')

  expect(roleIndex).toBeGreaterThanOrEqual(0)
  expect(user.columns[roleIndex]).toBe('角色')
})
