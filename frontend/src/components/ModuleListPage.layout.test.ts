import { flushPromises, mount } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, it, vi } from 'vitest'
import ModuleListPage from './ModuleListPage.vue'
import { moduleDefinitions } from '../modules/module-config'

const { loadModule } = vi.hoisted(() => ({ loadModule: vi.fn() }))
vi.mock('../api/workbench', () => ({ loadModule }))

it('reserves enough width for all order action buttons', async () => {
  loadModule.mockResolvedValue({ items: [{ id: 1, status: 'PENDING_CUSTOMER_PAYMENT' }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'order')! } })
  await flushPromises()

  expect(wrapper.get('colgroup col:last-child').attributes('style')).toContain('390px')
})

it('reserves enough width for all after-sales action buttons', async () => {
  loadModule.mockResolvedValue({ items: [{ id: 1, status: 'WAITING_RETURN' }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'afterSales')! } })
  await flushPromises()

  expect(wrapper.get('colgroup col:last-child').attributes('style')).toContain('300px')
})
it('keeps ten equal rows within the viewport and leaves the horizontal scrollbar visible', () => {
  const styles = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8')

  expect(styles).toMatch(/--table-row-height:\s*68px/)
  expect(styles).toMatch(/\.table-wrap\s*\{[^}]*flex:\s*1\s+1\s+auto[^}]*min-height:\s*0[^}]*overflow:\s*auto[^}]*\}/s)
  expect(styles).toMatch(/tbody tr\s*\{[^}]*height:\s*var\(--table-row-height\)[^}]*\}/s)
  expect(styles).toMatch(/td\s*\{[^}]*box-sizing:\s*border-box[^}]*height:\s*var\(--table-row-height\)[^}]*\}/s)
  expect(styles).toMatch(/@media\s*\(max-height:\s*900px\)[\s\S]*?--table-row-height:\s*52px/)
  expect(styles).toMatch(/@media\s*\(max-height:\s*900px\)[\s\S]*?\.product-thumbnail[^}]*width:\s*42px[^}]*height:\s*42px/)
})

it('keeps the operation column visible and truncates long cell text without changing row height', () => {
  const styles = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8')
  const cellContentStyles = styles.match(/\.cell-content\s*\{([^}]*)\}/s)?.[1] ?? ''

  expect(styles).toMatch(/\.row-actions\s*\{[^}]*position:\s*sticky[^}]*right:\s*0[^}]*\}/s)
  expect(cellContentStyles).toMatch(/white-space:\s*nowrap/)
  expect(cellContentStyles).toMatch(/text-overflow:\s*ellipsis/)
})
