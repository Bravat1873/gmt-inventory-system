import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import { markRaw } from 'vue'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import ModuleListPage from './ModuleListPage.vue'
import { moduleDefinitions } from '../modules/module-config'

const { loadModule, loadUnconfiguredProcurementShortages } = vi.hoisted(() => ({ loadModule: vi.fn(), loadUnconfiguredProcurementShortages: vi.fn() }))
vi.mock('../api/workbench', () => ({ loadModule, loadUnconfiguredProcurementShortages }))

it('shows supplier configuration alerts only in purchase management', async () => {
  loadModule.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 })
  loadUnconfiguredProcurementShortages.mockResolvedValue([
    { skuId: 70, customerPartNumber: 'F70', productName: 'F70', shortageQuantity: 200, orderNumbers: ['DD20260800002'] }
  ])
  const purchase = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'purchase')! } })
  await flushPromises()
  expect(purchase.text()).toContain('1 个缺货产品尚未配置有效供应商采购信息')

  const order = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'order')! } })
  await flushPromises()
  expect(order.text()).not.toContain('缺货产品尚未配置有效供应商采购信息')
})
it('shows the PRODUCT import action to administrators', async () => {
  loadModule.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'product')!, currentUserRole: 'ADMIN' } })
  await flushPromises()

  expect(wrapper.get('[data-test="primary-action"]').text()).toBe('导入产品')
})

it.each(['ADMIN', 'USER'] as const)('shows separate order creation and import actions to %s users', async role => {
  loadModule.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'order')!, currentUserRole: role } })
  await flushPromises()

  expect(wrapper.get('[data-test="primary-action"]').text()).toBe('新增订单')
  expect(wrapper.get('[data-test="import-action"]').text()).toBe('导入订单')
  await wrapper.get('[data-test="import-action"]').trigger('click')
  expect(wrapper.emitted('import')).toHaveLength(1)
})

it.each(['order', 'afterSales', 'purchase'] as const)('shows a row document export and a page summary export for %s', async key => {
  loadModule.mockResolvedValue({ items: [{ id: 1, recordType: key === 'purchase' ? 'PURCHASE' : undefined }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === key)! } })
  await flushPromises()

  expect(wrapper.get('[data-test="export-document-row"]').text()).toBe('单据')
  expect(wrapper.get('[data-test="export-summary-action"]').text()).toBe('导出汇总数据')
})

it('emits the selected record when exporting a document', async () => {
  loadModule.mockResolvedValue({ items: [{ id: 12, orderNo: 'DD20260800012' }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'order')! } })
  await flushPromises()

  await wrapper.get('[data-test="export-document-row"]').trigger('click')
  expect(wrapper.emitted('exportDocument')?.[0]?.[0]).toMatchObject({ id: 12, orderNo: 'DD20260800012' })
})

it.each(['product', 'inventory'] as const)('shows only summary export action for %s', async key => {
  loadModule.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === key)! } })
  await flushPromises()

  expect(wrapper.find('[data-test="export-document-row"]').exists()).toBe(false)
  expect(wrapper.get('[data-test="export-summary-action"]').text()).toBe('导出汇总数据')
})

it('shows a finance summary export action', async () => {
  loadModule.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'finance')! } })
  await flushPromises()

  expect(wrapper.get('[data-test="export-summary-action"]').text()).toBe('导出财务汇总数据')
  await wrapper.get('[data-test="export-summary-action"]').trigger('click')
  expect(wrapper.emitted('exportSummary')).toHaveLength(1)
})

it('hides every order creation entry from finance users', async () => {
  loadModule.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'order')!, currentUserRole: 'FINANCE' } })
  await flushPromises()

  expect(wrapper.find('[data-test="primary-action"]').exists()).toBe(false)
  expect(wrapper.find('[data-test="import-action"]').exists()).toBe(false)
})

it.each(['FINANCE', 'USER'] as const)('hides the PRODUCT replace action from %s users', async role => {
  loadModule.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'product')!, currentUserRole: role } })
  await flushPromises()

  expect(wrapper.find('[data-test="primary-action"]').exists()).toBe(false)
})

it('keeps non-COST import actions available to regular users', async () => {
  loadModule.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'inventory')!, currentUserRole: 'USER' } })
  await flushPromises()

  expect(wrapper.get('[data-test="primary-action"]').text()).toBe('导入库存')
})

it('renders finance direction dots at the same size and with the same soft outer ring as order status', () => {
  const styles = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8')

  expect(styles).toContain('.finance-direction i { width:8px; height:8px; flex:0 0 8px; border-radius:50%; }')
  expect(styles).toContain('.finance-direction.receivable i { background:#22a06b; box-shadow:0 0 0 3px #e6f7ef; }')
  expect(styles).toContain('.finance-direction.payable i { background:#e5484d; box-shadow:0 0 0 3px #ffebec; }')
})

it.each(['order', 'finance'] as const)('emits the original %s row from its invoice action', async key => {
  const row = markRaw({ id: 12, orderNo: 'DD20260800012', businessNo: 'SO001' })
  loadModule.mockResolvedValue({ items: [row], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === key)! } })
  await flushPromises()

  const invoice = wrapper.get('[data-test="invoice"]')
  expect(invoice.text()).toBe('发票')
  await invoice.trigger('click')
  expect(wrapper.emitted('invoice')?.[0]?.[0]).toBe(row)
})

it('emits invoices for actual purchases but not purchase suggestions', async () => {
  const purchaseRow = markRaw({ id: 1, recordType: 'PURCHASE' })
  loadModule.mockResolvedValue({ items: [
    purchaseRow,
    { id: 2, recordType: 'SUGGESTION' }
  ], total: 2, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'purchase')! } })
  await flushPromises()

  expect(wrapper.findAll('[data-test="invoice"]')).toHaveLength(1)
  await wrapper.get('[data-test="invoice"]').trigger('click')
  expect(wrapper.emitted('invoice')?.[0]?.[0]).toBe(purchaseRow)
})

it('keeps all finance actions in one horizontal sticky action column', async () => {
  loadModule.mockResolvedValue({ items: [{
    id: 1,
    cashDirection: 'RECEIVABLE',
    businessType: '销售订单',
    outstandingAmount: 120,
    pendingReviewCount: 1
  }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'finance')! } })
  await flushPromises()

  expect(wrapper.findAll('col').at(-1)?.attributes('style')).toContain('width: 280px')
  expect(wrapper.get('.finance-actions').element.tagName).toBe('DIV')
  const styles = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8')
  expect(styles).toContain('.row-actions-content.finance-actions { display:flex; flex-wrap:nowrap;')
})

it.each(['order', 'purchase', 'finance'] as const)('right-aligns the shared action header for %s', async key => {
  loadModule.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === key)! } })
  await flushPromises()

  expect(wrapper.get('.action-column-header').text()).toBe('操作')
})

it.each([['order', 480], ['purchase', 380], ['finance', 280]] as const)('uses a single-line %s action column', async (key, width) => {
  loadModule.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === key)! } })
  await flushPromises()

  expect(wrapper.findAll('col').at(-1)?.attributes('style')).toContain(`width: ${width}px`)
})

it('keeps order actions on one line without clipping them', () => {
  const styles = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8')

  expect(styles).toContain('.row-actions-content { display:flex; flex-wrap:nowrap;')
  expect(styles).toContain('min-width:max-content;')
})

it.each(['order', 'purchase', 'finance'] as const)('provides a 查看 button for %s records', async key => {
  loadModule.mockResolvedValue({ items: [{ id: 1, status: 'DRAFT', recordType: key === 'purchase' ? 'PURCHASE' : undefined }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === key)! } })
  await flushPromises()

  expect(wrapper.get('[data-test="view-details"]').text()).toBe('查看')
})

it('shows review and delete actions for draft orders and delete for reviewed orders', async () => {
  loadModule.mockResolvedValue({ items: [
    { id: 1, status: 'DRAFT' },
    { id: 2, status: 'READY_TO_SHIP' }
  ], total: 2, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'order')! } })
  await flushPromises()

  const actions = wrapper.findAll('.row-actions')
  expect(actions[0].text()).toContain('复核')
  expect(actions[0].text()).toContain('删除')
  expect(actions[1].text()).not.toContain('复核')
  expect(actions[1].text()).toContain('删除')
})

it('shortens customer, order, after-sales and finance row action labels', async () => {
  loadModule.mockResolvedValueOnce({ items: [{ id: 1 }], total: 1, page: 1, pageSize: 10, totalPages: 1 })

  const customer = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'customer')! } })
  await flushPromises()
  expect(customer.get('[data-test="customer-funds"]').text()).toBe('资金')

  loadModule.mockResolvedValueOnce({ items: [{ id: 1, status: 'READY_TO_SHIP' }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const order = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'order')! } })
  await flushPromises()
  expect(order.findAll('col').at(-1)?.attributes('style')).toContain('width: 480px')
  expect(order.get('[data-test="order-allocation"]').text()).toBe('分配')

  loadModule.mockResolvedValueOnce({ items: [{ id: 1, status: 'WAITING_RETURN' }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const afterSales = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'afterSales')! } })
  await flushPromises()
  expect(afterSales.get('.row-actions').text()).toContain('收货')

  loadModule.mockResolvedValueOnce({ items: [{ id: 1, cashDirection: 'RECEIVABLE', outstandingAmount: 120, pendingReviewCount: 2 }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const finance = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'finance')! } })
  await flushPromises()
  expect(finance.get('[data-test="finance-receipt"]').text()).toBe('登记')
  expect(finance.get('.row-actions').text()).toContain('复核')
  expect(finance.get('.row-actions').text()).not.toContain('复核（')
})

it('uses the exact same red and green shipment status dot for purchase status', async () => {
  loadModule.mockResolvedValue({ items: [{ id: 1, status: 'RECEIVED', recordType: 'PURCHASE' }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'purchase')! } })
  await flushPromises()

  const status = wrapper.get('.order-shipment-status')
  expect(status.get('.shipment-status-dot').classes()).toContain('complete')
})

it('shows finance direction dot in the business type cell and the matching automatic settlement action', async () => {
  loadModule.mockResolvedValue({ items: [{ id: 1, cashDirection: 'RECEIVABLE', businessType: '销售订单', status: '待收款', businessNo: 'SO001', outstandingAmount: 120 }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'finance')! } })
  await flushPromises()

  expect(wrapper.get('[data-test="finance-direction"]').attributes('aria-label')).toBe('收款')
  expect(wrapper.find('[data-test="finance-receipt"]').exists()).toBe(true)
})

  it('shows payment and receipt actions independently for an unfinished purchase', async () => {
  loadModule.mockResolvedValue({ items: [{ id: 1, recordType: 'PURCHASE', status: 'EXECUTING', outstandingAmount: 75, remainingQuantity: 6 }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'purchase')! } })
  await flushPromises()

  expect(wrapper.find('[data-test="purchase-payment"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="purchase-receipt"]').exists()).toBe(true)
  })

  it('keeps the header edit action available for a partially paid system purchase', async () => {
    loadModule.mockResolvedValue({ items: [{
      id: 1,
      recordType: 'PURCHASE',
      manualEntry: 0,
      paidAmount: 30,
      receivedQuantity: 0
    }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
    const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'purchase')! } })
    await flushPromises()

    expect(wrapper.get('.row-actions-content').text()).toContain('修改')
  })

it('reserves enough sticky action-column width for every purchase action', async () => {
  loadModule.mockResolvedValue({ items: [{ id: 1, recordType: 'PURCHASE', outstandingAmount: 75, remainingQuantity: 6 }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'purchase')! } })
  await flushPromises()

  const columns = wrapper.findAll('col')
    expect(columns.at(-1)?.attributes('style')).toContain('width: 380px')
  expect(wrapper.get('.row-actions-content').text()).toContain('单据')
  expect(wrapper.get('.row-actions-content').text()).toContain('登记')
  expect(wrapper.get('.row-actions-content').text()).toContain('收货')
})

it('hides only the completed purchase action', async () => {
  loadModule.mockResolvedValue({ items: [{ id: 1, recordType: 'PURCHASE', status: 'EXECUTING', outstandingAmount: 0, remainingQuantity: 6 }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'purchase')! } })
  await flushPromises()

  expect(wrapper.find('[data-test="purchase-payment"]').exists()).toBe(false)
  expect(wrapper.find('[data-test="purchase-receipt"]').exists()).toBe(true)
})

it('formats inventory age zero and dates using the list display convention', async () => {
  loadModule.mockResolvedValue({ items: [{ id: 1, oldestStockDate: '2026-08-01T09:10:11', inventoryAgeDays: 0 }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'inventory')! } })
  await flushPromises()

  expect(wrapper.text()).toContain('2026-08-01 09:10:11')
  expect(wrapper.text()).toContain('0 天')
})

it('renders the product primary image, count badge and opens its gallery', async () => {
  loadModule.mockResolvedValue({
    items: [{ id: 7, primaryImageId: 9, primaryImageUrl: '/api/product-images/9/content', imageCount: 5 }],
    total: 1, page: 1, pageSize: 10, totalPages: 1
  })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'product')! } })
  await flushPromises()

  const thumbnail = wrapper.get('[data-test="product-thumbnail"]')
  expect(thumbnail.get('img').attributes('src')).toBe('/api/product-images/9/content')
  expect(wrapper.get('[data-test="product-image-count"]').text()).toBe('5')
  await thumbnail.trigger('click')
  expect(wrapper.emitted('gallery')?.[0]?.[0]).toMatchObject({ id: 7 })
})

it('renders a clickable no-image placeholder that opens the empty gallery', async () => {
  loadModule.mockResolvedValue({ items: [{ id: 8, imageCount: 0 }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'product')! } })
  await flushPromises()

  const placeholder = wrapper.get('[data-test="product-thumbnail"]')
  expect(placeholder.text()).toContain('暂无图片')
  await placeholder.trigger('click')
  expect(wrapper.emitted('gallery')?.[0]?.[0]).toMatchObject({ id: 8 })
})

it.each([
  ['ADMIN', '管理员'],
  ['FINANCE', '财务'],
  ['USER', '普通用户']
])('shows the %s user role as %s', async (role, label) => {
  loadModule.mockResolvedValue({ items: [{ id: 1, username: 'user', displayName: '用户', role }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'user')! } })
  await flushPromises()

  expect(wrapper.text()).toContain(label)
  expect(wrapper.text()).not.toContain(`>${role}<`)
})

it.each([
  ['FINISHED_PRODUCT', '成品'],
  ['PART', '零件']
])('renders product material type %s as %s', async (materialType, label) => {
  loadModule.mockResolvedValue({ items: [{ id: 7, materialType }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'product')! } })
  await flushPromises()
  expect(wrapper.text()).toContain(label)
})
it('shows only the edit action for inventory rows', async () => {
  loadModule.mockResolvedValue({ items: [{ id: 1, customerPartNumber: 'P50' }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'inventory')! } })
  await flushPromises()
  const actions = wrapper.get('.row-actions')
  expect(actions.text()).toBe('修改')
  expect(actions.find('[data-test="inventory-details"]').exists()).toBe(false)
})
it('highlights negative inventory supply-demand surplus as a purchase shortage', async () => {
  loadModule.mockResolvedValue({ items: [{ id: 1, supplyDemandSurplus: -6, purchaseShortageQuantity: 6 }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'inventory')! } })
  await flushPromises()
  const balance = wrapper.get('[data-test="supply-demand-surplus"]')
  expect(balance.classes()).toContain('negative')
  expect(balance.text()).toContain('-6')
  expect(balance.text()).toContain('采购缺口 6')
})
it('keeps supplier quotes on one line and exposes all quotes in the title', async () => {
  loadModule.mockResolvedValue({
    items: [{ id: 7, supplierQuotes: [
      { supplierId: 1, supplierName: '供应商甲', purchasePrice: 100 },
      { supplierId: 2, supplierName: '供应商乙', purchasePrice: 105.5 }
    ] }],
    total: 1, page: 1, pageSize: 10, totalPages: 1
  })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'product')! } })
  await flushPromises()

  const quotes = wrapper.get('[data-test="supplier-quotes"]')
  expect(quotes.text()).toBe('供应商甲：¥100 + 其余 1 家')
  expect(quotes.findAll('span')).toHaveLength(0)
  expect(quotes.attributes('title')).toBe('供应商甲：¥100\n供应商乙：¥105.5')
})


