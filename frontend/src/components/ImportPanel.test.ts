import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ImportPanel from './ImportPanel.vue'

const { previewImport, commitImport, commitProductReplace } = vi.hoisted(() => ({ previewImport: vi.fn(), commitImport: vi.fn(), commitProductReplace: vi.fn() }))
vi.mock('../api/imports', () => ({ previewImport, commitImport, commitProductReplace }))

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

const productConflictBatch = {
  ...batch,
  importType: 'PRODUCT',
  originalFilename: 'products.xlsx',
  totalRows: 2,
  validRows: 2,
  rows: [
    { id: 21, sheetName: 'GMT库存产品清单', rowNumber: 9, status: 'VALID', data: { sourceProductCode: '旧编号-A', productCode: 'NEW-A', model: 'D51', productConfiguration: '黑色', supplierName: '供应商甲', supplierPrice: 10, _conflictGroup: 'NEW-A', _conflictAction: 'UNRESOLVED' }, errorMessage: null, manualEntry: false },
    { id: 22, sheetName: '贝朗库存产品清单', rowNumber: 9, status: 'VALID', data: { sourceProductCode: '旧编号-B', productCode: 'NEW-A', model: 'D52', productConfiguration: '银色', supplierName: '供应商乙', supplierPrice: 12, _conflictGroup: 'NEW-A', _conflictAction: 'UNRESOLVED' }, errorMessage: null, manualEntry: false }
  ]
}

const productErrorBatch = {
  ...batch,
  importType: 'PRODUCT',
  originalFilename: 'products-error.xlsx',
  totalRows: 1,
  validRows: 0,
  errorRows: 1,
  rows: [{ id: 23, sheetName: 'GMT库存产品清单', rowNumber: 10, status: 'ERROR', data: { sourceProductCode: '旧编号-C', productCode: 'NEW-C', model: '用于验证单行截断的超长型号文本', productConfiguration: '黑色', supplierName: '供应商甲', supplierTaxPrice: 10 }, errorMessage: '产品编号规则不存在，请检查产品分类', manualEntry: false }]
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
    commitProductReplace.mockResolvedValue({ ...structuredClone(productConflictBatch), status: 'COMMITTED', committedRows: 1 })
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
  it('does not auto-commit product imports and requires every duplicate group to be resolved', async () => {
    previewImport.mockResolvedValue(structuredClone(productConflictBatch))
    const wrapper = mount(ImportPanel, { props: { type: 'PRODUCT', title: '导入产品' } })
    await selectFile(wrapper, 'products.xlsx')

    expect(commitImport).not.toHaveBeenCalled()
    expect(commitProductReplace).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="product-conflict-keep-21"]').exists()).toBe(true)
    expect(wrapper.get('[data-test="commit-product-replace"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('GMT库存产品清单')
    expect(wrapper.text()).toContain('旧编号-A')
    expect(wrapper.text()).toContain('NEW-A')
  })

  it('sends explicit keep and skip decisions after irreversible confirmation', async () => {
    previewImport.mockResolvedValue(structuredClone(productConflictBatch))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mount(ImportPanel, { props: { type: 'PRODUCT', title: '导入产品' } })
    await selectFile(wrapper, 'products.xlsx')

    await wrapper.get('[data-test="product-conflict-keep-21"]').setValue()
    expect(wrapper.get('[data-test="commit-product-replace"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('[data-test="commit-product-replace"]').trigger('click')
    await flushPromises()

    expect(commitProductReplace).toHaveBeenCalledWith(8, { 21: 'KEEP', 22: 'SKIP' })
  })
  it('allows every conflict candidate to be explicitly skipped while forbidding an empty replacement', async () => {
    previewImport.mockResolvedValue(structuredClone(productConflictBatch))
    const wrapper = mount(ImportPanel, { props: { type: 'PRODUCT', title: '导入产品' } })
    await selectFile(wrapper, 'products.xlsx')

    expect(wrapper.find('[data-test="product-conflict-skip-21"]').exists()).toBe(true)
    await wrapper.get('[data-test="product-conflict-skip-21"]').trigger('click')
    await wrapper.get('[data-test="product-conflict-skip-22"]').trigger('click')
    expect(wrapper.get('[data-test="commit-product-replace"]').attributes('disabled')).toBeDefined()
    expect(commitProductReplace).not.toHaveBeenCalled()
  })
  it('shows the full product row error and keeps long product cells on one line', async () => {
    previewImport.mockResolvedValue(structuredClone(productErrorBatch))
    const wrapper = mount(ImportPanel, { props: { type: 'PRODUCT', title: '导入产品' } })
    await selectFile(wrapper, 'products-error.xlsx')

    const error = wrapper.get('[data-test="product-row-error-23"]')
    expect(error.text()).toBe('产品编号规则不存在，请检查产品分类')
    expect(error.attributes('title')).toBe('产品编号规则不存在，请检查产品分类')
    const model = wrapper.get('[data-test="product-model-23"]')
    expect(model.attributes('title')).toBe('用于验证单行截断的超长型号文本')
    expect(model.classes()).toContain('compact-cell')
    expect(wrapper.get('[data-test="commit-product-replace"]').attributes('disabled')).toBeDefined()
  })
})
