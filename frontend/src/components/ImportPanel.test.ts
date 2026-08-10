import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ImportPanel from './ImportPanel.vue'

const { previewImport, commitImport } = vi.hoisted(() => ({ previewImport: vi.fn(), commitImport: vi.fn() }))
vi.mock('../api/imports', () => ({ previewImport, commitImport }))

const batch = {
  batchId: 8, importType: 'CUSTOMER', originalFilename: 'customers.xlsx', status: 'PREVIEW',
  totalRows: 103, validRows: 103, errorRows: 0, ignoredRows: 0, committedRows: 0, result: null, rows: []
}
const supplierBatch = {
  ...batch,
  importType: 'SUPPLIER',
  originalFilename: 'suppliers.xlsx',
  totalRows: 2,
  validRows: 1,
  errorRows: 1,
  rows: [
    { id: 1, sheetName: '供应商', rowNumber: 3, status: 'VALID', data: { supplierName: '星云科技', phone: ' 00123 ' }, errorMessage: null, manualEntry: false },
    { id: 2, sheetName: '供应商', rowNumber: 4, status: 'ERROR', data: { supplierName: '', taxRegistrationNo: '0007' }, errorMessage: '供应商名称不能为空', manualEntry: false }
  ]
}

async function selectFile(wrapper: ReturnType<typeof mount>, filename = 'suppliers.xlsx') {
  const input = wrapper.get('input[type="file"]')
  Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['x'], filename)] })
  await input.trigger('change')
  await flushPromises()
}

describe('simple Excel import', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.clearAllMocks()
    previewImport.mockResolvedValue(structuredClone(batch))
    commitImport.mockResolvedValue({ ...structuredClone(batch), status: 'COMMITTED', committedRows: 103 })
  })

  it('keeps the existing immediate flow for non-supplier imports', async () => {
    const wrapper = mount(ImportPanel, { props: { type: 'CUSTOMER', title: '导入客户' } })
    await selectFile(wrapper, 'customers.xlsx')

    expect(previewImport).toHaveBeenCalledWith('CUSTOMER', expect.any(File))
    expect(commitImport).toHaveBeenCalledWith(8)
    expect(wrapper.text()).toContain('成功导入 103 条数据')
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
  })

  it('shows supplier valid rows, error rows and normalized fields without committing', async () => {
    previewImport.mockResolvedValue(structuredClone(supplierBatch))
    const wrapper = mount(ImportPanel, { props: { type: 'SUPPLIER', title: '导入供应商' } })
    await selectFile(wrapper)

    expect(commitImport).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="preview-total-count"]').text()).toBe('2')
    expect(wrapper.get('[data-test="preview-valid-count"]').text()).toBe('1')
    expect(wrapper.get('[data-test="preview-error-count"]').text()).toBe('1')
    expect(wrapper.findAll('[data-test="preview-row"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('星云科技')
    expect(wrapper.text()).toContain(' 00123 ')
    expect(wrapper.text()).toContain('供应商名称不能为空')
  })

  it('commits the supplier preview only after the explicit confirmation button is clicked', async () => {
    previewImport.mockResolvedValue({ ...structuredClone(supplierBatch), errorRows: 0, totalRows: 1, rows: [supplierBatch.rows[0]] })
    commitImport.mockResolvedValue({ ...structuredClone(supplierBatch), status: 'COMMITTED', committedRows: 1 })
    const wrapper = mount(ImportPanel, { props: { type: 'SUPPLIER', title: '导入供应商' } })
    await selectFile(wrapper)

    expect(commitImport).not.toHaveBeenCalled()
    await wrapper.get('[data-test="commit-import"]').trigger('click')
    await flushPromises()

    expect(commitImport).toHaveBeenCalledWith(8, 'OVERWRITE')
    expect(wrapper.text()).toContain('成功导入 1 条数据')
  })

  it('asks for a second confirmation only when committing full replacement', async () => {
    previewImport.mockResolvedValue({ ...structuredClone(supplierBatch), errorRows: 0, totalRows: 1, rows: [supplierBatch.rows[0]] })
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mount(ImportPanel, { props: { type: 'SUPPLIER', title: '导入供应商' } })
    await wrapper.get('[data-test="supplier-mode-replace-all"]').setValue()
    await selectFile(wrapper)

    expect(confirm).not.toHaveBeenCalled()
    expect(commitImport).not.toHaveBeenCalled()
    await wrapper.get('[data-test="commit-import"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith('全量替换将停用文件中不存在的供应商，是否继续？')
    expect(commitImport).toHaveBeenCalledWith(8, 'REPLACE_ALL')
  })

  it('keeps the preview and does not commit when full replacement is cancelled', async () => {
    previewImport.mockResolvedValue({ ...structuredClone(supplierBatch), errorRows: 0, totalRows: 1, rows: [supplierBatch.rows[0]] })
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const wrapper = mount(ImportPanel, { props: { type: 'SUPPLIER', title: '导入供应商' } })
    await wrapper.get('[data-test="supplier-mode-replace-all"]').setValue()
    await selectFile(wrapper)
    await wrapper.get('[data-test="commit-import"]').trigger('click')

    expect(commitImport).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="preview-row"]').exists()).toBe(true)
  })
})
