import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import OrderDialog from './OrderDialog.vue'

const { createOrder, updateOrder, loadOrderCustomers, loadOrderSkus, loadContractPrice } = vi.hoisted(() => ({
  createOrder: vi.fn(), updateOrder: vi.fn(), loadOrderCustomers: vi.fn(), loadOrderSkus: vi.fn(), loadContractPrice: vi.fn()
}))

vi.mock('../api/workbench', () => ({ createOrder, updateOrder, loadOrderCustomers, loadOrderSkus, loadContractPrice }))

const validOrder = () => ({ customerId: 1, orderDate: '2026-08-07', orderType: '工程订单', salesperson: 'Admin', items: [{ skuId: 1, quantity: 1, salePrice: 1 }] })
const sku = (id: number) => ({ id, customerPartNumber: `SKU-${id}`, productName: `Product ${id}`, model: 'M1', configuration: 'Standard', unit: 'PCS', primaryImageId: null as number | null, actualQuantity: 0, availableQuantity: 0, inTransitQuantity: 0, pendingDeliveryQuantity: 0, supplyDemandSurplus: 0, purchaseShortageQuantity: 0 })
const priceInput = (wrapper: VueWrapper) => wrapper.findAll('input[type="number"]')[1]
const legacyMaterialNumber = '物料' + '编号'
function deferred<T>() { let resolve!: (value: T) => void; return { promise: new Promise<T>(done => { resolve = done }), resolve } }
async function choose(wrapper: VueWrapper, picker: string, text: string, optionId: number) {
  await wrapper.get(`${picker} input`).setValue(text)
  document.body.querySelector<HTMLElement>(`[data-test="fuzzy-option-${optionId}"]`)?.click()
  await flushPromises()
}

it('does not submit without a customer', async () => {
  loadOrderSkus.mockResolvedValue([sku(1)])
  loadOrderCustomers.mockResolvedValue([])
  const wrapper = mount(OrderDialog, { props: { defaultSalesperson: 'Admin' } })
  await flushPromises()
  await wrapper.get('form').trigger('submit')
  expect(createOrder).not.toHaveBeenCalled()
  expect(wrapper.get('[data-test="customer-error"]').text()).toBe('请选择客户')
  wrapper.unmount()
})

it('uses 客户料号 for its visible material-number labels and prompts', async () => {
  loadOrderSkus.mockResolvedValue([sku(1)])
  loadOrderCustomers.mockResolvedValue([])
  const wrapper = mount(OrderDialog, { props: { defaultSalesperson: 'Admin' } })
  await flushPromises()

  expect(wrapper.text()).toContain('客户料号')
  expect(wrapper.text()).not.toContain(legacyMaterialNumber)
  expect((wrapper.get('[data-test="order-sku-picker-0"] input').element as HTMLInputElement).placeholder).toContain('客户料号')
  wrapper.unmount()
})

it('searches materials using product details when the SKU code is empty', async () => {
  loadOrderSkus.mockResolvedValue([{ ...sku(8), customerPartNumber: '', productName: 'Smart Lock', model: 'E71' }])
  loadOrderCustomers.mockResolvedValue([])
  const wrapper = mount(OrderDialog, { props: { defaultSalesperson: 'Admin' } })
  await flushPromises()
  await wrapper.get('[data-test="order-sku-picker-0"] input').setValue('E71')
  expect(document.body.querySelector('[data-test="fuzzy-option-8"]')?.textContent).toContain('型号：E71')
  wrapper.unmount()
})

it('searches and displays product identifiers with product code first', async () => {
  loadOrderSkus.mockResolvedValue([
    { ...sku(21), productCode: 'BR_D51-A', customerPartNumber: 'D1213K-D51', model: 'D51-GEN2' },
    { ...sku(22), productCode: 'BR_D51-B', customerPartNumber: 'D1213K-D51', model: 'D51-GEN2' }
  ])
  loadOrderCustomers.mockResolvedValue([])
  const wrapper = mount(OrderDialog, { attachTo: document.body, props: { defaultSalesperson: 'Admin' } })
  await flushPromises()

  await wrapper.get('[data-test="order-sku-picker-0"] input').setValue('BR_D51-B')
  expect(document.body.querySelector('[data-test="fuzzy-option-21"]')).toBeNull()
  const option = document.body.querySelector<HTMLElement>('[data-test="fuzzy-option-22"]')
  expect(option?.textContent).toMatch(/产品编号：BR_D51-B[\s\S]*客户料号：D1213K-D51[\s\S]*型号：D51-GEN2/)

  option?.click()
  await flushPromises()
  expect((wrapper.get('[data-test="order-sku-picker-0"] input').element as HTMLInputElement).value).toBe('BR_D51-B')
  expect(wrapper.get('[data-test="order-product-identifiers-0"]').text())
    .toMatch(/产品编号BR_D51-B[\s\S]*客户料号D1213K-D51[\s\S]*型号D51-GEN2/)
  wrapper.unmount()
})
it('hides the duplicate product name while retaining model details', async () => {
  loadOrderSkus.mockResolvedValue([{ ...sku(31), productCode: 'BR_A71', customerPartNumber: 'G8A71HS001', productName: 'A71', model: 'A71', configuration: 'BRAVAT / A71 / 宇宙黑' }])
  loadOrderCustomers.mockResolvedValue([])
  const wrapper = mount(OrderDialog, { attachTo: document.body, props: { defaultSalesperson: 'Admin' } })
  await flushPromises()
  await choose(wrapper, '[data-test="order-sku-picker-0"]', 'BR_A71', 31)
  const identity = wrapper.get('[data-test="order-product-identifiers-0"]')
  expect(identity.text()).toContain('型号A71')
  expect(identity.text()).toContain('型号 / 规格')
  expect(identity.text()).not.toContain('产品名称')
  wrapper.unmount()
})
it('leaves order and delivery contacts blank when the selected customer lacks order snapshots', async () => {
  loadOrderSkus.mockResolvedValue([])
  loadOrderCustomers.mockResolvedValue([{ id: 9, customerCode: 'C9', customerName: 'Legacy customer', contactName: 'Legacy name', phone: '13800138000', address: 'Address' }])
  const wrapper = mount(OrderDialog, { props: { defaultSalesperson: 'Admin' } })
  await flushPromises()
  await choose(wrapper, '[data-test="order-customer-picker"]', 'Legacy', 9)
  expect((wrapper.get('[data-test="order-customer-contact"]').element as HTMLInputElement).value).toBe('')
  expect((wrapper.get('[data-test="order-customer-phone"]').element as HTMLInputElement).value).toBe('')
  expect((wrapper.get('[data-test="order-delivery-contact"]').element as HTMLInputElement).value).toBe('')
  expect((wrapper.get('[data-test="order-delivery-phone"]').element as HTMLInputElement).value).toBe('')
  wrapper.unmount()
})

it('copies all three snapshots plus delivery defaults from the selected customer', async () => {
  loadOrderSkus.mockResolvedValue([])
  loadOrderCustomers.mockResolvedValue([{ id: 11, customerCode: 'C11', customerName: 'Snapshot customer', address: 'Delivery address', businessContactName: 'Business Wang', businessContactPhone: '13800000001', orderContactName: 'Order Li', orderContactPhone: '13800000002', financeContactName: 'Finance Zhao', financeContactPhone: '13800000003' }])
  const wrapper = mount(OrderDialog, { props: { defaultSalesperson: 'Admin' } })
  await flushPromises()
  await choose(wrapper, '[data-test="order-customer-picker"]', 'Snapshot', 11)
  expect((wrapper.get('[data-test="business-contact-name"]').element as HTMLInputElement).value).toBe('Business Wang')
  expect((wrapper.get('[data-test="business-contact-phone"]').element as HTMLInputElement).value).toBe('13800000001')
  expect((wrapper.get('[data-test="order-customer-contact"]').element as HTMLInputElement).value).toBe('Order Li')
  expect((wrapper.get('[data-test="order-customer-phone"]').element as HTMLInputElement).value).toBe('13800000002')
  expect((wrapper.get('[data-test="finance-contact-name"]').element as HTMLInputElement).value).toBe('Finance Zhao')
  expect((wrapper.get('[data-test="finance-contact-phone"]').element as HTMLInputElement).value).toBe('13800000003')
  expect((wrapper.get('[data-test="order-delivery-address"]').element as HTMLTextAreaElement).value).toBe('Delivery address')
  expect((wrapper.get('[data-test="order-delivery-contact"]').element as HTMLInputElement).value).toBe('Order Li')
  expect((wrapper.get('[data-test="order-delivery-phone"]').element as HTMLInputElement).value).toBe('13800000002')
  wrapper.unmount()
})

it('keeps saved snapshots when customer options load and falls back to legacy fields only for an old order', async () => {
  loadOrderSkus.mockResolvedValue([])
  loadOrderCustomers.mockResolvedValue([{ id: 12, customerCode: 'C12', customerName: 'Current customer', orderContactName: 'New order', businessContactName: 'New business' }])
  const wrapper = mount(OrderDialog, { props: { defaultSalesperson: 'Admin', row: { id: 99, ...validOrder(), businessContactName: 'Saved business', businessContactPhone: '101', orderContactName: '', orderContactPhone: '', customerContact: 'Legacy saved name', customerPhone: '102', financeContactName: 'Saved finance', financeContactPhone: '103', deliveryContact: 'Saved delivery', deliveryPhone: '104' } } })
  await flushPromises()
  expect((wrapper.get('[data-test="business-contact-name"]').element as HTMLInputElement).value).toBe('Saved business')
  expect((wrapper.get('[data-test="order-customer-contact"]').element as HTMLInputElement).value).toBe('Legacy saved name')
  expect((wrapper.get('[data-test="order-customer-phone"]').element as HTMLInputElement).value).toBe('102')
  expect((wrapper.get('[data-test="finance-contact-name"]').element as HTMLInputElement).value).toBe('Saved finance')
  expect((wrapper.get('[data-test="order-delivery-contact"]').element as HTMLInputElement).value).toBe('Saved delivery')
  wrapper.unmount()
})

it('saves edited order contacts in both snapshot and compatibility payload fields', async () => {
  loadOrderSkus.mockResolvedValue([])
  loadOrderCustomers.mockResolvedValue([])
  updateOrder.mockResolvedValue({})
  const wrapper = mount(OrderDialog, { props: { defaultSalesperson: 'Admin', row: { id: 100, version: 2, ...validOrder(), businessContactName: 'Business', businessContactPhone: '201', orderContactName: 'Order', orderContactPhone: '202', financeContactName: 'Finance', financeContactPhone: '203' } } })
  await flushPromises()
  await wrapper.get('[data-test="order-customer-contact"]').setValue('Edited order')
  await wrapper.get('[data-test="order-customer-phone"]').setValue('302')
  await wrapper.get('[data-test="finance-contact-phone"]').setValue('303')
  await wrapper.get('form').trigger('submit')
  expect(updateOrder).toHaveBeenCalledWith(100, expect.objectContaining({ businessContactName: 'Business', businessContactPhone: '201', orderContactName: 'Edited order', orderContactPhone: '302', financeContactName: 'Finance', financeContactPhone: '303', customerContact: 'Edited order', customerPhone: '302' }))
  wrapper.unmount()
})

it('renders inline errors for every basic and line validation field', async () => {
  const cases = [
    [{ ...validOrder(), orderDate: '' }, 'date-error'],
    [{ ...validOrder(), orderType: '' }, 'order-type-error'],
    [{ ...validOrder(), salesperson: '' }, 'salesperson-error'],
    [{ ...validOrder(), items: [{ skuId: null, quantity: 1, salePrice: 1 }] }, 'sku-error-0'],
    [{ ...validOrder(), items: [{ skuId: 1, quantity: 0, salePrice: 1 }] }, 'quantity-error-0'],
    [{ ...validOrder(), items: [{ skuId: 1, quantity: 1, salePrice: -1 }] }, 'price-error-0']
  ] as const
  for (const [row, selector] of cases) {
    loadOrderSkus.mockResolvedValue([sku(1)])
    loadOrderCustomers.mockResolvedValue([])
    const wrapper = mount(OrderDialog, { props: { row } })
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get(`[data-test="${selector}"]`).classes()).toContain('field-error')
    wrapper.unmount()
  }
})

it('shows the selected product image and current inventory in the order line', async () => {
  loadOrderSkus.mockResolvedValue([{ ...sku(1), primaryImageId: 91, actualQuantity: 12, availableQuantity: 9, supplyDemandSurplus: 12 }])
  loadOrderCustomers.mockResolvedValue([])
  const wrapper = mount(OrderDialog, { attachTo: document.body, props: { defaultSalesperson: 'Admin' } })
  await flushPromises()
  await choose(wrapper, '[data-test="order-sku-picker-0"]', 'SKU-1', 1)
  expect(wrapper.get('[data-test="order-product-image-0"]').attributes('src')).toBe('/api/product-images/91/content')
  expect(wrapper.get('[data-test="order-inventory-0"]').text()).toContain('实际库存12')
  expect(wrapper.get('[data-test="order-inventory-0"]').text()).toContain('供需余量12')
  wrapper.unmount()
})

it('shows an image placeholder when the selected product has no primary image', async () => {
  loadOrderSkus.mockResolvedValue([{ ...sku(1), actualQuantity: 3, availableQuantity: 2 }])
  loadOrderCustomers.mockResolvedValue([])
  const wrapper = mount(OrderDialog, { attachTo: document.body, props: { defaultSalesperson: 'Admin' } })
  await flushPromises()
  await choose(wrapper, '[data-test="order-sku-picker-0"]', 'SKU-1', 1)
  expect(wrapper.get('[data-test="order-image-placeholder-0"]').text()).toBe('暂无图片')
  wrapper.unmount()
})
it('renders order details as responsive product panels', async () => {
  loadOrderSkus.mockResolvedValue([sku(1)])
  loadOrderCustomers.mockResolvedValue([])
  const wrapper = mount(OrderDialog, { props: { defaultSalesperson: 'Admin' } })
  await flushPromises()
  expect(wrapper.findAll('.order-line-panel')).toHaveLength(1)
  expect(wrapper.get('.order-line-panel').find('.order-line-fields').exists()).toBe(true)
  expect(wrapper.get('.order-line-panel').find('.order-line-metrics').exists()).toBe(true)
  wrapper.unmount()
})

it('loads the active contract price after choosing a customer and product', async () => {
  loadOrderSkus.mockResolvedValue([sku(1)])
  loadOrderCustomers.mockResolvedValue([{ id: 9, customerCode: 'C9', customerName: 'Customer' }])
  loadContractPrice.mockResolvedValue(456.78)
  const wrapper = mount(OrderDialog, { attachTo: document.body, props: { defaultSalesperson: 'Admin' } })
  await flushPromises()
  await choose(wrapper, '[data-test="order-customer-picker"]', 'Customer', 9)
  await choose(wrapper, '[data-test="order-sku-picker-0"]', 'SKU-1', 1)
  expect(loadContractPrice).toHaveBeenCalledWith(9, 1)
  expect((priceInput(wrapper).element as HTMLInputElement).value).toBe('456.78')
  wrapper.unmount()
})

it('ignores a stale contract price after switching customers', async () => {
  const first = deferred<number | null>(); const second = deferred<number | null>()
  loadOrderSkus.mockResolvedValue([sku(1)])
  loadOrderCustomers.mockResolvedValue([{ id: 1, customerCode: 'C1', customerName: 'First' }, { id: 2, customerCode: 'C2', customerName: 'Second' }])
  loadContractPrice.mockImplementationOnce(() => first.promise).mockImplementationOnce(() => second.promise)
  const wrapper = mount(OrderDialog, { attachTo: document.body, props: { defaultSalesperson: 'Admin' } })
  await flushPromises()
  await choose(wrapper, '[data-test="order-customer-picker"]', 'First', 1)
  await choose(wrapper, '[data-test="order-sku-picker-0"]', 'SKU-1', 1)
  await choose(wrapper, '[data-test="order-customer-picker"]', 'Second', 2)
  second.resolve(200); await flushPromises()
  first.resolve(100); await flushPromises()
  expect((priceInput(wrapper).element as HTMLInputElement).value).toBe('200')
  wrapper.unmount()
})

it('ignores a stale contract price after changing the SKU', async () => {
  const first = deferred<number | null>(); const second = deferred<number | null>()
  loadOrderSkus.mockResolvedValue([sku(1), sku(2)])
  loadOrderCustomers.mockResolvedValue([{ id: 1, customerCode: 'C1', customerName: 'Customer' }])
  loadContractPrice.mockImplementationOnce(() => first.promise).mockImplementationOnce(() => second.promise)
  const wrapper = mount(OrderDialog, { attachTo: document.body, props: { defaultSalesperson: 'Admin' } })
  await flushPromises()
  await choose(wrapper, '[data-test="order-customer-picker"]', 'Customer', 1)
  await choose(wrapper, '[data-test="order-sku-picker-0"]', 'SKU-1', 1)
  await choose(wrapper, '[data-test="order-sku-picker-0"]', 'SKU-2', 2)
  second.resolve(200); await flushPromises()
  first.resolve(100); await flushPromises()
  expect((priceInput(wrapper).element as HTMLInputElement).value).toBe('200')
  wrapper.unmount()
})

it('does not apply a stale price to the row shifted after deleting the request row', async () => {
  const stale = deferred<number | null>()
  loadOrderSkus.mockResolvedValue([sku(1)])
  loadOrderCustomers.mockResolvedValue([{ id: 1, customerCode: 'C1', customerName: 'Customer' }])
  loadContractPrice.mockImplementationOnce(() => stale.promise)
  const wrapper = mount(OrderDialog, { attachTo: document.body, props: { defaultSalesperson: 'Admin' } })
  await flushPromises()
  await choose(wrapper, '[data-test="order-customer-picker"]', 'Customer', 1)
  await choose(wrapper, '[data-test="order-sku-picker-0"]', 'SKU-1', 1)
  await wrapper.get('[data-test="add-order-line"]').trigger('click')
  await wrapper.get('[data-test="remove-order-line-0"]').trigger('click')
  stale.resolve(100); await flushPromises()
  expect((priceInput(wrapper).element as HTMLInputElement).value).toBe('0')
  wrapper.unmount()
})

it('requires one of the three supported order types', async () => {
  loadOrderSkus.mockResolvedValue([sku(1)])
  loadOrderCustomers.mockResolvedValue([])
  const wrapper = mount(OrderDialog, { props: { defaultSalesperson: 'Admin' } })
  await flushPromises()
  const select = wrapper.get('[data-test="order-type-select"]')
  expect(select.findAll('option').map(option => option.text())).toEqual(['请选择订单类型', '工程订单', '零售订单', '前置订单'])
  expect((select.element as HTMLSelectElement).value).toBe('')
  wrapper.unmount()
})
it('shows the post-order supply-demand shortage while creating an order', async () => {
  loadOrderSkus.mockResolvedValue([{ ...sku(1), actualQuantity: 5, inTransitQuantity: 4, pendingDeliveryQuantity: 2, supplyDemandSurplus: 7 }])
  loadOrderCustomers.mockResolvedValue([])
  const wrapper = mount(OrderDialog, { attachTo: document.body, props: { defaultSalesperson: 'Admin' } })
  await flushPromises()
  await choose(wrapper, '[data-test="order-sku-picker-0"]', 'SKU-1', 1)
  await wrapper.findAll('input[type="number"]')[0].setValue('10')
  const inventory = wrapper.get('[data-test="order-inventory-0"]')
  expect(inventory.text()).toContain('实际库存5')
  expect(inventory.text()).toContain('在途数量4')
  expect(inventory.text()).toContain('全局未发货2')
  expect(inventory.text()).toContain('供需余量7')
  expect(inventory.text()).toContain('下单后供需余量-3')
  expect(inventory.text()).toContain('下单后采购缺口 3')
  wrapper.unmount()
})

it('restores the original remainder before previewing an edited order', async () => {
  loadOrderSkus.mockResolvedValue([{ ...sku(1), supplyDemandSurplus: 7 }])
  loadOrderCustomers.mockResolvedValue([])
  const wrapper = mount(OrderDialog, { props: { row: { id: 9, version: 1, ...validOrder(), items: [{ id: 51, skuId: 1, quantity: 4, shippedQuantity: 0, remainingQuantity: 4, salePrice: 1 }] } } })
  await flushPromises()
  await wrapper.findAll('input[type="number"]')[0].setValue('6')
  expect(wrapper.get('[data-test="order-inventory-0"]').text()).toContain('下单后供需余量5')
  wrapper.unmount()
})


