import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import ModuleListPage from './ModuleListPage.vue'
import { moduleDefinitions } from '../modules/module-config'

const { loadModule } = vi.hoisted(() => ({ loadModule: vi.fn() }))
vi.mock('../api/workbench', () => ({ loadModule }))

it.each(['ADMIN', 'FINANCE'] as const)('shows the COST import action to %s users', async role => {
  loadModule.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'product')!, currentUserRole: role } })
  await flushPromises()

  expect(wrapper.get('[data-test="primary-action"]').text()).toBe('导入产品')
})

it('hides the COST import action from regular users', async () => {
  loadModule.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'product')!, currentUserRole: 'USER' } })
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

it.each(['order', 'purchase', 'finance'] as const)('provides a 查看 button for %s records', async key => {
  loadModule.mockResolvedValue({ items: [{ id: 1, status: 'DRAFT', recordType: key === 'purchase' ? 'PURCHASE' : undefined }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === key)! } })
  await flushPromises()

  expect(wrapper.get('[data-test="view-details"]').text()).toBe('查看')
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
  loadModule.mockResolvedValue({ items: [{ id: 1, skuCode: 'P50' }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'inventory')! } })
  await flushPromises()
  const actions = wrapper.get('.row-actions')
  expect(actions.text()).toBe('修改')
  expect(actions.find('[data-test="inventory-details"]').exists()).toBe(false)
})
it('highlights negative inventory supply-demand balance as a purchase shortage', async () => {
  loadModule.mockResolvedValue({ items: [{ id: 1, supplyDemandBalance: -6, purchaseShortageQuantity: 6 }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'inventory')! } })
  await flushPromises()
  const balance = wrapper.get('[data-test="supply-demand-balance"]')
  expect(balance.classes()).toContain('negative')
  expect(balance.text()).toContain('-6')
  expect(balance.text()).toContain('采购缺口 6')
})
it('renders every supplier quote on its own line in the product list', async () => {
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
  expect(quotes.findAll('span').map(item => item.text())).toEqual(['供应商甲：¥100', '供应商乙：¥105.5'])
})