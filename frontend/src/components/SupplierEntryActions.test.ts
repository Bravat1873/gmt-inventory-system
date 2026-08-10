import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import ModuleListPage from './ModuleListPage.vue'
import { moduleDefinitions } from '../modules/module-config'

const { loadModule } = vi.hoisted(() => ({ loadModule: vi.fn() }))
vi.mock('../api/workbench', () => ({ loadModule }))

it('keeps both manual creation and spreadsheet import available for suppliers', async () => {
  loadModule.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 })
  const wrapper = mount(ModuleListPage, {
    props: { module: moduleDefinitions.find(item => item.key === 'supplier')!, currentUserRole: 'USER' }
  })
  await flushPromises()

  const buttons = wrapper.findAll('.heading-actions button')
  expect(buttons.map(button => button.text())).toEqual(['手工新增', '导入供应商'])

  await buttons[0].trigger('click')
  await buttons[1].trigger('click')
  expect(wrapper.emitted('manual')).toHaveLength(1)
  expect(wrapper.emitted('action')).toHaveLength(1)
})
