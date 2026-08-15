import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  addImportRow,
  commitImport,
  commitProductReplace,
  downloadImportErrors,
  getImportBatch,
  previewImport,
  updateImportRow
} from './imports'

const batch = { batchId: 12, importType: 'CUSTOMER', rows: [] }

describe('导入接口客户端', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('预览使用 multipart，读取和提交使用正确地址', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockImplementation(async () => new Response(JSON.stringify({ success: true, data: batch, message: '' }), {
        status: 200, headers: { 'Content-Type': 'application/json' }
      }))
    const file = new File(['excel'], '客户.xlsx')

    await previewImport('CUSTOMER', file)
    const [previewUrl, previewOptions] = fetchMock.mock.calls[0]
    expect(previewUrl).toBe('/api/imports/preview?type=CUSTOMER')
    expect(previewOptions?.method).toBe('POST')
    expect(previewOptions?.body).toBeInstanceOf(FormData)

    await getImportBatch(12)
    await commitImport(12)
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/imports/12', undefined)
    expect(fetchMock).toHaveBeenNthCalledWith(3, '/api/imports/12/commit', expect.objectContaining({ method: 'POST' }))
  })

  it('新增、修改行和下载错误清单', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(JSON.stringify({ success: true, data: { id: 1 }, message: '' })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ success: true, data: { id: 1 }, message: '' })))
      .mockResolvedValueOnce(new Response('xlsx'))

    await addImportRow(12, { customerName: '甲公司' })
    await updateImportRow(12, 1, { customerName: '乙公司' })
    const blob = await downloadImportErrors(12)

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/imports/12/rows', expect.objectContaining({ method: 'POST' }))
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/imports/12/rows/1', expect.objectContaining({ method: 'PUT' }))
    expect(fetchMock).toHaveBeenNthCalledWith(3, '/api/imports/12/errors.xlsx')
    expect(blob).toBeInstanceOf(Blob)
  })
  it('提交供应商导入时发送所选导入策略', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      success: true, data: { ...batch, importType: 'SUPPLIER' }, message: ''
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    await commitImport(12, 'REPLACE_ALL')

    expect(fetchMock).toHaveBeenCalledWith('/api/imports/12/commit', expect.objectContaining({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ supplierMode: 'REPLACE_ALL' })
    }))
  })

  it('提交产品全量替换时发送每一行的明确保留或跳过决定', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      success: true, data: { ...batch, importType: 'PRODUCT' }, message: ''
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    await commitProductReplace(12, { 21: 'KEEP', 22: 'SKIP' })

    expect(fetchMock).toHaveBeenCalledWith('/api/imports/12/commit', expect.objectContaining({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ productConflictActions: { 21: 'KEEP', 22: 'SKIP' } })
    }))
  })
})
