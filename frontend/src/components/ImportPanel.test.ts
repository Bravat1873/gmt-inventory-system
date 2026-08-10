import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ImportPanel from './ImportPanel.vue'

const { previewImport, commitImport } = vi.hoisted(() => ({ previewImport: vi.fn(), commitImport: vi.fn() }))
vi.mock('../api/imports', () => ({ previewImport, commitImport }))

const batch = {
  batchId: 8, importType: 'CUSTOMER', originalFilename: 'customers.xlsx', status: 'PREVIEW',
  totalRows: 103, validRows: 103, errorRows: 0, ignoredRows: 0, committedRows: 0, result: null, rows: []
}

describe('simple Excel import', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    previewImport.mockResolvedValue(structuredClone(batch))
    commitImport.mockResolvedValue({ ...structuredClone(batch), status: 'COMMITTED', committedRows: 103 })
  })

  it('imports immediately after the user selects a file without showing batch details', async () => {
    const wrapper = mount(ImportPanel, { props: { type: 'CUSTOMER', title: '导入客户' } })
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['x'], 'customers.xlsx')] })
    await input.trigger('change')
    await flushPromises()

    expect(previewImport).toHaveBeenCalledWith('CUSTOMER', expect.any(File))
    expect(commitImport).toHaveBeenCalledWith(8)
    expect(wrapper.text()).toContain('成功导入 103 条数据')
    expect(wrapper.text()).not.toContain('导入批次')
    expect(wrapper.find('[data-test="commit-import"]').exists()).toBe(false)
  })
  it('accepts both legacy and current Excel supplier files', () => {
    const wrapper = mount(ImportPanel, { props: { type: 'SUPPLIER', title: '导入供应商' } })

    expect(wrapper.get('input[type="file"]').attributes('accept')).toBe('.xls,.xlsx')
  })
  it('defaults supplier imports to overwrite mode', () => {
    const wrapper = mount(ImportPanel, { props: { type: 'SUPPLIER', title: '导入供应商' } })

    expect(wrapper.get('[data-test="supplier-mode-overwrite"]').attributes('aria-checked')).toBe('true')
    expect(wrapper.get('[data-test="supplier-mode-replace-all"]').attributes('aria-checked')).toBe('false')
    expect(wrapper.text()).toContain('覆盖更新')
  })

  it('asks for a second confirmation before committing full replacement', async () => {
    previewImport.mockResolvedValue({ ...structuredClone(batch), importType: 'SUPPLIER' })
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mount(ImportPanel, { props: { type: 'SUPPLIER', title: '导入供应商' } })
    await wrapper.get('[data-test="supplier-mode-replace-all"]').setValue()
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['x'], 'suppliers.xlsx')] })
    await input.trigger('change')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith('全量替换将停用文件中不存在的供应商，是否继续？')
    expect(commitImport).toHaveBeenCalledWith(8, 'REPLACE_ALL')
  })

  it('does not commit full replacement when the second confirmation is cancelled', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const wrapper = mount(ImportPanel, { props: { type: 'SUPPLIER', title: '导入供应商' } })
    await wrapper.get('[data-test="supplier-mode-replace-all"]').setValue()
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['x'], 'suppliers.xlsx')] })
    await input.trigger('change')
    await flushPromises()

    expect(commitImport).not.toHaveBeenCalled()
  })
})
