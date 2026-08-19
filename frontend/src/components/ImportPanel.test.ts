import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ImportPanel from './ImportPanel.vue'
import type { ImportBatch } from '../api/imports'

const { previewImport, commitImport, commitProductReplace, updateImportRow } = vi.hoisted(() => ({ previewImport: vi.fn(), commitImport: vi.fn(), commitProductReplace: vi.fn(), updateImportRow: vi.fn() }))
vi.mock('../api/imports', () => ({ previewImport, commitImport, commitProductReplace, updateImportRow }))

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

const customerConflictBatch = {
  ...batch,
  importType: 'CUSTOMER',
  totalRows: 2,
  validRows: 2,
  rows: [
    { id: 41, sheetName: '客户', rowNumber: 2, status: 'VALID', data: { customerName: '客户甲', _conflict: true, _conflictGroup: '客户甲', _conflictLabel: '客户名称：客户甲', _conflictField: 'customerName', _conflictAction: 'SKIP' }, errorMessage: null, manualEntry: false },
    { id: 42, sheetName: '客户', rowNumber: 3, status: 'VALID', data: { customerName: '客户乙' }, errorMessage: null, manualEntry: false }
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

const orderBatch: ImportBatch = {
  ...batch,
  batchId: 18,
  importType: 'ORDER',
  originalFilename: 'orders.xlsx',
  totalRows: 3,
  validRows: 3,
  rows: [
    { id: 31, sheetName: '订单导入', rowNumber: 2, status: 'VALID', data: { externalOrderNo: 'EXT-001', customerCode: 'C001', orderDate: '2026-08-17', orderType: '工程订单', orderStatus: '正式订单', productCode: 'P001', customerPartNumber: 'CM-01', quantity: 2, salePrice: 99.5 }, errorMessage: null, manualEntry: false },
    { id: 32, sheetName: '订单导入', rowNumber: 3, status: 'VALID', data: { externalOrderNo: 'EXT-001', customerCode: 'C001', orderDate: '2026-08-17', orderType: '工程订单', orderStatus: '正式订单', productCode: 'P002', customerPartNumber: 'CM-02', quantity: 3, salePrice: 120 }, errorMessage: null, manualEntry: false },
    { id: 33, sheetName: '订单导入', rowNumber: 4, status: 'VALID', data: { externalOrderNo: 'EXT-002', customerCode: 'C002', orderDate: '2026-08-18', orderType: '零售订单', orderStatus: '草稿', productCode: 'P003', customerPartNumber: 'CM-03', quantity: 1, salePrice: 80 }, errorMessage: null, manualEntry: false }
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
    commitProductReplace.mockResolvedValue({ ...structuredClone(productConflictBatch), status: 'COMMITTED', committedRows: 1 })
  })

  it('shows the ORDER template download link only for ORDER imports', () => {
    const order = mount(ImportPanel, { props: { type: 'ORDER', title: '导入订单' } })
    const download = order.get('[data-test="download-import-template"]')
    expect(download.text()).toBe('下载 XLSX 模板')
    expect(download.attributes('href')).toBe('/api/imports/templates/ORDER.xlsx')
    expect(download.attributes('download')).toBeDefined()

    const customer = mount(ImportPanel, { props: { type: 'CUSTOMER', title: '导入客户' } })
    expect(customer.find('[data-test="download-import-template"]').exists()).toBe(true)
  })

  it('keeps the existing immediate flow for non-supplier imports', async () => {
    const wrapper = mount(ImportPanel, { props: { type: 'CUSTOMER', title: '导入客户' } })
    await selectFile(wrapper, 'customers.xlsx')

    expect(previewImport).toHaveBeenCalledWith('CUSTOMER', expect.any(File))
    expect(commitImport).toHaveBeenCalledWith(8)
    expect(wrapper.text()).toContain('成功导入 103 条数据')
    expect(wrapper.find('[data-test="commit-import"]').exists()).toBe(false)
  })

  it('previews a file dropped on the upload zone', async () => {
    const wrapper = mount(ImportPanel, { props: { type: 'ORDER', title: '导入订单' } })
    const file = new File(['x'], 'orders.xlsx')
    await wrapper.get('[data-test="import-dropzone"]').trigger('drop', { dataTransfer: { files: [file] } })
    await flushPromises()

    expect(previewImport).toHaveBeenCalledWith('ORDER', file)
  })

  it('highlights conflict rows and blocks customer commit until each conflict is resolved', async () => {
    previewImport.mockResolvedValue(structuredClone(customerConflictBatch))
    const wrapper = mount(ImportPanel, { props: { type: 'CUSTOMER', title: '导入客户' } })
    await selectFile(wrapper, 'customers.xlsx')

    expect(commitImport).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="conflict-row-41"]').classes()).toContain('conflict-row')
    expect(wrapper.get('[data-test="commit-import"]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-test="conflict-overwrite-41"]').trigger('click')
    expect(wrapper.get('[data-test="commit-import"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('[data-test="commit-import"]').trigger('click')
    await flushPromises()

    expect(updateImportRow).toHaveBeenCalledWith(8, 41, expect.objectContaining({ _conflictAction: 'OVERWRITE' }))
    expect(commitImport).toHaveBeenCalledWith(8)
  })

  it('groups ORDER preview rows by external order number and keeps the preview pending', async () => {
    previewImport.mockResolvedValue(structuredClone(orderBatch))
    const wrapper = mount(ImportPanel, { props: { type: 'ORDER', title: '导入订单' } })
    await selectFile(wrapper, 'orders.xlsx')

    expect(commitImport).not.toHaveBeenCalled()
    expect(wrapper.findAll('[data-test^="order-preview-group-"]')).toHaveLength(2)
    expect(wrapper.get('[data-test="order-preview-group-EXT-001"]').text()).toContain('EXT-001')
    expect(wrapper.get('[data-test="order-preview-group-EXT-001"]').text()).toContain('C001')
    expect(wrapper.get('[data-test="order-preview-group-EXT-001"]').text()).toContain('P001')
    expect(wrapper.get('[data-test="order-preview-group-EXT-001"]').text()).toContain('CM-02')
    expect(wrapper.get('[data-test="order-preview-group-EXT-002"]').text()).toContain('草稿')
  })

  it('shows the normalized backend ORDER status before the imported status text', async () => {
    const normalizedBatch = structuredClone(orderBatch)
    Object.assign(normalizedBatch.rows[0].data, { orderStatus: '正式订单', _normalizedStatus: 'DRAFT' })
    Object.assign(normalizedBatch.rows[1].data, { orderStatus: '正式订单', _normalizedStatus: 'DRAFT' })
    Object.assign(normalizedBatch.rows[2].data, { orderStatus: '正式订单', _normalizedStatus: 'PENDING_CUSTOMER_PAYMENT' })
    previewImport.mockResolvedValue(normalizedBatch)

    const wrapper = mount(ImportPanel, { props: { type: 'ORDER', title: '导入订单' } })
    await selectFile(wrapper, 'orders.xlsx')

    expect(wrapper.get('[data-test="order-preview-group-EXT-001"]').text()).toContain('状态：草稿')
    expect(wrapper.get('[data-test="order-preview-group-EXT-002"]').text()).toContain('状态：正式订单')
  })

  it('disables ORDER commit for error rows and empty valid-order batches', async () => {
    previewImport.mockResolvedValue({ ...structuredClone(orderBatch), validRows: 2, errorRows: 1, rows: [{ ...orderBatch.rows[0], status: 'ERROR', errorMessage: '产品不存在' }, orderBatch.rows[2]] })
    const wrapper = mount(ImportPanel, { props: { type: 'ORDER', title: '导入订单' } })
    await selectFile(wrapper, 'orders-error.xlsx')
    expect(wrapper.get('[data-test="commit-order-import"]').attributes('disabled')).toBeDefined()

    previewImport.mockResolvedValue({ ...structuredClone(orderBatch), validRows: 0, rows: [] })
    const empty = mount(ImportPanel, { props: { type: 'ORDER', title: '导入订单' } })
    await selectFile(empty, 'orders-empty.xlsx')
    expect(empty.get('[data-test="commit-order-import"]').attributes('disabled')).toBeDefined()
  })

  it('confirms ORDER inventory and customer-funds consequences before using commitImport', async () => {
    previewImport.mockResolvedValue(structuredClone(orderBatch))
    commitImport.mockResolvedValue({ ...structuredClone(orderBatch), status: 'COMMITTED', committedRows: 2 })
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mount(ImportPanel, { props: { type: 'ORDER', title: '导入订单' } })
    await selectFile(wrapper, 'orders.xlsx')

    await wrapper.get('[data-test="commit-order-import"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('正式订单将按现有逻辑锁定库存'))
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('草稿不锁定'))
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('客户余额不会自动扣减'))
    expect(commitImport).toHaveBeenCalledWith(18)
    expect(commitProductReplace).not.toHaveBeenCalled()
  })

  it('keeps failed ORDER groups visible and reports a partial commit', async () => {
    previewImport.mockResolvedValue(structuredClone(orderBatch))
    const partial = structuredClone(orderBatch)
    partial.status = 'COMMITTED'
    partial.validRows = 2
    partial.errorRows = 1
    partial.committedRows = 2
    partial.rows[2].status = 'ERROR'
    partial.rows[2].errorMessage = '外部订单号已存在，订单提交已取消'
    partial.result = { createdOrders: 1, failedOrders: 1, committed: 2, errors: 1, orderErrors: [{ externalOrderNo: 'EXT-002', error: '外部订单号已存在，订单提交已取消' }] }
    commitImport.mockResolvedValue(partial)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mount(ImportPanel, { props: { type: 'ORDER', title: '导入订单' } })
    await selectFile(wrapper, 'orders.xlsx')

    await wrapper.get('[data-test="commit-order-import"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="order-partial-result"]').text()).toContain('成功订单 1')
    expect(wrapper.get('[data-test="order-partial-result"]').text()).toContain('失败订单 1')
    expect(wrapper.get('[data-test="order-partial-result"]').text()).toContain('成功行 2')
    expect(wrapper.get('[data-test="order-partial-result"]').text()).toContain('失败行 1')
    expect(wrapper.get('[data-test="order-preview-group-EXT-002"]').text()).toContain('外部订单号已存在')
    expect(wrapper.get('[data-test="download-import-errors"]').attributes('href')).toBe('/api/imports/18/errors.xlsx')
    expect(wrapper.find('[data-test="import-success"]').exists()).toBe(false)
  })

  it('clears the ORDER preview after every order commits successfully', async () => {
    previewImport.mockResolvedValue(structuredClone(orderBatch))
    commitImport.mockResolvedValue({
      ...structuredClone(orderBatch), status: 'COMMITTED', committedRows: 3,
      result: { createdOrders: 2, failedOrders: 0, committed: 3, errors: 0, orderErrors: [] }
    })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mount(ImportPanel, { props: { type: 'ORDER', title: '导入订单' } })
    await selectFile(wrapper, 'orders.xlsx')

    await wrapper.get('[data-test="commit-order-import"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="import-success"]').text()).toContain('成功导入 3 条数据')
    expect(wrapper.find('[data-test="order-partial-result"]').exists()).toBe(false)
    expect(wrapper.find('[data-test^="order-preview-group-"]').exists()).toBe(false)
  })

  it('labels an ORDER commit with zero successful orders as failed', async () => {
    previewImport.mockResolvedValue(structuredClone(orderBatch))
    const failed = structuredClone(orderBatch)
    failed.status = 'COMMITTED'
    failed.validRows = 0
    failed.errorRows = 3
    failed.committedRows = 0
    for (const row of failed.rows) {
      row.status = 'ERROR'
      row.errorMessage = '外部订单号已存在，订单提交已取消'
    }
    failed.result = { createdOrders: 0, failedOrders: 2, committed: 0, errors: 3, orderErrors: [] }
    commitImport.mockResolvedValue(failed)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mount(ImportPanel, { props: { type: 'ORDER', title: '导入订单' } })
    await selectFile(wrapper, 'orders.xlsx')

    await wrapper.get('[data-test="commit-order-import"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="order-partial-result"] strong').text()).toBe('订单导入失败')
    expect(wrapper.emitted('message')?.at(-1)).toEqual(['订单导入失败：成功 0 单，失败 2 单', 'error'])
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
