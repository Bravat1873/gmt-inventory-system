import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, it } from 'vitest'

const source = (name: string) => readFileSync(resolve(process.cwd(), `src/components/${name}`), 'utf8')

it.each([
  'ProcurementReviewDialog.vue',
  'PurchaseReceiptDialog.vue',
  'OrderAllocationDialog.vue',
  'ShipmentQuantityDialog.vue',
  'BusinessTraceDialog.vue',
  'AfterSalesDialog.vue',
  'AfterSalesReceiptDialog.vue',
  'AfterSalesShipmentDialog.vue',
])('%s uses the shared complete product identity display', (name) => {
  const text = source(name)
  expect(text).toContain("import ProductIdentityDisplay from './ProductIdentityDisplay.vue'")
  expect(text).toContain('<ProductIdentityDisplay')
})
