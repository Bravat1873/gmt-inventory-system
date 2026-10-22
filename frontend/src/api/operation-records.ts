export interface OperationRecord {
  id: number; action: string; moduleName: string; httpMethod: string; requestPath: string
  actorId: number | null; actorName: string; actorUsername: string; operatedAt: string
  completedAt?: string; status: 'SUCCESS' | 'FAILED' | 'REVERSED' | 'LEGACY'
  changeCount: number; affectedModules?: string; relatedRecords?: string; clientIp?: string
  nonReversibleReason?: string; resultMessage?: string; reversesId?: number
  reversedByOperationId?: number; reversalReason?: string
}
export interface OperationChange {
  id: number; module: string; table: string; recordKey: string; label: string
  type: 'INSERT' | 'UPDATE' | 'DELETE'; preserved: boolean
  fields: { field: string; before: unknown; after: unknown }[]
}
export interface OperationPage { items: OperationRecord[]; total: number; page: number; pageSize: number }
export interface OperationDetail {
  operation: OperationRecord; changes: OperationChange[]; changePage: number; changePageSize: number
  canUndo: boolean; undoReason: string
  blockers: { id: number; action: string; actorName: string; operatedAt: string }[]
}
async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(url, { ...options, credentials: 'include', headers: { 'Content-Type': 'application/json', ...options.headers } })
  const body = await response.json().catch(() => null)
  if (!response.ok || !body?.success) throw new Error(body?.message || '操作记录请求失败，请重试')
  return body.data
}
export const loadOperations = (params: URLSearchParams) => request<OperationPage>(`/api/operation-records?${params}`)
export const loadOperation = (id: number, changePage = 1) => request<OperationDetail>(`/api/operation-records/${id}?changePage=${changePage}`)
export const undoOperation = (id: number, reason: string) => request<{ reversalOperationId: number }>(`/api/operation-records/${id}/undo`, { method: 'POST', body: JSON.stringify({ reason }) })
