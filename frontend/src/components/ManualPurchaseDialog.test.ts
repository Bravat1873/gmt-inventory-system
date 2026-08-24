import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import ManualPurchaseDialog from './ManualPurchaseDialog.vue'

const api = vi.hoisted(() => ({
  createManualPurchase: vi.fn().mockResolvedValue({ purchaseNo: 'PO-001' }),
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
    quantity: 5,
    deliveryAddress: '珠海市香洲区交货地址',
    remark: '原备注'
  }))
  expect(api.createManualPurchase).not.toHaveBeenCalled()
})

it('only updates delivery fields for a system purchase', async () => {
  vi.clearAllMocks()
  const wrapper = mount(ManualPurchaseDialog, {
    props: {
      purchase: {
        id: 92,
        purchaseNo: 'CG20260800001',
        supplierName: '贝朗供应商',
        totalAmount: 1100,
        manualEntry: false,
        expectedArrivalDate: '2026-08-25',
        deliveryAddress: '原交货地址',
        remark: '原备注',
        items: [{ id: 1, skuId: 101, quantity: 10, receivedQuantity: 0, remainingQuantity: 10 }]
      }
    }
  })
  await flushPromises()

  expect(wrapper.text()).toContain('产品、数量和供应商由采购建议锁定')
  expect(wrapper.find('[data-test="product-search"]').exists()).toBe(false)
  await wrapper.get('textarea[placeholder="填写供应商送货地址"]').setValue('新交货地址')
  await wrapper.findAll('textarea')[1].setValue('新备注')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.updatePurchaseHeader).toHaveBeenCalledWith(92, {
    expectedArrivalDate: '2026-08-25',
    deliveryAddress: '新交货地址',
    remark: '新备注'
  })
  expect(api.updateManualPurchase).not.toHaveBeenCalled()
})

