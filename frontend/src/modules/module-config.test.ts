import { expect, it } from 'vitest'
import { moduleDefinitions } from './module-config'

it('keeps manual order creation and configures a separate ORDER import action', () => {
  const order = moduleDefinitions.find(item => item.key === 'order')!

  expect(order.actionLabel).toBe('新增订单')
  expect(order.importType).toBe('ORDER')
  expect(order.importActionLabel).toBe('导入订单')
})

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
it('offers supplier spreadsheet import and displays the complete supplier profile', () => {
  const supplier = moduleDefinitions.find(item => item.key === 'supplier')!

  expect(supplier.actionLabel).toBe('导入供应商')
  expect(supplier.importType).toBe('SUPPLIER')
  expect(supplier.fields).toEqual([
    'manufacturerCategory', 'manufacturerType', 'supplierLocation', 'productAttribute',
    'shortName', 'supplierName', 'contactName', 'contactTitle', 'phone', 'address',
    'currency', 'taxRegistrationNo', 'bankAddress', 'bankAccount', 'productCount', 'updatedAt'
  ])
})

it('places finance direction inside the business type cell instead of a standalone column', () => {
  const finance = moduleDefinitions.find(item => item.key === 'finance')!
  expect(finance.fields).not.toContain('cashDirection')
  expect(finance.fields).toContain('businessType')
  expect(finance.columns[finance.fields.indexOf('amount')]).toBe('总金额')
  expect(finance.columns[finance.fields.indexOf('invoiceNos')]).toBe('发票号码')
})

it('places oldest stock date and inventory age after the available inventory quantities', () => {
  const inventory = moduleDefinitions.find(item => item.key === 'inventory')!
  const availableIndex = inventory.fields.indexOf('availableQuantity')

  expect(inventory.fields.slice(availableIndex + 1, availableIndex + 3)).toEqual(['oldestStockDate', 'inventoryAgeDays'])
  expect(inventory.columns.slice(availableIndex + 1, availableIndex + 3)).toEqual(['最早在库日期', '库龄'])
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

it('includes the product material type column', () => {
  const product = moduleDefinitions.find(item => item.key === 'product')!
  const index = product.fields.indexOf('materialType')
  expect(index).toBeGreaterThanOrEqual(0)
  expect(product.columns[index]).toBe('物料类型')
})
it('keeps location allocations out of the extensible inventory table', () => {
  const inventory = moduleDefinitions.find(item => item.key === 'inventory')!
  expect(inventory.fields).not.toEqual(expect.arrayContaining([
    'lockedMingAiJunQiao', 'lockedBoLeLongMi', 'lockedLaos', 'lockedBeiLang', 'lockedMalaysia'
  ]))
  expect(inventory.columns).not.toEqual(expect.arrayContaining(['铭爱钧乔', '博乐龙米', '老挝', '贝朗', '马来西亚']))
})
it('labels unlocked stock and places demand metrics after in-transit stock', () => {
  const inventory = moduleDefinitions.find(item => item.key === 'inventory')!
  const availableIndex = inventory.fields.indexOf('availableQuantity')
  const transitIndex = inventory.fields.indexOf('inTransitQuantity')
  expect(inventory.columns[availableIndex]).toBe('未锁定库存数量')
  expect(inventory.fields.slice(transitIndex, transitIndex + 3)).toEqual(['inTransitQuantity', 'pendingDeliveryQuantity', 'supplyDemandSurplus'])
  expect(inventory.columns.slice(transitIndex, transitIndex + 3)).toEqual(['在途数量', '未发货数量', '供需余量'])
})
it('replaces fixed product price columns with supplier quotes while keeping sales minimum', () => {
  const product = moduleDefinitions.find(item => item.key === 'product')!
  expect(product.fields).toContain('supplierQuotes')
  expect(product.columns[product.fields.indexOf('supplierQuotes')]).toBe('供应商报价')
  expect(product.fields).toContain('salesMinimumOrderQuantity')
  expect(product.fields).not.toEqual(expect.arrayContaining(['currentCost', 'factoryPrice', 'priceDifference']))
})

it('shows inventory fields inside the product module list', () => {
  const product = moduleDefinitions.find(item => item.key === 'product')!
  const inventoryIndex = product.fields.indexOf('actualQuantity')

  expect(product.fields.slice(inventoryIndex, inventoryIndex + 5)).toEqual([
    'actualQuantity', 'lockedQuantity', 'inTransitQuantity', 'sourceSupplierName', 'inventoryRemark'
  ])
  expect(product.columns.slice(inventoryIndex, inventoryIndex + 5)).toEqual([
    '实际库存数量', '已锁定数量', '在途数量', '库存供应商', '库存备注'
  ])
})
it('enables sorting for short management fields and excludes long display fields', () => {
  const expected: Record<string, string[]> = {
    customer: ['customerCode', 'customerName', 'orderContactName', 'orderContactPhone', 'contractStatus', 'contractEndDate', 'updatedAt'],
    user: ['username', 'displayName', 'role', 'updatedAt'],
    product: ['productCode', 'customerPartNumber', 'brand', 'model', 'productType', 'materialType', 'salesMinimumOrderQuantity', 'updatedAt'],
    supplier: ['manufacturerCategory', 'manufacturerType', 'supplierLocation', 'productAttribute', 'shortName', 'supplierName', 'contactName', 'contactTitle', 'phone', 'currency', 'taxRegistrationNo', 'bankAddress', 'bankAccount', 'productCount', 'updatedAt'],
    order: ['orderNo', 'customerName', 'orderType', 'totalAmount', 'status', 'orderDate', 'salesperson', 'createdAt', 'updatedAt'],
    afterSales: ['afterSalesNo', 'orderNo', 'customerName', 'orderType', 'afterSalesType', 'returnQuantity', 'replacementQuantity', 'status', 'applicationDate', 'updatedAt'],
    inventory: ['productCode', 'model', 'productType', 'unit', 'actualQuantity', 'availableQuantity', 'oldestStockDate', 'inventoryAgeDays', 'lockedQuantity', 'inTransitQuantity', 'pendingDeliveryQuantity', 'supplyDemandSurplus', 'sourceSupplierName', 'updatedAt'],
    purchase: ['purchaseNo', 'supplierName', 'totalAmount', 'paymentStatus', 'receiptStatus', 'status', 'expectedArrivalDate', 'updatedAt'],
    finance: ['businessNo', 'businessType', 'counterparty', 'amount', 'settledAmount', 'outstandingAmount', 'status', 'updatedAt']
  }
  for (const [key, fields] of Object.entries(expected)) {
    const module = moduleDefinitions.find(item => item.key === key)!
    for (const field of fields) expect(module.sortable[module.fields.indexOf(field)], `${key}.${field}`).toBe(field)
  }
  const excluded = ['productImage', 'remark', 'address', 'configuration', 'productConfiguration', 'supplierQuotes', 'productSummary', 'invoiceNos']
  for (const module of moduleDefinitions) for (const field of excluded) {
    const index = module.fields.indexOf(field)
    if (index >= 0) expect(module.sortable[index], `${module.key}.${field}`).toBe('')
  }
})

it('places order type after customer in order and after-sales lists', () => {
  for (const key of ['order', 'afterSales'] as const) {
    const module = moduleDefinitions.find(item => item.key === key)!
    const customerIndex = module.fields.indexOf('customerName')
    expect(module.fields[customerIndex + 1]).toBe('orderType')
    expect(module.columns[customerIndex + 1]).toBe('订单类型')
  }
})

