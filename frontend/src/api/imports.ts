export type ImportType = 'CUSTOMER' | 'COST' | 'INVENTORY' | 'SUPPLIER' | 'PRODUCT' | 'ORDER'
export type ProductConflictAction = 'OVERWRITE' | 'SKIP' | 'KEEP'
export type SupplierImportMode = 'OVERWRITE' | 'REPLACE_ALL'
export type ImportConflictAction = 'OVERWRITE' | 'SKIP'
export type ImportRowStatus = 'VALID' | 'ERROR' | 'IGNORED'

export interface ImportRow {
  id: number
  sheetName: string
  rowNumber: number
  status: ImportRowStatus
  data: Record<string, unknown>
  errorMessage: string | null
  manualEntry: boolean
}

export interface ImportBatch {
  batchId: number
  importType: ImportType
  originalFilename: string
  status: string
  totalRows: number
  validRows: number
  errorRows: number
  ignoredRows: number
  committedRows: number
  result: unknown
  rows: ImportRow[]
}

interface ApiResponse<T> { success: boolean; data: T; message: string }

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, options)
  const body = await response.json().catch(() => null) as ApiResponse<T> | null
  if (!response.ok || !body?.success) throw new Error(body?.message || '请求失败，请稍后重试')
  return body.data
}

function jsonOptions(method: 'POST' | 'PUT', data?: Record<string, unknown>): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: data ? JSON.stringify({ data }) : undefined
  }
}

export function previewImport(type: ImportType, file: File): Promise<ImportBatch> {
  const form = new FormData()
  form.append('file', file)
  return request(`/api/imports/preview?type=${type}`, { method: 'POST', body: form })
}

export function getImportBatch(id: number): Promise<ImportBatch> {
  return request(`/api/imports/${id}`)
}

export function addImportRow(id: number, data: Record<string, unknown>): Promise<ImportRow> {
  return request(`/api/imports/${id}/rows`, jsonOptions('POST', data))
}

export function updateImportRow(id: number, rowId: number, data: Record<string, unknown>): Promise<ImportRow> {
  return request(`/api/imports/${id}/rows/${rowId}`, jsonOptions('PUT', data))
}

export function commitImport(id: number, supplierMode?: SupplierImportMode, conflictActions?: Record<number, ImportConflictAction>): Promise<ImportBatch> {
  const data = { ...(supplierMode ? { supplierMode } : {}), ...(conflictActions ? { conflictActions } : {}) }
  const options = Object.keys(data).length > 0
    ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }
    : { method: 'POST' }
  return request(`/api/imports/${id}/commit`, options)
}
export function commitProductImport(id: number, actions: Record<number, ProductConflictAction>): Promise<ImportBatch> {
  return request(`/api/imports/${id}/commit`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ productConflictActions: actions })
  })
}

/** @deprecated Use commitProductImport with OVERWRITE or SKIP. */
export function commitProductReplace(id: number, actions: Record<number, ProductConflictAction>): Promise<ImportBatch> {
  return commitProductImport(id, actions)
}

export async function downloadImportErrors(id: number): Promise<Blob> {
  const response = await fetch(`/api/imports/${id}/errors.xlsx`)
  if (!response.ok) throw new Error('错误清单下载失败')
  return response.blob()
}
