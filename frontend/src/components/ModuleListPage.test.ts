import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import ModuleListPage from './ModuleListPage.vue'
import { moduleDefinitions } from '../modules/module-config'

const { loadModule } = vi.hoisted(() => ({ loadModule: vi.fn() }))
vi.mock('../api/workbench', () => ({ loadModule }))

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

it('shows finance direction dot in the business type cell and the matching automatic settlement action', async () => {
  loadModule.mockResolvedValue({ items: [{ id: 1, cashDirection: 'RECEIVABLE', businessType: '销售订单', status: '待收款', businessNo: 'SO001', outstandingAmount: 120 }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'finance')! } })
  await flushPromises()

  expect(wrapper.get('[data-test="finance-direction"]').attributes('aria-label')).toBe('收款')
  expect(wrapper.find('[data-test="finance-receipt"]').exists()).toBe(true)
})
