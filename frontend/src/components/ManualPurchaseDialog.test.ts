import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import ManualPurchaseDialog from './ManualPurchaseDialog.vue'

const api = vi.hoisted(() => ({
  createManualPurchase: vi.fn().mockResolvedValue({ purchaseNo: 'PO-001', status: 'DRAFT' }),
  updateManualPurchase: vi.fn().mockResolvedValue({ purchaseNo: 'CG-001' }),
  updatePurchaseHeader: vi.fn().mockResolvedValue({ purchaseNo: 'CG-001' }),
  loadOrderSkus: vi.fn().mockResolvedValue([
    { id: 101, currentCost: 100, factoryPrice: 120, customerPartNumber: 'P90-001', productName: 'P90 智能锁', model: 'P90', unit: '件', actualQuantity: 2, availableQuantity: 2, inTransitQuantity: 3, pendingDeliveryQuantity: 10, supplyDemandSurplus: -5, purchaseShortageQuantity: 5 },
    { id: 102, currentCost: 80, factoryPrice: 95, customerPartNumber: 'P50-001', productName: 'P50 智能锁', model: 'P50', unit: '件', actualQuantity: 12, availableQuantity: 10, inTransitQuantity: 3, pendingDeliveryQuantity: 5, supplyDemandSurplus: 10, purchaseShortageQuantity: 0 }
  ]),
  loadProductSuppliers: vi.fn().mockResolvedValue([
    { supplierId: 201, supplierName: '贝朗供应商', purchaseInfos: [
      { id: 12, purchasePrice: 220, moq: 5, leadTimeDays: 7, updatedAt: '2026-08-14T10:00:00' },
      { id: 11, purchasePrice: 210, moq: 10, leadTimeDays: 9, updatedAt: '2026-08-13T10:00:00' }
    ], latestPurchaseInfo: { id: 12, purchasePrice: 220, moq: 5, leadTimeDays: 7, updatedAt: '2026-08-14T10:00:00' } }
  ])
}))

vi.mock('../api/workbench', () => api)

it('adds multiple products from one supplier and saves the pending product too', async () => {
  vi.clearAllMocks()
  const wrapper = mount(ManualPurchaseDialog)
  await flushPromises()
  await wrapper.get('[data-test="product-search"]').trigger('focus')
  await wrapper.get('[data-test="product-option-101"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-test="supplier-search"]').trigger('focus')
  await wrapper.get('[data-test="supplier-option-201"]').trigger('click')
  await wrapper.get('input[type="number"]').setValue('5')
  await wrapper.get('[data-test="add-purchase-line"]').trigger('click')
  expect(wrapper.get('[data-test="manual-purchase-lines"]').text()).toContain('已添加 1 条明细')
  await wrapper.get('[data-test="product-search"]').trigger('focus')
  await wrapper.get('[data-test="product-option-102"]').trigger('click')
  await flushPromises()
  expect((wrapper.get('[data-test="supplier-search"]').element as HTMLInputElement).value).toBe('贝朗供应商')
  await wrapper.findAll('input[type="number"]')[1].setValue('10')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.createManualPurchase).toHaveBeenCalledWith(expect.objectContaining({ supplierId: 201, items: [
    { skuId: 101, supplierPurchaseInfoId: 12, quantity: 5 }, { skuId: 102, supplierPurchaseInfoId: 12, quantity: 10 }
  ] }))
})

it('allows zero quantity for a draft line and explains the required review', async () => {
  vi.clearAllMocks()
  const wrapper = mount(ManualPurchaseDialog)
  await flushPromises()
  expect(wrapper.get('#manual-purchase-title').text()).toBe('新增采购单')
  await wrapper.get('[data-test="product-search"]').trigger('focus')
  await wrapper.get('[data-test="product-option-101"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-test="supplier-search"]').trigger('focus')
  await wrapper.get('[data-test="supplier-option-201"]').trigger('click')
  await wrapper.get('input[type="number"]').setValue('0')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.createManualPurchase).toHaveBeenCalledWith(expect.objectContaining({
    items: [{ skuId: 101, supplierPurchaseInfoId: 12, quantity: 0 }]
  }))
  expect(wrapper.emitted('message')?.[0]?.[0]).toContain('已保存为草稿，请在列表复核')
})

it('does not treat an empty quantity input as zero', async () => {
  vi.clearAllMocks()
  const wrapper = mount(ManualPurchaseDialog)
  await flushPromises()
  await wrapper.get('[data-test="product-search"]').trigger('focus')
  await wrapper.get('[data-test="product-option-101"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-test="supplier-search"]').trigger('focus')
  await wrapper.get('[data-test="supplier-option-201"]').trigger('click')
  await wrapper.get('input[type="number"]').setValue('')
  await wrapper.get('form').trigger('submit')
  expect(wrapper.text()).toContain('采购数量须为整数')
  expect(api.createManualPurchase).not.toHaveBeenCalled()
})

it('restores every product of an existing purchase without dropping lines on save', async () => {
  vi.clearAllMocks()
  const wrapper = mount(ManualPurchaseDialog, { props: { purchase: {
    id: 91, supplierId: 201, purchaseNo: 'CG-001', supplierName: '贝朗供应商', totalAmount: 3300,
    items: [{ id: 1, skuId: 101, supplierPurchaseInfoId: 12, quantity: 5, receivedQuantity: 0, remainingQuantity: 5 },
      { id: 2, skuId: 102, supplierPurchaseInfoId: 12, quantity: 10, receivedQuantity: 0, remainingQuantity: 10 }]
  } } })
  await flushPromises()
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  const payload = api.updateManualPurchase.mock.calls[0][1]
  expect(payload.items).toHaveLength(2)
  expect(payload.items).toEqual(expect.arrayContaining([
    { id: 1, retainPrice: true, skuId: 101, supplierPurchaseInfoId: 12, quantity: 5 }, { id: 2, retainPrice: true, skuId: 102, supplierPurchaseInfoId: 12, quantity: 10 }
  ]))
})

it('saves zero in an existing purchase line without dropping other lines', async () => {
  vi.clearAllMocks()
  const wrapper = mount(ManualPurchaseDialog, { props: { purchase: {
    id: 91, supplierId: 201, purchaseNo: 'CG-001', supplierName: '贝朗供应商', status: 'DRAFT', totalAmount: 3300,
    items: [{ id: 1, skuId: 101, supplierPurchaseInfoId: 12, quantity: 5, receivedQuantity: 0, remainingQuantity: 5 },
      { id: 2, skuId: 102, supplierPurchaseInfoId: 12, quantity: 10, receivedQuantity: 0, remainingQuantity: 10 }]
  } } })
  await flushPromises()
  await wrapper.get('[data-test="manual-line-quantity-0"]').setValue('0')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.updateManualPurchase).toHaveBeenCalledWith(91, expect.objectContaining({
    items: [{ id: 1, retainPrice: true, skuId: 101, supplierPurchaseInfoId: 12, quantity: 0 },
      { id: 2, retainPrice: true, skuId: 102, supplierPurchaseInfoId: 12, quantity: 10 }]
  }))
})

it('shows searchable products with product code first', async () => {
  api.loadOrderSkus.mockResolvedValueOnce([{ id: 201, productCode: 'BR_P90', customerPartNumber: 'P90-001', model: 'P90' }])
  const wrapper = mount(ManualPurchaseDialog)
  await flushPromises()
  await wrapper.get('[data-test="product-search"]').trigger('focus')
  expect(wrapper.get('[data-test="product-option-201"]').text())
    .toMatch(/产品编号：BR_P90[\s\S]*客户料号：P90-001[\s\S]*型号：P90/)
  await wrapper.get('[data-test="product-option-201"]') .trigger('click')
  expect((wrapper.get('[data-test="product-search"]') .element as HTMLInputElement).value).toBe('BR_P90')
})
it('selects a product first and then its configured supplier', async () => {
  const wrapper = mount(ManualPurchaseDialog)
  await flushPromises()

  const productSearch = wrapper.get('[data-test="product-search"]')
  const supplierSearch = wrapper.get('[data-test="supplier-search"]')
  expect(productSearch.element.compareDocumentPosition(supplierSearch.element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(supplierSearch.attributes('disabled')).toBeDefined()

  await productSearch.trigger('focus')
  await wrapper.get('[data-test="product-option-101"]').trigger('click')
  await flushPromises()
  expect(api.loadProductSuppliers).toHaveBeenCalledWith(101, '')
  expect(supplierSearch.attributes('disabled')).toBeUndefined()

  await supplierSearch.trigger('focus')
  await wrapper.get('[data-test="supplier-option-201"]').trigger('click')
  const priceSelect = wrapper.get('[data-test="purchase-price"]')
  expect(priceSelect.text()).toContain('¥220｜起订 5｜交货 7 天')
  expect(wrapper.text()).toContain('最小起订量：5')
  expect(wrapper.text()).not.toContain('成本单价')
  expect(wrapper.text()).not.toContain('转厂价格')

  await productSearch.trigger('focus')
  await productSearch.setValue('P50')
  await wrapper.get('[data-test="product-option-102"]').trigger('click')
  await flushPromises()
  expect((supplierSearch.element as HTMLInputElement).value).toBe('')
  expect(wrapper.get('[data-test="purchase-price"]').text()).toContain('请选择采购信息')
})
it('shows purchase guidance for a negative balance without changing quantity', async () => {
  const wrapper = mount(ManualPurchaseDialog)
  await flushPromises()
  await wrapper.get('[data-test="product-search"]').trigger('focus')
  await wrapper.get('[data-test="product-option-101"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('实际库存 2')
  expect(wrapper.text()).toContain('在途数量 3')
  expect(wrapper.text()).toContain('未发货数量 10')
  expect(wrapper.text()).toContain('供需余量 -5')
  expect(wrapper.text()).toContain('建议采购 5')
  expect((wrapper.get('input[type="number"]').element as HTMLInputElement).value).toBe('1')
})

it('does not show purchase guidance for a nonnegative balance', async () => {
  const wrapper = mount(ManualPurchaseDialog)
  await flushPromises()
  await wrapper.get('[data-test="product-search"]').trigger('focus')
  await wrapper.get('[data-test="product-option-102"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('供需余量 10')
  expect(wrapper.text()).not.toContain('建议采购')
})

it('submits the optional delivery address with a manual purchase', async () => {
  const wrapper = mount(ManualPurchaseDialog)
  await flushPromises()
  await wrapper.get('[data-test="product-search"]').trigger('focus')
  await wrapper.get('[data-test="product-option-101"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-test="supplier-search"]').trigger('focus')
  await wrapper.get('[data-test="supplier-option-201"]').trigger('click')
  await wrapper.get('textarea[placeholder="填写供应商送货地址"]').setValue('珠海市香洲区示例交货地址')
  await wrapper.get('input[type="number"]').setValue('5')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.createManualPurchase).toHaveBeenCalledWith(expect.objectContaining({
    deliveryAddress: '珠海市香洲区示例交货地址'
  }))
})

it('updates an existing manual purchase instead of creating another purchase', async () => {
  vi.clearAllMocks()
  const wrapper = mount(ManualPurchaseDialog, {
    props: {
      purchase: {
        id: 91,
        purchaseNo: 'CG20260800001',
        supplierId: 201,
        supplierName: '贝朗供应商',
        totalAmount: 1100,
        expectedArrivalDate: '2026-08-25',
        deliveryAddress: '珠海市香洲区交货地址',
        remark: '原备注',
        items: [{ id: 1, skuId: 101, supplierPurchaseInfoId: 12, quantity: 5, receivedQuantity: 0, remainingQuantity: 5 }]
      }
    }
  })
  await flushPromises()

  expect((wrapper.get('[data-test="product-search"]').element as HTMLInputElement).value).toBe('未设置产品编号')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.updateManualPurchase).toHaveBeenCalledWith(91, expect.objectContaining({
    items: [{ id: 1, retainPrice: true, skuId: 101, supplierPurchaseInfoId: 12, quantity: 5 }],
    deliveryAddress: '珠海市香洲区交货地址',
    remark: '原备注'
  }))
  expect(api.createManualPurchase).not.toHaveBeenCalled()
})

it('fully edits a system purchase with historical quote and version', async () => {
  vi.clearAllMocks()
  const wrapper = mount(ManualPurchaseDialog, { props: { purchase: {
    id: 92, version: 7, purchaseNo: 'CG20260800001', supplierId: 201, supplierName: '贝朗供应商',
    totalAmount: 1100, manualEntry: false, status: 'EXECUTING', supplierLocked: true, paidAmount: 800,
    expectedArrivalDate: '2026-08-25', deliveryAddress: '原交货地址', remark: '原备注',
    items: [{ id: 1, skuId: 101, purchasePrice: 110, quantity: 10, receivedQuantity: 4, remainingQuantity: 6 }]
  } } })
  await flushPromises()
  expect(wrapper.find('[data-test="product-search"]').exists()).toBe(true)
  expect(wrapper.get('[data-test="product-search"]').attributes('disabled')).toBeDefined()
  expect(wrapper.get('[data-test="supplier-search"]').attributes('disabled')).toBeDefined()
  await wrapper.get('input[type="number"]').setValue('6')
  expect(wrapper.get('[data-test="manual-purchase-total"]').text()).toContain('660.00')
  expect(wrapper.get('[data-test="purchase-overpaid"]').text()).toContain('140.00')
  await wrapper.get('textarea[placeholder="填写供应商送货地址"]').setValue('新交货地址')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.updateManualPurchase).toHaveBeenCalledWith(92, expect.objectContaining({
    version: 7, supplierId: 201, deliveryAddress: '新交货地址', expectedArrivalDate: '2026-08-25',
    items: [{ id: 1, retainPrice: true, skuId: 101, supplierPurchaseInfoId: 0, quantity: 6 }]
  }))
  expect(api.updatePurchaseHeader).not.toHaveBeenCalled()
})

it('blocks quantity below receipts and preserves a received line while editing another line', async () => {
  vi.clearAllMocks()
  const wrapper = mount(ManualPurchaseDialog, { props: { purchase: {
    id: 92, version: 3, purchaseNo: 'CG-001', supplierId: 201, supplierName: '贝朗供应商', totalAmount: 3300,
    status: 'COMPLETED', supplierLocked: true,
    items: [{ id: 1, skuId: 101, supplierPurchaseInfoId: 12, purchasePrice: 220, quantity: 5, receivedQuantity: 5, remainingQuantity: 0 },
      { id: 2, skuId: 102, supplierPurchaseInfoId: 12, purchasePrice: 220, quantity: 10, receivedQuantity: 10, remainingQuantity: 0 }]
  } } })
  await flushPromises()
  expect(wrapper.get('[data-test="remove-manual-line-0"]').attributes('disabled')).toBeDefined()
  await wrapper.get('[data-test="edit-manual-line-0"]').trigger('click')
  await wrapper.findAll('input[type="number"]')[1].setValue('4')
  await wrapper.get('form').trigger('submit')
  expect(api.updateManualPurchase).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('不能小于已收货数量 5')
  await wrapper.findAll('input[type="number"]')[1].setValue('6')
  await wrapper.get('[data-test="edit-manual-line-0"]').trigger('click')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.updateManualPurchase.mock.calls[0][1].items).toEqual(expect.arrayContaining([
    { id: 1, retainPrice: true, skuId: 101, supplierPurchaseInfoId: 12, quantity: 6 },
    { id: 2, retainPrice: true, skuId: 102, supplierPurchaseInfoId: 12, quantity: 10 }
  ]))
})

it('restores disabled products and retains the original price when the catalogue price changes', async () => {
  vi.clearAllMocks()
  api.loadOrderSkus.mockResolvedValueOnce([])
  const wrapper = mount(ManualPurchaseDialog, { props: { purchase: {
    id: 92, version: 2, purchaseNo: 'CG-001', supplierId: 201, supplierName: '贝朗供应商', totalAmount: 100,
    items: [{ id: 1, skuId: 101, productCode: 'OLD-101', supplierPurchaseInfoId: 12, purchasePrice: 10, quantity: 10, receivedQuantity: 0, remainingQuantity: 10 }]
  } } })
  await flushPromises()
  expect((wrapper.get('[data-test="product-search"]').element as HTMLInputElement).value).toBe('OLD-101')
  expect(wrapper.get('[data-test="manual-purchase-total"]').text()).toContain('100.00')
  const select = wrapper.get('[data-test="purchase-price"]')
  const element = select.element as HTMLSelectElement
  element.selectedIndex = 2 // First current catalogue quote after original snapshot and empty option.
  await select.trigger('change')
  expect(wrapper.get('[data-test="manual-purchase-total"]').text()).toContain('2200.00')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.updateManualPurchase.mock.calls[0][1].items[0].retainPrice).toBe(false)
})
