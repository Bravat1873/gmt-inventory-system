import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import ModuleListPage from './ModuleListPage.vue'
import { moduleDefinitions } from '../modules/module-config'

const { loadModule } = vi.hoisted(() => ({ loadModule: vi.fn() }))
vi.mock('../api/workbench', () => ({ loadModule }))

it('reserves enough width for all order action buttons', async () => {
  loadModule.mockResolvedValue({ items: [{ id: 1, status: 'PENDING_CUSTOMER_PAYMENT' }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: moduleDefinitions.find(item => item.key === 'order')! } })
  await flushPromises()

  expect(wrapper.get('colgroup col:last-child').attributes('style')).toContain('292px')
})
